import { withSupabase } from "@supabase/server";
import { type AccountDeletionPreparation, type AccountDeletionStatus, handleAccountDelete } from "./core.ts";
import { isLiveSessionRequired, isSessionAlreadyRevoked, isUserNotFound } from "./identity.ts";

type AccountDeletionDatabase = {
    public: {
        Tables: Record<string, never>;
        Views: Record<string, never>;
        Functions: {
            prepare_account_deletion_with_session: {
                Args: {
                    p_user_id: string;
                    p_session_id: string;
                    p_idempotency_key: string;
                };
                Returns: Array<{
                    status: string;
                    effective_idempotency_key: string;
                }>;
            };
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

        return handleAccountDelete(request, {
            verifiedUserId,
            jwtClaims: context.jwtClaims,
            nowEpochSeconds: Math.floor(Date.now() / 1_000),
            getCurrentUser: async () => {
                const { data, error } = await context.supabase.auth.getUser();
                if (
                    error !== null &&
                    !isUserNotFound(error) &&
                    !isSessionAlreadyRevoked(error)
                ) {
                    throw new Error("Verified account lookup failed");
                }
                return error === null && data.user !== null ? { id: data.user.id } : null;
            },
            prepareDeletionWithSession: async (userId, sessionId, idempotencyKey) => {
                const { data, error } = await context.supabaseAdmin.rpc(
                    "prepare_account_deletion_with_session",
                    {
                        p_user_id: userId,
                        p_session_id: sessionId,
                        p_idempotency_key: idempotencyKey,
                    },
                );
                if (error !== null) {
                    if (isLiveSessionRequired(error)) {
                        return {
                            status: "session_not_live",
                            effectiveIdempotencyKey: idempotencyKey,
                        };
                    }
                    throw new Error("Authorized account deletion preparation failed");
                }
                return requireRpcPreparation(data);
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
        case "session_not_live":
            return {
                status: row.status,
                effectiveIdempotencyKey: row.effective_idempotency_key,
            };
        default:
            throw new Error("Invalid account deletion RPC status");
    }
}

function requireRpcStatus(data: unknown): AccountDeletionStatus {
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
