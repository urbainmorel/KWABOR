package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.EmailSignUpRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError

internal interface AuthDataSource {
    suspend fun getCurrentSession(): AuthSessionDto?

    suspend fun requestEmailOtp(email: String)

    suspend fun verifyEmailOtp(email: String, otpCode: String)

    suspend fun signUpWithEmail(request: EmailSignUpRequest): AuthSessionDto

    suspend fun signInWithEmail(email: String, password: String): AuthSessionDto

    suspend fun signInWithSocialProvider(request: SocialSignInRequest): AuthSessionDto

    suspend fun signOut()
}

internal data class AuthSessionDto(
    val userId: String,
    val email: String?,
    val expiresAtEpochMilliseconds: Long,
)

internal sealed class AuthDataException(
    val domainError: DomainError,
) : RuntimeException(domainError.messageKey) {
    class AuthenticationRequired(
        messageKey: String = "error.auth.session_required",
    ) : AuthDataException(DomainError.AuthenticationRequired(messageKey))

    class PermissionDenied(
        messageKey: String = "error.auth.permission_denied",
    ) : AuthDataException(DomainError.PermissionDenied(messageKey))

    class Validation(
        messageKey: String = "error.auth.invalid_request",
    ) : AuthDataException(DomainError.Validation(messageKey))

    class NetworkUnavailable : AuthDataException(DomainError.NetworkUnavailable())

    class Unexpected : AuthDataException(DomainError.Unexpected())
}
