package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.EmailOtpProfileRequest
import com.kwabor.shared.domain.auth.EmailSignUpRequest
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult

private const val MINIMUM_PASSWORD_LENGTH = 8
private const val MINIMUM_OTP_LENGTH = 6

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

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<Unit> = runAuthCall {
        requireValidEmail(email)
        requireOtpCode(otpCode)
        dataSource.verifyEmailOtp(email = email.trim(), otpCode = otpCode.trim())
    }

    override suspend fun verifyEmailOtpWithProfile(request: EmailOtpProfileRequest): DomainResult<AuthSession> =
        runAuthCall {
            requireValidEmail(request.email)
            requireOtpCode(request.otpCode)
            dataSource.verifyEmailOtpWithProfile(
                request.copy(
                    email = request.email.trim(),
                    otpCode = request.otpCode.trim(),
                ),
            ).toDomain()
        }

    override suspend fun signUpWithEmail(request: EmailSignUpRequest): DomainResult<AuthSession> = runAuthCall {
        requireValidEmail(request.email)
        requirePassword(request.password)
        requireOtpCode(request.otpCode)
        dataSource.signUpWithEmail(request.copy(email = request.email.trim(), otpCode = request.otpCode.trim()))
            .toDomain()
    }

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> = runAuthCall {
        requireValidEmail(email)
        requirePassword(password)
        dataSource.signInWithEmail(email = email.trim(), password = password).toDomain()
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> =
        runAuthCall {
            if (request.idToken.isBlank()) {
                throw AuthDataException.Validation("error.auth.id_token_required")
            }
            dataSource.signInWithSocialProvider(request.copy(idToken = request.idToken.trim())).toDomain()
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

private fun requireOtpCode(otpCode: String) {
    if (otpCode.trim().length < MINIMUM_OTP_LENGTH) {
        throw AuthDataException.Validation("error.auth.otp_invalid")
    }
}
