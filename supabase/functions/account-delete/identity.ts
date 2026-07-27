export interface UserWithLinkedIdentities {
    identities?: Array<{
        provider: string;
        identity_id: string;
    }>;
}

export function readProviderSubject(idToken: string): string | null {
    const payload = idToken.split(".")[1];
    if (payload === undefined) return null;

    try {
        const normalized = payload.replaceAll("-", "+").replaceAll("_", "/");
        const padding = "=".repeat((4 - normalized.length % 4) % 4);
        const binary = atob(normalized + padding);
        const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
        const decoded: unknown = JSON.parse(new TextDecoder().decode(bytes));
        if (!isRecord(decoded) || typeof decoded.sub !== "string") return null;
        const subject = decoded.sub.trim();
        return subject.length > 0 && subject.length <= 255 ? subject : null;
    } catch {
        return null;
    }
}

export function hasLinkedIdentity(
    user: UserWithLinkedIdentities,
    provider: "google" | "apple",
    subject: string,
): boolean {
    return user.identities?.some((identity) => identity.provider === provider && identity.identity_id === subject) ===
        true;
}

export function isUserNotFound(error: unknown): boolean {
    if (!isRecord(error)) return false;
    return error.code === "user_not_found";
}

export function isSessionAlreadyRevoked(error: unknown): boolean {
    if (!isRecord(error)) return false;
    return error.code === "session_not_found" ||
        error.code === "refresh_token_not_found";
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}
