package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.i18n.AppLocale

internal interface AuthDataSource {
    suspend fun getCurrentSession(): AuthSessionDto?

    suspend fun requestEmailOtp(email: String)

    suspend fun verifyEmailOtp(email: String, otpCode: String): AuthSessionDto

    suspend fun setInitialPassword(password: String)

    suspend fun listActiveLegalDocuments(locale: AppLocale): List<LegalDocumentRevision>

    suspend fun completeOnboarding(request: CompleteOnboardingRequest): AuthSessionDto

    suspend fun signInWithEmail(email: String, password: String): AuthSessionDto

    suspend fun signInWithSocialProvider(request: SocialSignInRequest): AuthSessionDto

    suspend fun signOut()
}

internal data class AuthSessionDto(
    val userId: String,
    val email: String?,
    val expiresAtEpochMilliseconds: Long,
    val onboardingCompleted: Boolean,
)

internal sealed class AuthDataException(
    val domainError: DomainError,
    cause: Throwable? = null,
) : RuntimeException(domainError.messageKey, cause) {
    class AuthenticationRequired(
        messageKey: String = "error.auth.session_required",
        cause: Throwable? = null,
    ) : AuthDataException(DomainError.AuthenticationRequired(messageKey), cause)

    class PermissionDenied(
        messageKey: String = "error.auth.permission_denied",
        cause: Throwable? = null,
    ) : AuthDataException(DomainError.PermissionDenied(messageKey), cause)

    class Validation(
        messageKey: String = "error.auth.invalid_request",
        cause: Throwable? = null,
    ) : AuthDataException(DomainError.Validation(messageKey), cause)

    class LegalDocumentsUnavailable(cause: Throwable? = null) :
        AuthDataException(DomainError.NotFound("error.auth.legal_documents_unavailable"), cause)

    class NetworkUnavailable(cause: Throwable? = null) : AuthDataException(DomainError.NetworkUnavailable(), cause)

    class Unexpected(cause: Throwable? = null) : AuthDataException(DomainError.Unexpected(), cause)
}
