import { withSupabase } from "@supabase/server";
import { createClient, type SupabaseClient, type User } from "@supabase/supabase-js";
import {
    type AccountDeletionPreparation,
    type AccountDeletionState,
    type AccountDeletionStatus,
    handleAccountDelete,
    type ReauthenticationCredential,
} from "./core.ts";
import { hasLinkedIdentity, isSessionAlreadyRevoked, isUserNotFound, readProviderSubject } from "./identity.ts";

type AccountDeletionDatabase = {
    public: {
        Tables: {
            account_deletion_requests: {
                Row: {
                    user_id: string;
                    idempotency_key: string;
                    status: "prepared" | "completed";
                    completed_at: string | null;
                };
                Insert: Record<string, never>;
                Update: Record<string, never>;
                Relationships: [];
            };
        };
        Views: Record<string, never>;
        Functions: {
            prepare_account_deletion: {
                Args: {
                    p_user_id: string;
                    p_idempotency_key: string;
                };
                Returns: Array<{
                    status: string;
                    effective_idempotency_key: string;
                }>;
            };
            mark_account_deletion_completed: {
                Args: {
                    p_user_id: string;
                    p_idempotency_key: string;
                };
                Returns: Array<{ status: string }>;
            };
        };
    };
};

export default {
    fetch: withSupabase<AccountDeletionDatabase>({ auth: "user" }, (request, context) => {
        const verifiedUserId = context.userClaims?.id ?? "";
        const bearerToken = readBearerToken(request.headers.get("authorization"));
        let verifiedCurrentUser: User | null = null;

        return handleAccountDelete(request, {
            verifiedUserId,
            getCurrentUser: async () => {
                const { data, error } = await context.supabase.auth.getUser();
                if (error !== null && !isUserNotFound(error)) {
                    throw new Error("Verified account lookup failed");
                }
                verifiedCurrentUser = error === null ? data.user : null;
                return verifiedCurrentUser === null ? null : { id: verifiedCurrentUser.id };
            },
            getDeletionState: (userId, idempotencyKey) =>
                getDeletionState(context.supabaseAdmin, userId, idempotencyKey),
            reauthenticate: (credential) => {
                if (verifiedCurrentUser === null) return Promise.resolve(null);
                return reauthenticateIsolated(verifiedCurrentUser, credential);
            },
            prepareDeletion: async (userId, idempotencyKey) => {
                const { data, error } = await context.supabaseAdmin.rpc(
                    "prepare_account_deletion",
                    {
                        p_user_id: userId,
                        p_idempotency_key: idempotencyKey,
                    },
                );
                if (error !== null) throw new Error("Account deletion preparation failed");
                return requireRpcPreparation(data);
            },
            // Revoke by the originally verified bearer through the admin API.
            // The isolated reauthentication session is never used for this step.
            revokeSessions: async () => {
                if (bearerToken === null) return false;
                const { error } = await context.supabaseAdmin.auth.admin.signOut(
                    bearerToken,
                    "global",
                );
                return error === null || isSessionAlreadyRevoked(error);
            },
            deleteUser: async (userId) => {
                const { error } = await context.supabaseAdmin.auth.admin.deleteUser(userId);
                if (error === null) return "deleted";
                return isUserNotFound(error) ? "not_found" : "failed";
            },
            markCompleted: async (userId, idempotencyKey) => {
                const { data, error } = await context.supabaseAdmin.rpc(
                    "mark_account_deletion_completed",
                    {
                        p_user_id: userId,
                        p_idempotency_key: idempotencyKey,
                    },
                );
                return error === null && requireRpcStatus(data) === "completed";
            },
        });
    }),
};

