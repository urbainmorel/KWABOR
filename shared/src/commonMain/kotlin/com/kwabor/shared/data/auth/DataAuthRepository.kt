package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.EmailSignUpRequest
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult

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
} catch (exception: IllegalArgumentException) {
    DomainResult.Failure(DomainError.Validation(exception.message ?: "error.auth.invalid_request"))
} catch (exception: IllegalStateException) {
    DomainResult.Failure(DomainError.Unexpected())
} catch (exception: Exception) {
    DomainResult.Failure(DomainError.Unexpected())
}

private fun AuthSessionDto.toDomain(): AuthSession = AuthSession(
    userId = userId,
    email = email,
    expiresAtEpochMilliseconds = expiresAtEpochMilliseconds,
)

private fun requireValidEmail(email: String) {
    require(email.isNotBlank() && "@" in email) { "error.auth.email_invalid" }
}

private fun requirePassword(password: String) {
    require(password.length >= 8) { "error.auth.password_too_short" }
}

private fun requireOtpCode(otpCode: String) {
    require(otpCode.trim().length >= 6) { "error.auth.otp_invalid" }
}
