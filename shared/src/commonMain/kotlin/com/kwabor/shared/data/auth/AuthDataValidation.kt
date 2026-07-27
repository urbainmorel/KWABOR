package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_SOCIAL_NONCE_REQUIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.MAX_ONBOARDING_NAME_LENGTH
import com.kwabor.shared.domain.auth.SocialSignInRequest

private const val MINIMUM_PASSWORD_LENGTH = 8
private val OTP_PATTERN = Regex(pattern = "^[0-9]{6}$")
private val ID_TOKEN_PATTERN = Regex("^[A-Za-z0-9._-]{20,16384}$")
private val RAW_NONCE_PATTERN = Regex("^[A-Za-z0-9_-]{32,128}$")

internal fun requireValidEmail(email: String) {
    if (email.isBlank() || "@" !in email) {
        throw AuthDataException.Validation("error.auth.email_invalid")
    }
}

internal fun requirePassword(password: String) {
    if (password.length < MINIMUM_PASSWORD_LENGTH) {
        throw AuthDataException.Validation("error.auth.password_too_short")
    }
}

internal fun requireSignInPassword(password: String) {
    if (password.isEmpty()) {
        throw AuthDataException.Validation("error.auth.password_required")
    }
}

internal fun requireOtpCode(otpCode: String) {
    if (!OTP_PATTERN.matches(otpCode.trim())) {
        throw AuthDataException.Validation("error.auth.otp_invalid")
    }
}

internal fun requireSocialRequest(request: SocialSignInRequest) {
    val validationErrorKey = when {
        !ID_TOKEN_PATTERN.matches(request.idToken) -> "error.auth.id_token_required"
        !RAW_NONCE_PATTERN.matches(request.rawNonce) -> AUTH_SOCIAL_NONCE_REQUIRED_ERROR_KEY
        request.hasInvalidNameHint() -> "error.auth.name_too_long"
        else -> null
    }
    if (validationErrorKey != null) {
        throw AuthDataException.Validation(validationErrorKey)
    }
}

internal fun SocialSignInRequest.normalized(): SocialSignInRequest = SocialSignInRequest(
    provider = provider,
    idToken = idToken.trim(),
    rawNonce = rawNonce.trim(),
    suggestedFirstName = suggestedFirstName.normalizedNameHint(),
    suggestedLastName = suggestedLastName.normalizedNameHint(),
)

private fun SocialSignInRequest.hasInvalidNameHint(): Boolean = listOf(suggestedFirstName, suggestedLastName)
    .filterNotNull()
    .any { hint -> hint.length > MAX_ONBOARDING_NAME_LENGTH || hint.any(Char::isISOControl) }

private fun String?.normalizedNameHint(): String? = this?.trim()?.takeIf(String::isNotEmpty)