async function getDeletionState(
    client: SupabaseClient<AccountDeletionDatabase>,
    userId: string,
    idempotencyKey: string,
): Promise<AccountDeletionState> {
    const exact = await client
        .from("account_deletion_requests")
        .select("status,idempotency_key")
        .eq("user_id", userId)
        .eq("idempotency_key", idempotencyKey)
        .maybeSingle();
    if (exact.error !== null) throw new Error("Account deletion status lookup failed");
    if (exact.data !== null) return deletionStateFromRow(exact.data);

    const prepared = await client
        .from("account_deletion_requests")
        .select("status,idempotency_key")
        .eq("user_id", userId)
        .eq("status", "prepared")
        .maybeSingle();
    if (prepared.error !== null) throw new Error("Account deletion status lookup failed");
    if (prepared.data !== null) return deletionStateFromRow(prepared.data);

    const completed = await client
        .from("account_deletion_requests")
        .select("status,idempotency_key")
        .eq("user_id", userId)
        .eq("status", "completed")
        .order("completed_at", { ascending: false })
        .limit(1)
        .maybeSingle();
    if (completed.error !== null) throw new Error("Account deletion status lookup failed");
    return completed.data === null ? null : deletionStateFromRow(completed.data);
}

function deletionStateFromRow(row: unknown): AccountDeletionPreparation {
    if (
        !isRecord(row) ||
        (row.status !== "prepared" && row.status !== "completed") ||
        typeof row.idempotency_key !== "string"
    ) {
        throw new Error("Invalid account deletion state");
    }
    return {
        status: row.status,
        effectiveIdempotencyKey: row.idempotency_key,
    };
}

async function reauthenticateIsolated(
    currentUser: User,
    credential: ReauthenticationCredential,
): Promise<{ id: string } | null> {
    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const publicKey = Deno.env.get("SUPABASE_ANON_KEY");
    if (!supabaseUrl || !publicKey) {
        throw new Error("Isolated reauthentication environment is unavailable");
    }

    const client = createClient(supabaseUrl, publicKey, {
        auth: {
            autoRefreshToken: false,
            detectSessionInUrl: false,
            persistSession: false,
        },
    });
    let temporarySessionEstablished = false;
    let reauthenticatedUserId: string | null = null;

    if (credential.type === "password") {
        const email = currentUser.email;
        if (typeof email !== "string" || email.length === 0) return null;
        const { data, error } = await client.auth.signInWithPassword({
            email,
            password: credential.password,
        });
        temporarySessionEstablished = data.session !== null;
        if (error === null && data.user?.id === currentUser.id) {
            reauthenticatedUserId = data.user.id;
        }
    } else {
        const providerSubject = readProviderSubject(credential.idToken);
        if (
            providerSubject === null ||
            !hasLinkedIdentity(currentUser, credential.provider, providerSubject)
        ) {
            return null;
        }

        const { data, error } = await client.auth.signInWithIdToken({
            provider: credential.provider,
            token: credential.idToken,
            nonce: credential.nonce,
        });
        temporarySessionEstablished = data.session !== null;
        if (
            error === null &&
            data.user?.id === currentUser.id &&
            hasLinkedIdentity(data.user, credential.provider, providerSubject)
        ) {
            reauthenticatedUserId = data.user.id;
        }
    }

    if (temporarySessionEstablished) {
        const { error } = await client.auth.signOut({ scope: "local" });
        if (error !== null) {
            throw new Error("Isolated reauthentication cleanup failed");
        }
    }
    return reauthenticatedUserId === null ? null : { id: reauthenticatedUserId };
}

function requireRpcPreparation(data: unknown): AccountDeletionPreparation {
    const row = Array.isArray(data) ? data[0] : data;
    if (
        !isRecord(row) ||
        typeof row.status !== "string" ||
        typeof row.effective_idempotency_key !== "string"
    ) {
        throw new Error("Invalid account deletion RPC response");
    }
    switch (row.status) {
        case "prepared":
        case "completed":
        case "ownership_conflict":
        case "storage_conflict":
            return {
                status: row.status,
                effectiveIdempotencyKey: row.effective_idempotency_key,
            };
        default:
            throw new Error("Invalid account deletion RPC status");
    }
}

function requireRpcStatus(data: unknown): Exclude<AccountDeletionStatus, null> {
    const row = Array.isArray(data) ? data[0] : data;
    if (!isRecord(row) || typeof row.status !== "string") {
        throw new Error("Invalid account deletion RPC response");
    }
    if (row.status === "completed") return row.status;
    throw new Error("Invalid account deletion RPC status");
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readBearerToken(authorization: string | null): string | null {
    if (authorization === null) return null;
    const match = /^Bearer ([A-Za-z0-9._-]+)$/.exec(authorization);
    return match?.[1] ?? null;
}
