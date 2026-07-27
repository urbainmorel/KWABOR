import {
    type AccountDeleteDependencies,
    type AccountDeletionStatus,
    handleAccountDelete,
    type ReauthenticationCredential,
} from "./core.ts";

const USER_ID = "11111111-1111-4111-8111-111111111111";
const OTHER_USER_ID = "22222222-2222-4222-8222-222222222222";
const IDEMPOTENCY_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const RETRY_IDEMPOTENCY_KEY = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

Deno.test("rejects an unverified or mismatched bearer identity before mutation", async () => {
    for (const currentUserId of [null, OTHER_USER_ID]) {
        const fake = dependencies({
            getCurrentUser: () => Promise.resolve(currentUserId === null ? null : { id: currentUserId }),
        });

        const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

        assertEquals(response.status, 401);
        assertEquals(
            fake.calls,
            currentUserId === null ? ["status", "current-user", "status"] : ["status", "current-user"],
        );
    }
});

Deno.test("rejects malformed and over-permissive request bodies", async () => {
    const bodies = [
        {},
        {
            idempotency_key: IDEMPOTENCY_KEY,
            credential: { type: "password", password: "secret", unexpected: true },
        },
        {
            idempotency_key: "not-a-uuid",
            credential: { type: "password", password: "secret" },
        },
        {
            idempotency_key: IDEMPOTENCY_KEY,
            credential: {
                type: "social",
                provider: "google",
                id_token: "short",
                nonce: "nonce",
            },
        },
    ];

    for (const body of bodies) {
        const fake = dependencies();
        const response = await handleAccountDelete(jsonRequest(body), fake.dependencies);
        assertEquals(response.status, 400);
        assertEquals(fake.calls, []);
    }
});

Deno.test("returns explicit owner and storage conflicts without revoking sessions", async () => {
    for (
        const [status, errorCode] of [
            ["ownership_conflict", "organization_ownership_conflict"],
            ["storage_conflict", "storage_objects_conflict"],
        ] as const
    ) {
        const fake = dependencies({
            prepareResult: {
                status,
                effectiveIdempotencyKey: IDEMPOTENCY_KEY,
            },
        });
        const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

        assertEquals(response.status, 409);
        assertEquals((await response.json()).error_code, errorCode);
        assertEquals(fake.calls, ["status", "current-user", "reauthenticate", "prepare"]);
    }
});

Deno.test("requires a reauthenticated identity matching the bearer user", async () => {
    const fake = dependencies({
        reauthenticate: () => Promise.resolve({ id: OTHER_USER_ID }),
    });

    const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

    assertEquals(response.status, 401);
    assertEquals((await response.json()).error_code, "reauthentication_failed");
    assertEquals(fake.calls, ["status", "current-user", "reauthenticate"]);
});

Deno.test("prepares, revokes, deletes, and tombstones in strict order", async () => {
    const fake = dependencies();

    const response = await handleAccountDelete(socialRequest(), fake.dependencies);

    assertEquals(response.status, 204);
    assertEquals(
        fake.calls,
        [
            "status",
            "current-user",
            "reauthenticate",
            "prepare",
            "revoke",
            "prepare",
            "delete",
            "complete",
        ],
    );
    assertEquals(fake.lastCredential?.type, "social");
});

Deno.test("does not delete the user when global session revocation fails", async () => {
    const fake = dependencies({ revokeSessions: () => Promise.resolve(false) });

    const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

    assertEquals(response.status, 503);
    assertEquals(fake.calls, ["status", "current-user", "reauthenticate", "prepare", "revoke"]);
    assertEquals((await response.json()).error_code, "deletion_prepared_retryable");
});

Deno.test("does not mark completion when Auth user deletion fails", async () => {
    const fake = dependencies({ deleteUser: () => Promise.resolve("failed") });

    const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

    assertEquals(response.status, 503);
    assertEquals(
        fake.calls,
        ["status", "current-user", "reauthenticate", "prepare", "revoke", "prepare", "delete"],
    );
    assertEquals((await response.json()).error_code, "deletion_prepared_retryable");
});

