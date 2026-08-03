import {
    type AccountDeleteDependencies,
    type AccountDeletionPreparation,
    handleAccountDelete,
    readRecentStepUpSession,
} from "./core.ts";

const USER_ID = "11111111-1111-4111-8111-111111111111";
const OTHER_USER_ID = "22222222-2222-4222-8222-222222222222";
const SESSION_ID = "33333333-3333-4333-8333-333333333333";
const IDEMPOTENCY_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const RETRY_IDEMPOTENCY_KEY = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const NOW = 1_800_000_000;

Deno.test("rejects non-POST requests before inspecting identity or state", async () => {
    const fake = dependencies();
    const response = await handleAccountDelete(
        new Request("https://kwabor.test/account-delete", { method: "GET" }),
        fake.dependencies,
    );

    assertEquals(response.status, 405);
    assertEquals(response.headers.get("allow"), "POST");
    assertEquals(fake.calls, []);
});

Deno.test("rejects an unverified bearer identity before mutation", async () => {
    const fake = dependencies({ verifiedUserId: "not-a-uuid" });

    const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

    await assertError(response, 401, "unauthorized");
    assertEquals(fake.calls, []);
});

Deno.test("accepts only the exact idempotency body and never accepts credentials", async () => {
    const invalidBodies: unknown[] = [
        {},
        null,
        { idempotency_key: "not-a-uuid" },
        { idempotency_key: IDEMPOTENCY_KEY, unexpected: true },
        { idempotency_key: IDEMPOTENCY_KEY, email: "person@example.com" },
        { idempotency_key: IDEMPOTENCY_KEY, password: "secret" },
        { idempotency_key: IDEMPOTENCY_KEY, id_token: "header.payload.signature" },
        { idempotency_key: IDEMPOTENCY_KEY, nonce: "secret-nonce" },
        {
            idempotency_key: IDEMPOTENCY_KEY,
            credential: { type: "password", password: "secret" },
        },
    ];

    for (const body of invalidBodies) {
        const fake = dependencies();
        const response = await handleAccountDelete(jsonRequest(body), fake.dependencies);
        await assertError(response, 400, "invalid_request");
        assertEquals(fake.calls, []);
    }

    for (
        const request of [
            new Request("https://kwabor.test/account-delete", {
                method: "POST",
                headers: { "Content-Type": "text/plain" },
                body: JSON.stringify({ idempotency_key: IDEMPOTENCY_KEY }),
            }),
            new Request("https://kwabor.test/account-delete", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: "not-json",
            }),
            new Request("https://kwabor.test/account-delete", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Content-Length": "257",
                },
                body: JSON.stringify({ idempotency_key: IDEMPOTENCY_KEY }),
            }),
        ]
    ) {
        const fake = dependencies();
        const response = await handleAccountDelete(request, fake.dependencies);
        await assertError(response, 400, "invalid_request");
        assertEquals(fake.calls, []);
    }
});

Deno.test("rejects malformed, mismatched, stale, future, or weak AMR claims", async () => {
    const invalidClaims: unknown[] = [
        null,
        {},
        freshClaims({ sub: OTHER_USER_ID }),
        freshClaims({ session_id: "not-a-uuid" }),
        freshClaims({ amr: undefined }),
        freshClaims({ amr: [] }),
        freshClaims({ amr: [null] }),
        freshClaims({ amr: [{ method: "password" }] }),
        freshClaims({ amr: [{ method: "password", timestamp: "1800000000" }] }),
        freshClaims({ amr: [{ method: "password", timestamp: NOW + 0.5 }] }),
        freshClaims({ amr: [{ method: "", timestamp: NOW }] }),
        freshClaims({ amr: [{ method: "password", timestamp: NOW - 301 }] }),
        freshClaims({ amr: [{ method: "oauth", timestamp: NOW + 31 }] }),
        freshClaims({ amr: [{ method: "otp", timestamp: NOW }] }),
        freshClaims({ amr: [{ method: "token_refresh", timestamp: NOW }] }),
        freshClaims({
            amr: [
                { method: "password", timestamp: NOW },
                { method: "otp", timestamp: NOW },
            ],
        }),
    ];

    for (const jwtClaims of invalidClaims) {
        const fake = dependencies({ jwtClaims });
        const response = await handleAccountDelete(deletionRequest(), fake.dependencies);
        await assertError(response, 401, "reauthentication_failed");
        assertEquals(fake.calls, []);
    }
});

