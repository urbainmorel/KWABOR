const MAX_REQUEST_BODY_BYTES = 20_000;
const MAX_PASSWORD_LENGTH = 512;
const MAX_ID_TOKEN_LENGTH = 16_384;
const MIN_ID_TOKEN_LENGTH = 20;
const MIN_NONCE_LENGTH = 32;
const MAX_NONCE_LENGTH = 128;

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ID_TOKEN_PATTERN = /^[A-Za-z0-9._-]+$/;
const NONCE_PATTERN = /^[A-Za-z0-9_-]+$/;

export type AccountDeletionStatus =
    | "prepared"
    | "completed"
    | "ownership_conflict"
    | "storage_conflict"
    | null;

export interface AccountDeletionPreparation {
    status: Exclude<AccountDeletionStatus, null>;
    effectiveIdempotencyKey: string;
}

export type AccountDeletionState = AccountDeletionPreparation | null;

export type AccountDeletionUserResult = "deleted" | "not_found" | "failed";

export type ReauthenticationCredential =
    | {
        type: "password";
        password: string;
    }
    | {
        type: "social";
        provider: "google" | "apple";
        idToken: string;
        nonce: string;
    };

export interface AccountDeleteDependencies {
    verifiedUserId: string;
    getCurrentUser: () => Promise<{ id: string } | null>;
    getDeletionState: (
        userId: string,
        idempotencyKey: string,
    ) => Promise<AccountDeletionState>;
    reauthenticate: (
        credential: ReauthenticationCredential,
    ) => Promise<{ id: string } | null>;
    prepareDeletion: (
        userId: string,
        idempotencyKey: string,
    ) => Promise<AccountDeletionPreparation>;
    revokeSessions: () => Promise<boolean>;
    deleteUser: (userId: string) => Promise<AccountDeletionUserResult>;
    markCompleted: (userId: string, idempotencyKey: string) => Promise<boolean>;
}

interface AccountDeletePayload {
    idempotencyKey: string;
    credential: ReauthenticationCredential;
}

export async function handleAccountDelete(
    request: Request,
    dependencies: AccountDeleteDependencies,
): Promise<Response> {
    if (request.method !== "POST") {
        return errorResponse(405, "method_not_allowed", { Allow: "POST" });
    }

    if (!UUID_PATTERN.test(dependencies.verifiedUserId)) {
        return errorResponse(401, "unauthorized");
    }

    const payload = await parsePayload(request);
    if (payload === null) {
        return errorResponse(400, "invalid_request");
    }

    try {
        let existingState = await dependencies.getDeletionState(
            dependencies.verifiedUserId,
            payload.idempotencyKey,
        );
        const currentUser = await dependencies.getCurrentUser();

        if (currentUser === null) {
            // Close the crash window between Auth deletion and tombstone completion.
            // Refresh once because another concurrent request may have prepared the
            // deletion after the initial state lookup.
            existingState = existingState ??
                await dependencies.getDeletionState(
                    dependencies.verifiedUserId,
                    payload.idempotencyKey,
                );
            if (existingState?.status === "completed") {
                return new Response(null, { status: 204 });
            }
            if (existingState?.status === "prepared") {
                const completed = await dependencies.markCompleted(
                    dependencies.verifiedUserId,
                    existingState.effectiveIdempotencyKey,
                );
                return completed
                    ? new Response(null, { status: 204 })
                    : errorResponse(503, "deletion_completion_pending");
            }
            return errorResponse(401, "unauthorized");
        }
        if (currentUser.id !== dependencies.verifiedUserId) {
            return errorResponse(401, "unauthorized");
        }

        const reauthenticatedUser = await dependencies.reauthenticate(payload.credential);
        if (reauthenticatedUser?.id !== dependencies.verifiedUserId) {
            return errorResponse(401, "reauthentication_failed");
        }

        const preparation = await dependencies.prepareDeletion(
            dependencies.verifiedUserId,
            payload.idempotencyKey,
        );
        if (!UUID_PATTERN.test(preparation.effectiveIdempotencyKey)) {
            return errorResponse(503, "temporarily_unavailable");
        }
        const preparationError = preparationStatusError(preparation.status);
        if (preparationError !== null) {
            return preparationError;
        }

        if (!await dependencies.revokeSessions()) {
            return errorResponse(503, "deletion_prepared_retryable");
        }
        const finalPreparation = await dependencies.prepareDeletion(
            dependencies.verifiedUserId,
            preparation.effectiveIdempotencyKey,
        );
        if (
            finalPreparation.effectiveIdempotencyKey !== preparation.effectiveIdempotencyKey ||
            !UUID_PATTERN.test(finalPreparation.effectiveIdempotencyKey)
        ) {
            return errorResponse(503, "deletion_prepared_retryable");
        }
        const finalPreparationError = preparationStatusError(finalPreparation.status);
        if (finalPreparationError !== null) {
            return finalPreparationError;
        }
        const deletionResult = await dependencies.deleteUser(dependencies.verifiedUserId);
        if (deletionResult === "failed") {
            return errorResponse(503, "deletion_prepared_retryable");
        }
        if (
            !await dependencies.markCompleted(
                dependencies.verifiedUserId,
                preparation.effectiveIdempotencyKey,
            )
        ) {
            return errorResponse(503, "deletion_completion_pending");
        }
        return new Response(null, { status: 204 });
    } catch {
        return errorResponse(503, "temporarily_unavailable");
    }
}