Deno.test("completed requests with an absent Auth user are idempotent", async () => {
    const fake = dependencies({
        existingState: {
            status: "completed",
            effectiveIdempotencyKey: IDEMPOTENCY_KEY,
        },
        getCurrentUser: () => Promise.resolve(null),
    });

    const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

    assertEquals(response.status, 204);
    assertEquals(fake.calls, ["status", "current-user"]);
});

Deno.test("repairs the crash window after Auth deletion but before completion", async () => {
    const fake = dependencies({
        existingState: {
            status: "prepared",
            effectiveIdempotencyKey: IDEMPOTENCY_KEY,
        },
        getCurrentUser: () => Promise.resolve(null),
    });

    const response = await handleAccountDelete(
        passwordRequest(RETRY_IDEMPOTENCY_KEY),
        fake.dependencies,
    );

    assertEquals(response.status, 204);
    assertEquals(fake.calls, ["status", "current-user", "complete"]);
    assertEquals(fake.completedIdempotencyKeys, [IDEMPOTENCY_KEY]);
});

Deno.test("returns a machine-recoverable state when crash repair cannot tombstone", async () => {
    const fake = dependencies({
        existingState: {
            status: "prepared",
            effectiveIdempotencyKey: IDEMPOTENCY_KEY,
        },
        getCurrentUser: () => Promise.resolve(null),
        markCompleted: () => Promise.resolve(false),
    });

    const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

    assertEquals(response.status, 503);
    assertEquals((await response.json()).error_code, "deletion_completion_pending");
    assertEquals(fake.calls, ["status", "current-user", "complete"]);
});

Deno.test("treats a concurrent user_not_found deletion as success", async () => {
    const fake = dependencies({ deleteUser: () => Promise.resolve("not_found") });

    const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

    assertEquals(response.status, 204);
    assertEquals(
        fake.calls,
        [
            "status",
            "current-user",
            "reauthenticate",
            "prepare",
            "revoke",
            "prepare",
            "delete",
            "complete",
        ],
    );
});

Deno.test("a restarted client completes the effective prepared key returned by the server", async () => {
    const fake = dependencies({
        prepareResult: {
            status: "prepared",
            effectiveIdempotencyKey: IDEMPOTENCY_KEY,
        },
    });

    const response = await handleAccountDelete(
        passwordRequest(RETRY_IDEMPOTENCY_KEY),
        fake.dependencies,
    );

    assertEquals(response.status, 204);
    assertEquals(fake.completedIdempotencyKeys, [IDEMPOTENCY_KEY]);
    assertEquals(
        fake.calls,
        [
            "status",
            "current-user",
            "reauthenticate",
            "prepare",
            "revoke",
            "prepare",
            "delete",
            "complete",
        ],
    );
});

Deno.test("rechecks Storage and ownership blockers after revoking sessions", async () => {
    const fake = dependencies({
        prepareResults: [
            {
                status: "prepared",
                effectiveIdempotencyKey: IDEMPOTENCY_KEY,
            },
            {
                status: "storage_conflict",
                effectiveIdempotencyKey: IDEMPOTENCY_KEY,
            },
        ],
    });

    const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

    assertEquals(response.status, 409);
    assertEquals((await response.json()).error_code, "storage_objects_conflict");
    assertEquals(
        fake.calls,
        ["status", "current-user", "reauthenticate", "prepare", "revoke", "prepare"],
    );
});

Deno.test("fails closed if final preparation changes the effective operation key", async () => {
    const fake = dependencies({
        prepareResults: [
            {
                status: "prepared",
                effectiveIdempotencyKey: IDEMPOTENCY_KEY,
            },
            {
                status: "prepared",
                effectiveIdempotencyKey: RETRY_IDEMPOTENCY_KEY,
            },
        ],
    });

    const response = await handleAccountDelete(passwordRequest(), fake.dependencies);

    assertEquals(response.status, 503);
    assertEquals((await response.json()).error_code, "deletion_prepared_retryable");
    assertEquals(
        fake.calls,
        ["status", "current-user", "reauthenticate", "prepare", "revoke", "prepare"],
    );
});

