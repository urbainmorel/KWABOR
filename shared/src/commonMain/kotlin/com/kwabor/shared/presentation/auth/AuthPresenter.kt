package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AUTH_EMAIL_NOT_CONFIRMED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_INVALID_CREDENTIALS_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_OTP_EXPIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PASSWORD_SAME_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PASSWORD_TOO_WEAK_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_RATE_LIMITED_ERROR_KEY
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.i18n.KwaborStrings

class AuthPresenter(
    private val authRepository: AuthRepository,
) {
    suspend fun loadCurrentSession(state: AuthUiState, strings: KwaborStrings): AuthUiState =
        when (val result = authRepository.getCurrentSession()) {
            is DomainResult.Success -> state.copy(
                currentSession = result.value,
                noticeMessage = null,
                errorMessage = null,
            )
            is DomainResult.Failure -> state.copy(errorMessage = result.error.toAuthMessage(strings))
        }

    suspend fun signInWithEmail(
        state: AuthUiState,
        email: String,
        password: String,
        strings: KwaborStrings,
    ): AuthUiState {
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (val result = authRepository.signInWithEmail(email = email, password = password)) {
            is DomainResult.Success -> loadingState.copy(
                isLoading = false,
                currentSession = result.value,
                noticeMessage = strings.authSessionReady,
            )
            is DomainResult.Failure -> loadingState.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
    }

    suspend fun signInWithSocialIdToken(
        state: AuthUiState,
        provider: SocialAuthProvider,
        idToken: String,
        strings: KwaborStrings,
    ): AuthUiState {
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (
            val result = authRepository.signInWithSocialProvider(
                SocialSignInRequest(provider = provider, idToken = idToken),
            )
        ) {
            is DomainResult.Success -> loadingState.copy(
                isLoading = false,
                currentSession = result.value,
                noticeMessage = strings.authSessionReady,
            )
            is DomainResult.Failure -> loadingState.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
    }

    suspend fun signOut(state: AuthUiState, strings: KwaborStrings): AuthUiState =
        when (val result = authRepository.signOut()) {
            is DomainResult.Success -> initialAuthUiState().copy(noticeMessage = strings.authSignedOut)
            is DomainResult.Failure -> state.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
}

internal fun DomainError.toAuthMessage(strings: KwaborStrings): String = when (this) {
    is DomainError.AuthenticationRequired -> strings.authSessionExpired
    is DomainError.NetworkUnavailable -> strings.offlineBanner
    is DomainError.PermissionDenied -> strings.authPermissionDenied
    is DomainError.NotFound -> strings.registrationLegalUnavailable
    is DomainError.Validation -> messageKey.toAuthValidationMessage(strings)
    is DomainError.Unexpected -> strings.authInvalidInput
}

private fun String.toAuthValidationMessage(strings: KwaborStrings): String = when (this) {
    AUTH_OTP_EXPIRED_ERROR_KEY -> strings.registrationOtpExpired
    AUTH_INVALID_CREDENTIALS_ERROR_KEY -> strings.authInvalidCredentials
    AUTH_EMAIL_NOT_CONFIRMED_ERROR_KEY -> strings.authEmailNotConfirmed
    AUTH_RATE_LIMITED_ERROR_KEY -> strings.authRateLimited
    AUTH_PASSWORD_TOO_WEAK_ERROR_KEY -> strings.authPasswordTooWeak
    AUTH_PASSWORD_SAME_ERROR_KEY -> strings.authPasswordSame
    else -> strings.authInvalidInput
}