async function parsePayload(request: Request): Promise<AccountDeletePayload | null> {
    const contentType = request.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
    if (contentType !== "application/json") return null;

    const declaredLength = Number(request.headers.get("content-length") ?? "0");
    if (
        !Number.isSafeInteger(declaredLength) ||
        declaredLength < 0 ||
        declaredLength > MAX_REQUEST_BODY_BYTES
    ) {
        return null;
    }

    let rawBody: string;
    try {
        rawBody = await request.text();
    } catch {
        return null;
    }
    if (new TextEncoder().encode(rawBody).byteLength > MAX_REQUEST_BODY_BYTES) {
        return null;
    }

    let body: unknown;
    try {
        body = JSON.parse(rawBody);
    } catch {
        return null;
    }
    if (!isRecord(body) || !hasExactKeys(body, ["idempotency_key", "credential"])) {
        return null;
    }

    const idempotencyKey = body.idempotency_key;
    if (typeof idempotencyKey !== "string" || !UUID_PATTERN.test(idempotencyKey)) {
        return null;
    }
    const credential = parseCredential(body.credential);
    return credential === null ? null : { idempotencyKey, credential };
}

function parseCredential(value: unknown): ReauthenticationCredential | null {
    if (!isRecord(value) || typeof value.type !== "string") return null;
    if (value.type === "password") {
        if (!hasExactKeys(value, ["type", "password"])) return null;
        if (
            typeof value.password !== "string" ||
            value.password.length === 0 ||
            value.password.length > MAX_PASSWORD_LENGTH
        ) {
            return null;
        }
        return { type: "password", password: value.password };
    }
    if (value.type === "social") {
        if (!hasExactKeys(value, ["type", "provider", "id_token", "nonce"])) return null;
        if (value.provider !== "google" && value.provider !== "apple") return null;
        if (
            typeof value.id_token !== "string" ||
            value.id_token.length < MIN_ID_TOKEN_LENGTH ||
            value.id_token.length > MAX_ID_TOKEN_LENGTH ||
            !ID_TOKEN_PATTERN.test(value.id_token)
        ) {
            return null;
        }
        if (
            typeof value.nonce !== "string" ||
            value.nonce.length < MIN_NONCE_LENGTH ||
            value.nonce.length > MAX_NONCE_LENGTH ||
            !NONCE_PATTERN.test(value.nonce)
        ) {
            return null;
        }
        return {
            type: "social",
            provider: value.provider,
            idToken: value.id_token,
            nonce: value.nonce,
        };
    }
    return null;
}

function preparationStatusError(status: Exclude<AccountDeletionStatus, null>): Response | null {
    switch (status) {
        case "prepared":
        case "completed":
            return null;
        case "ownership_conflict":
            return errorResponse(409, "organization_ownership_conflict");
        case "storage_conflict":
            return errorResponse(409, "storage_objects_conflict");
    }
}

function errorResponse(
    status: number,
    errorCode: string,
    extraHeaders: Record<string, string> = {},
): Response {
    return Response.json(
        { error_code: errorCode },
        {
            status,
            headers: {
                "Cache-Control": "no-store",
                ...extraHeaders,
            },
        },
    );
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, expectedKeys: string[]): boolean {
    const keys = Object.keys(value).sort();
    const sortedExpectedKeys = [...expectedKeys].sort();
    return keys.length === expectedKeys.length &&
        keys.every((key, index) => key === sortedExpectedKeys[index]);
}
