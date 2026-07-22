package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale

private const val MINIMUM_PASSWORD_LENGTH = 8
private val OTP_PATTERN = Regex(pattern = "^[0-9]{6}$")

class DataAuthRepository internal constructor(
    private val dataSource: AuthDataSource,
) : AuthRepository {
    override suspend fun getCurrentSession(): DomainResult<AuthSession?> = runAuthCall {
        dataSource.getCurrentSession()?.toDomain()
    }

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> = runAuthCall {
        requireValidEmail(email)
        dataSource.requestEmailOtp(email.trim())
    }

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession> = runAuthCall {
        requireValidEmail(email)
        requireOtpCode(otpCode)
        dataSource.verifyEmailOtp(email = email.trim(), otpCode = otpCode.trim()).toDomain()
    }

    override suspend fun setInitialPassword(password: String): DomainResult<Unit> = runAuthCall {
        requirePassword(password)
        dataSource.setInitialPassword(password)
    }

    override suspend fun listActiveLegalDocuments(locale: AppLocale): DomainResult<List<LegalDocumentRevision>> =
        runAuthCall {
            val revisions = dataSource.listActiveLegalDocuments(locale)
            if (revisions.isEmpty()) {
                throw AuthDataException.LegalDocumentsUnavailable()
            }
            revisions
        }

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): DomainResult<AuthSession> =
        runAuthCall {
            dataSource.completeOnboarding(request).toDomain()
        }

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> = runAuthCall {
        requireValidEmail(email)
        requireSignInPassword(password)
        dataSource.signInWithEmail(email = email.trim(), password = password).toDomain()
    }

    override suspend fun requestPasswordRecovery(email: String): DomainResult<Unit> = runAuthCall {
        requireValidEmail(email)
        dataSource.requestPasswordRecovery(email.trim())
    }

    override suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): DomainResult<AuthSession> =
        runAuthCall {
            requireValidEmail(email)
            requireOtpCode(otpCode)
            dataSource.verifyPasswordRecoveryOtp(
                email = email.trim(),
                otpCode = otpCode.trim(),
            ).toDomain()
        }

    override suspend fun completePasswordRecovery(newPassword: String): DomainResult<Unit> = runAuthCall {
        requirePassword(newPassword)
        dataSource.completePasswordRecovery(newPassword)
    }

    override suspend fun cancelPasswordRecovery(): DomainResult<Unit> = runAuthCall {
        dataSource.cancelPasswordRecovery()
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> =
        runAuthCall {
            if (request.idToken.isBlank()) {
                throw AuthDataException.Validation("error.auth.id_token_required")
            }
            dataSource.signInWithSocialProvider(
                SocialSignInRequest(
                    provider = request.provider,
                    idToken = request.idToken.trim(),
                ),
            ).toDomain()
        }

    override suspend fun activatePromoterInvite(request: PromoterActivationRequest): DomainResult<AuthSession> =
        DomainResult.Failure(DomainError.Validation("error.auth.promoter_activation_requires_server"))

    override suspend fun signOut(): DomainResult<Unit> = runAuthCall {
        dataSource.signOut()
    }
}

private inline fun <T> runAuthCall(block: () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: AuthDataException) {
    DomainResult.Failure(exception.domainError)
}

private fun AuthSessionDto.toDomain(): AuthSession = AuthSession(
    userId = userId,
    email = email,
    expiresAtEpochMilliseconds = expiresAtEpochMilliseconds,
    accountSetupStatus = if (onboardingCompleted) {
        AccountSetupStatus.Complete
    } else {
        AccountSetupStatus.OnboardingRequired
    },
    purpose = purpose,
)

private fun requireValidEmail(email: String) {
    if (email.isBlank() || "@" !in email) {
        throw AuthDataException.Validation("error.auth.email_invalid")
    }
}

private fun requirePassword(password: String) {
    if (password.length < MINIMUM_PASSWORD_LENGTH) {
        throw AuthDataException.Validation("error.auth.password_too_short")
    }
}

private fun requireSignInPassword(password: String) {
    if (password.isEmpty()) {
        throw AuthDataException.Validation("error.auth.password_required")
    }
}

private fun requireOtpCode(otpCode: String) {
    if (!OTP_PATTERN.matches(otpCode.trim())) {
        throw AuthDataException.Validation("error.auth.otp_invalid")
    }
}
