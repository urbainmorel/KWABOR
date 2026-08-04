const MAX_REQUEST_BODY_BYTES = 256;
const STEP_UP_MAX_AGE_SECONDS = 300;
const STEP_UP_MAX_FUTURE_SKEW_SECONDS = 30;

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export type AccountDeletionStatus =
    | "prepared"
    | "completed"
    | "ownership_conflict"
    | "storage_conflict"
    | "session_not_live";

export interface AccountDeletionPreparation {
    status: AccountDeletionStatus;
    effectiveIdempotencyKey: string;
}

export type AccountDeletionUserResult = "deleted" | "not_found" | "failed";

export interface AccountDeleteDependencies {
    verifiedUserId: string;
    jwtClaims: unknown;
    nowEpochSeconds: number;
    getCurrentUser: () => Promise<{ id: string } | null>;
    prepareDeletionWithSession: (
        userId: string,
        sessionId: string,
        idempotencyKey: string,
    ) => Promise<AccountDeletionPreparation>;
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
}

interface StepUpSession {
    sessionId: string;
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

    const stepUpSession = readRecentStepUpSession(
        dependencies.jwtClaims,
        dependencies.verifiedUserId,
        dependencies.nowEpochSeconds,
    );
    if (stepUpSession === null) {
        return errorResponse(401, "reauthentication_failed");
    }

    try {
        const currentUser = await dependencies.getCurrentUser();
        if (currentUser === null) {
            return errorResponse(401, "reauthentication_failed");
        }
        if (currentUser.id !== dependencies.verifiedUserId) {
            return errorResponse(401, "unauthorized");
        }

        const preparation = await dependencies.prepareDeletionWithSession(
            dependencies.verifiedUserId,
            stepUpSession.sessionId,
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

export function readRecentStepUpSession(
    claims: unknown,
    verifiedUserId: string,
    nowEpochSeconds: number,
): StepUpSession | null {
    if (
        !isRecord(claims) ||
        claims.sub !== verifiedUserId ||
        typeof claims.session_id !== "string" ||
        !UUID_PATTERN.test(claims.session_id) ||
        !Number.isSafeInteger(nowEpochSeconds) ||
        nowEpochSeconds < 0 ||
        !Array.isArray(claims.amr) ||
        claims.amr.length === 0
    ) {
        return null;
    }

    let latestMethod: string | null = null;
    let latestTimestamp = Number.NEGATIVE_INFINITY;
    for (const entry of claims.amr) {
        if (
            !isRecord(entry) ||
            typeof entry.method !== "string" ||
            entry.method.length === 0 ||
            typeof entry.timestamp !== "number" ||
            !Number.isSafeInteger(entry.timestamp) ||
            entry.timestamp < 0
        ) {
            return null;
        }
        if (entry.timestamp >= latestTimestamp) {
            latestMethod = entry.method;
            latestTimestamp = entry.timestamp;
        }
    }

    if (
        (latestMethod !== "password" && latestMethod !== "oauth") ||
        latestTimestamp < nowEpochSeconds - STEP_UP_MAX_AGE_SECONDS ||
        latestTimestamp > nowEpochSeconds + STEP_UP_MAX_FUTURE_SKEW_SECONDS
    ) {
        return null;
    }

    return { sessionId: claims.session_id };
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
    if (!isRecord(body) || !hasExactKeys(body, ["idempotency_key"])) {
        return null;
    }

    const idempotencyKey = body.idempotency_key;
    return typeof idempotencyKey === "string" && UUID_PATTERN.test(idempotencyKey) ? { idempotencyKey } : null;
}

function preparationStatusError(status: AccountDeletionStatus): Response | null {
    switch (status) {
        case "prepared":
        case "completed":
            return null;
        case "ownership_conflict":
            return errorResponse(409, "organization_ownership_conflict");
        case "storage_conflict":
            return errorResponse(409, "storage_objects_conflict");
        case "session_not_live":
            return errorResponse(401, "reauthentication_failed");
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
