import { hasLinkedIdentity, isSessionAlreadyRevoked, isUserNotFound, readProviderSubject } from "./identity.ts";

const LINKED_SUBJECT = "google-subject-123";

Deno.test("social reauthentication accepts only an already linked provider and subject", () => {
    const user = {
        identities: [
            {
                provider: "google",
                identity_id: LINKED_SUBJECT,
            },
        ],
    };

    assertEquals(hasLinkedIdentity(user, "google", LINKED_SUBJECT), true);
    assertEquals(hasLinkedIdentity(user, "apple", LINKED_SUBJECT), false);
    assertEquals(hasLinkedIdentity(user, "google", "different-subject"), false);
});

Deno.test("provider subject extraction is strict and never trusts malformed payloads", () => {
    assertEquals(readProviderSubject(jwtWithSubject(LINKED_SUBJECT)), LINKED_SUBJECT);
    assertEquals(readProviderSubject("not-a-jwt"), null);
    assertEquals(readProviderSubject(jwtWithPayload({ sub: "" })), null);
    assertEquals(readProviderSubject(jwtWithPayload({ subject: LINKED_SUBJECT })), null);
});

Deno.test("Auth user_not_found responses are safe idempotent deletion outcomes", () => {
    assertEquals(isUserNotFound({ code: "user_not_found", status: 400 }), true);
    assertEquals(isUserNotFound({ code: "unexpected", status: 404 }), false);
    assertEquals(isUserNotFound({ code: "unexpected", status: 500 }), false);
});

Deno.test("already revoked global sessions keep crash retries idempotent", () => {
    assertEquals(isSessionAlreadyRevoked({ code: "session_not_found" }), true);
    assertEquals(isSessionAlreadyRevoked({ code: "refresh_token_not_found" }), true);
    assertEquals(isSessionAlreadyRevoked({ status: 404 }), false);
    assertEquals(isSessionAlreadyRevoked({ status: 500 }), false);
});

function jwtWithSubject(subject: string): string {
    return jwtWithPayload({ sub: subject });
}

function jwtWithPayload(payload: Record<string, unknown>): string {
    return `header.${base64Url(JSON.stringify(payload))}.signature`;
}

function base64Url(value: string): string {
    const bytes = new TextEncoder().encode(value);
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary)
        .replaceAll("+", "-")
        .replaceAll("/", "_")
        .replaceAll("=", "");
}

function assertEquals(actual: unknown, expected: unknown): void {
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
        throw new Error(`Expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
    }
}