Deno.test("rejects non-POST requests", async () => {
    const fake = dependencies();
    const response = await handleAccountDelete(
        new Request("https://kwabor.test/account-delete", { method: "GET" }),
        fake.dependencies,
    );

    assertEquals(response.status, 405);
    assertEquals(response.headers.get("allow"), "POST");
    assertEquals(fake.calls, []);
});

interface DependencyOverrides {
    existingState?: {
        status: Exclude<AccountDeletionStatus, null>;
        effectiveIdempotencyKey: string;
    } | null;
    prepareResult?: {
        status: Exclude<AccountDeletionStatus, null>;
        effectiveIdempotencyKey: string;
    };
    prepareResults?: Array<{
        status: Exclude<AccountDeletionStatus, null>;
        effectiveIdempotencyKey: string;
    }>;
    getCurrentUser?: AccountDeleteDependencies["getCurrentUser"];
    reauthenticate?: AccountDeleteDependencies["reauthenticate"];
    revokeSessions?: AccountDeleteDependencies["revokeSessions"];
    deleteUser?: AccountDeleteDependencies["deleteUser"];
    markCompleted?: AccountDeleteDependencies["markCompleted"];
}

function dependencies(overrides: DependencyOverrides = {}): {
    dependencies: AccountDeleteDependencies;
    calls: string[];
    readonly lastCredential: ReauthenticationCredential | null;
    completedIdempotencyKeys: string[];
} {
    const state = {
        calls: [] as string[],
        lastCredential: null as ReauthenticationCredential | null,
        completedIdempotencyKeys: [] as string[],
        prepareResults: [...(overrides.prepareResults ?? [])],
    };
    return {
        calls: state.calls,
        get lastCredential() {
            return state.lastCredential;
        },
        completedIdempotencyKeys: state.completedIdempotencyKeys,
        dependencies: {
            verifiedUserId: USER_ID,
            getDeletionState: () => {
                state.calls.push("status");
                return Promise.resolve(overrides.existingState ?? null);
            },
            getCurrentUser: () => {
                state.calls.push("current-user");
                return overrides.getCurrentUser === undefined
                    ? Promise.resolve({ id: USER_ID })
                    : overrides.getCurrentUser();
            },
            reauthenticate: (credential) => {
                state.calls.push("reauthenticate");
                state.lastCredential = credential;
                return overrides.reauthenticate === undefined
                    ? Promise.resolve({ id: USER_ID })
                    : overrides.reauthenticate(credential);
            },
            prepareDeletion: (_userId, idempotencyKey) => {
                state.calls.push("prepare");
                return Promise.resolve(
                    state.prepareResults.shift() ?? overrides.prepareResult ?? {
                        status: "prepared",
                        effectiveIdempotencyKey: idempotencyKey,
                    },
                );
            },
            revokeSessions: () => {
                state.calls.push("revoke");
                return overrides.revokeSessions?.() ?? Promise.resolve(true);
            },
            deleteUser: (userId) => {
                state.calls.push("delete");
                return overrides.deleteUser?.(userId) ?? Promise.resolve("deleted");
            },
            markCompleted: (userId, idempotencyKey) => {
                state.calls.push("complete");
                state.completedIdempotencyKeys.push(idempotencyKey);
                return overrides.markCompleted?.(userId, idempotencyKey) ?? Promise.resolve(true);
            },
        },
    };
}

function passwordRequest(idempotencyKey: string = IDEMPOTENCY_KEY): Request {
    return jsonRequest({
        idempotency_key: idempotencyKey,
        credential: {
            type: "password",
            password: "Test-password-123",
        },
    });
}

function socialRequest(): Request {
    return jsonRequest({
        idempotency_key: IDEMPOTENCY_KEY,
        credential: {
            type: "social",
            provider: "google",
            id_token: "header.payload.signature",
            nonce: "abcdefghijklmnopqrstuvwxyzABCDEF",
        },
    });
}

function jsonRequest(body: unknown): Request {
    return new Request("https://kwabor.test/account-delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
}

function assertEquals(actual: unknown, expected: unknown): void {
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
        throw new Error(`Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
    }
}