Deno.test("selects the latest AMR by timestamp and then by last array index", () => {
    for (
        const amr of [
            [{ method: "password", timestamp: NOW - 300 }],
            [{ method: "oauth", timestamp: NOW + 30 }],
            [
                { method: "password", timestamp: NOW },
                { method: "otp", timestamp: NOW - 1 },
            ],
            [
                { method: "otp", timestamp: NOW },
                { method: "oauth", timestamp: NOW },
            ],
        ]
    ) {
        assertEquals(
            readRecentStepUpSession(freshClaims({ amr }), USER_ID, NOW),
            { sessionId: SESSION_ID },
        );
    }

    assertEquals(
        readRecentStepUpSession(
            freshClaims({
                amr: [
                    { method: "oauth", timestamp: NOW },
                    { method: "token_refresh", timestamp: NOW },
                ],
            }),
            USER_ID,
            NOW,
        ),
        null,
    );
});

Deno.test("requires a live Auth user matching the signed bearer user", async () => {
    for (
        const [currentUserId, errorCode] of [
            [null, "reauthentication_failed"],
            [OTHER_USER_ID, "unauthorized"],
        ] as const
    ) {
        const fake = dependencies({
            getCurrentUser: () => Promise.resolve(currentUserId === null ? null : { id: currentUserId }),
        });

        const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

        await assertError(response, 401, errorCode);
        assertEquals(fake.calls, ["current-user"]);
    }
});

Deno.test("requires the signed session to remain live in the atomic first mutation", async () => {
    const fake = dependencies({
        prepareAuthorizedResult: {
            status: "session_not_live",
            effectiveIdempotencyKey: IDEMPOTENCY_KEY,
        },
    });

    const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

    await assertError(response, 401, "reauthentication_failed");
    assertEquals(fake.calls, ["current-user", "prepare-authorized"]);
    assertEquals(fake.authorizedSessionIds, [SESSION_ID]);
});

Deno.test("returns owner and storage conflicts without revoking sessions", async () => {
    for (
        const [status, errorCode] of [
            ["ownership_conflict", "organization_ownership_conflict"],
            ["storage_conflict", "storage_objects_conflict"],
        ] as const
    ) {
        const fake = dependencies({
            prepareAuthorizedResult: {
                status,
                effectiveIdempotencyKey: IDEMPOTENCY_KEY,
            },
        });

        const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

        await assertError(response, 409, errorCode);
        assertEquals(fake.calls, ["current-user", "prepare-authorized"]);
    }
});

Deno.test("prepares atomically, revokes, rechecks, deletes, and tombstones in strict order", async () => {
    const fake = dependencies();

    const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

    assertEquals(response.status, 204);
    assertEquals(
        fake.calls,
        ["current-user", "prepare-authorized", "revoke", "prepare", "delete", "complete"],
    );
    assertEquals(fake.authorizedSessionIds, [SESSION_ID]);
    assertEquals(fake.completedIdempotencyKeys, [IDEMPOTENCY_KEY]);
});

Deno.test("uses the server effective key through the entire destructive sequence", async () => {
    const fake = dependencies({
        prepareAuthorizedResult: {
            status: "prepared",
            effectiveIdempotencyKey: IDEMPOTENCY_KEY,
        },
    });

    const response = await handleAccountDelete(
        deletionRequest(RETRY_IDEMPOTENCY_KEY),
        fake.dependencies,
    );

    assertEquals(response.status, 204);
    assertEquals(fake.regularPreparationKeys, [IDEMPOTENCY_KEY]);
    assertEquals(fake.completedIdempotencyKeys, [IDEMPOTENCY_KEY]);
});

Deno.test("stops after a failed global session revocation", async () => {
    const fake = dependencies({ revokeSessions: () => Promise.resolve(false) });

    const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

    await assertError(response, 503, "deletion_prepared_retryable");
    assertEquals(fake.calls, ["current-user", "prepare-authorized", "revoke"]);
});

Deno.test("rechecks ownership and Storage blockers after global revocation", async () => {
    const fake = dependencies({
        prepareResult: {
            status: "storage_conflict",
            effectiveIdempotencyKey: IDEMPOTENCY_KEY,
        },
    });

    const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

    await assertError(response, 409, "storage_objects_conflict");
    assertEquals(
        fake.calls,
        ["current-user", "prepare-authorized", "revoke", "prepare"],
    );
});

Deno.test("fails closed if preparation keys or RPC payloads are inconsistent", async () => {
    for (
        const overrides of [
            {
                prepareAuthorizedResult: {
                    status: "prepared" as const,
                    effectiveIdempotencyKey: "not-a-uuid",
                },
            },
            {
                prepareResult: {
                    status: "prepared" as const,
                    effectiveIdempotencyKey: RETRY_IDEMPOTENCY_KEY,
                },
            },
        ]
    ) {
        const fake = dependencies(overrides);
        const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

        const expectedCode = overrides.prepareAuthorizedResult === undefined
            ? "deletion_prepared_retryable"
            : "temporarily_unavailable";
        await assertError(response, 503, expectedCode);
        assertEquals(fake.calls.includes("delete"), false);
    }
});

Deno.test("does not mark completion when Auth deletion fails", async () => {
    const fake = dependencies({ deleteUser: () => Promise.resolve("failed") });

    const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

    await assertError(response, 503, "deletion_prepared_retryable");
    assertEquals(
        fake.calls,
        ["current-user", "prepare-authorized", "revoke", "prepare", "delete"],
    );
});

