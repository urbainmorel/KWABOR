export function isUserNotFound(error: unknown): boolean {
    if (!isRecord(error)) return false;
    return error.code === "user_not_found";
}

export function isSessionAlreadyRevoked(error: unknown): boolean {
    if (!isRecord(error)) return false;
    return error.code === "session_not_found" ||
        error.code === "refresh_token_not_found";
}

export function isLiveSessionRequired(error: unknown): boolean {
    if (!isRecord(error)) return false;
    return error.code === "42501" && error.message === "Live authentication session required";
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}
