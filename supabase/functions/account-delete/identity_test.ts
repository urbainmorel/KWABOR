import { isLiveSessionRequired, isSessionAlreadyRevoked, isUserNotFound } from "./identity.ts";

Deno.test("Auth user_not_found responses are safe idempotent deletion outcomes", () => {
    assertEquals(isUserNotFound({ code: "user_not_found", status: 400 }), true);
    assertEquals(isUserNotFound({ code: "unexpected", status: 404 }), false);
    assertEquals(isUserNotFound({ code: "unexpected", status: 500 }), false);
});

Deno.test("concurrent global session revocation remains idempotent", () => {
    assertEquals(isSessionAlreadyRevoked({ code: "session_not_found" }), true);
    assertEquals(isSessionAlreadyRevoked({ code: "refresh_token_not_found" }), true);
    assertEquals(isSessionAlreadyRevoked({ status: 404 }), false);
    assertEquals(isSessionAlreadyRevoked({ status: 500 }), false);
});

Deno.test("only the exact live-session authorization error is recoverable", () => {
    assertEquals(
        isLiveSessionRequired({
            code: "42501",
            message: "Live authentication session required",
        }),
        true,
    );
    assertEquals(
        isLiveSessionRequired({
            code: "42501",
            message: "Different authorization failure",
        }),
        false,
    );
    assertEquals(
        isLiveSessionRequired({
            code: "22023",
            message: "Live authentication session required",
        }),
        false,
    );
});

function assertEquals(actual: unknown, expected: unknown): void {
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
        throw new Error(`Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
    }
}