Deno.test("treats a concurrent Auth user_not_found deletion as idempotent success", async () => {
    const fake = dependencies({ deleteUser: () => Promise.resolve("not_found") });

    const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

    assertEquals(response.status, 204);
    assertEquals(
        fake.calls,
        ["current-user", "prepare-authorized", "revoke", "prepare", "delete", "complete"],
    );
});

Deno.test("returns a recoverable completion state when tombstoning fails", async () => {
    const fake = dependencies({ markCompleted: () => Promise.resolve(false) });

    const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

    await assertError(response, 503, "deletion_completion_pending");
    assertEquals(
        fake.calls,
        ["current-user", "prepare-authorized", "revoke", "prepare", "delete", "complete"],
    );
});

Deno.test("maps unexpected dependency failures to a non-disclosing retry response", async () => {
    const fake = dependencies({
        getCurrentUser: () => Promise.reject(new Error("sensitive provider detail")),
    });

    const response = await handleAccountDelete(deletionRequest(), fake.dependencies);

    await assertError(response, 503, "temporarily_unavailable");
    assertEquals(response.headers.get("cache-control"), "no-store");
    assertEquals(fake.calls, ["current-user"]);
});

interface DependencyOverrides {
    verifiedUserId?: string;
    jwtClaims?: unknown;
    nowEpochSeconds?: number;
    getCurrentUser?: AccountDeleteDependencies["getCurrentUser"];
    prepareAuthorizedResult?: AccountDeletionPreparation;
    prepareResult?: AccountDeletionPreparation;
    revokeSessions?: AccountDeleteDependencies["revokeSessions"];
    deleteUser?: AccountDeleteDependencies["deleteUser"];
    markCompleted?: AccountDeleteDependencies["markCompleted"];
}

function dependencies(overrides: DependencyOverrides = {}): {
    dependencies: AccountDeleteDependencies;
    calls: string[];
    authorizedSessionIds: string[];
    regularPreparationKeys: string[];
    completedIdempotencyKeys: string[];
} {
    const calls: string[] = [];
    const authorizedSessionIds: string[] = [];
    const regularPreparationKeys: string[] = [];
    const completedIdempotencyKeys: string[] = [];
    return {
        calls,
        authorizedSessionIds,
        regularPreparationKeys,
        completedIdempotencyKeys,
        dependencies: {
            verifiedUserId: overrides.verifiedUserId ?? USER_ID,
            jwtClaims: "jwtClaims" in overrides ? overrides.jwtClaims : freshClaims(),
            nowEpochSeconds: overrides.nowEpochSeconds ?? NOW,
            getCurrentUser: () => {
                calls.push("current-user");
                return overrides.getCurrentUser?.() ?? Promise.resolve({ id: USER_ID });
            },
            prepareDeletionWithSession: (_userId, sessionId, idempotencyKey) => {
                calls.push("prepare-authorized");
                authorizedSessionIds.push(sessionId);
                return Promise.resolve(
                    overrides.prepareAuthorizedResult ?? {
                        status: "prepared",
                        effectiveIdempotencyKey: idempotencyKey,
                    },
                );
            },
            prepareDeletion: (_userId, idempotencyKey) => {
                calls.push("prepare");
                regularPreparationKeys.push(idempotencyKey);
                return Promise.resolve(
                    overrides.prepareResult ?? {
                        status: "prepared",
                        effectiveIdempotencyKey: idempotencyKey,
                    },
                );
            },
            revokeSessions: () => {
                calls.push("revoke");
                return overrides.revokeSessions?.() ?? Promise.resolve(true);
            },
            deleteUser: (userId) => {
                calls.push("delete");
                return overrides.deleteUser?.(userId) ?? Promise.resolve("deleted");
            },
            markCompleted: (userId, idempotencyKey) => {
                calls.push("complete");
                completedIdempotencyKeys.push(idempotencyKey);
                return overrides.markCompleted?.(userId, idempotencyKey) ?? Promise.resolve(true);
            },
        },
    };
}

function freshClaims(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        sub: USER_ID,
        session_id: SESSION_ID,
        amr: [{ method: "password", timestamp: NOW }],
        ...overrides,
    };
}

function deletionRequest(idempotencyKey: string = IDEMPOTENCY_KEY): Request {
    return jsonRequest({ idempotency_key: idempotencyKey });
}

function jsonRequest(body: unknown): Request {
    return new Request("https://kwabor.test/account-delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
}

async function assertError(response: Response, status: number, errorCode: string): Promise<void> {
    assertEquals(response.status, status);
    assertEquals((await response.json()).error_code, errorCode);
}

function assertEquals(actual: unknown, expected: unknown): void {
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
        throw new Error(`Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
    }
}
