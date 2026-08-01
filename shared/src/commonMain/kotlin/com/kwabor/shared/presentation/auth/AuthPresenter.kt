package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_STORAGE_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_EMAIL_NOT_CONFIRMED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_INVALID_CREDENTIALS_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_OTP_EXPIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PASSWORD_SAME_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PASSWORD_TOO_WEAK_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PROMOTER_INVITE_EXPIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PROMOTER_INVITE_USED_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_RATE_LIMITED_ERROR_KEY
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.PromoterActivationResult
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
        request: SocialSignInRequest,
        strings: KwaborStrings,
    ): AuthUiState {
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (
            val result = authRepository.signInWithSocialProvider(request)
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

    suspend fun handlePromoterActivationCallback(
        callbackUrl: String,
        strings: KwaborStrings,
    ): AuthActionResult<PromoterActivationContext> = authRepository
        .handlePromoterActivationCallback(callbackUrl)
        .toAuthActionResult(strings)

    suspend fun activatePromoterInvite(
        request: PromoterActivationRequest,
        strings: KwaborStrings,
    ): AuthActionResult<PromoterActivationResult> = authRepository
        .activatePromoterInvite(request)
        .toAuthActionResult(strings)

    suspend fun deleteAccount(request: AccountDeletionRequest, strings: KwaborStrings): AuthActionResult<Unit> =
        authRepository.deleteAccount(request).toAuthActionResult(strings)
}

data class AuthActionResult<out T>(
    val value: T? = null,
    val errorMessage: String? = null,
) {
    val isSuccess: Boolean get() = value != null
}

private fun <T> DomainResult<T>.toAuthActionResult(strings: KwaborStrings): AuthActionResult<T> = when (this) {
    is DomainResult.Success -> AuthActionResult(value = value)
    is DomainResult.Failure -> AuthActionResult(errorMessage = error.toAuthMessage(strings))
}

internal fun DomainError.toAuthMessage(strings: KwaborStrings): String = when (this) {
    is DomainError.AuthenticationRequired -> strings.authSessionExpired
    is DomainError.NetworkUnavailable -> strings.offlineBanner
    is DomainError.LocalStorageUnavailable -> strings.authInvalidInput
    is DomainError.PermissionDenied -> strings.authPermissionDenied
    is DomainError.NotFound -> strings.registrationLegalUnavailable
    is DomainError.Validation -> messageKey.toAuthValidationMessage(strings)
    is DomainError.Unexpected -> strings.authInvalidInput
}

private fun String.toAuthValidationMessage(strings: KwaborStrings): String = toCredentialValidationMessage(strings)
    ?: toPromoterValidationMessage(strings)
    ?: toAccountDeletionValidationMessage(strings)
    ?: strings.authInvalidInput

private fun String.toCredentialValidationMessage(strings: KwaborStrings): String? = when (this) {
    AUTH_OTP_EXPIRED_ERROR_KEY -> strings.registrationOtpExpired
    AUTH_INVALID_CREDENTIALS_ERROR_KEY -> strings.authInvalidCredentials
    AUTH_EMAIL_NOT_CONFIRMED_ERROR_KEY -> strings.authEmailNotConfirmed
    AUTH_RATE_LIMITED_ERROR_KEY -> strings.authRateLimited
    AUTH_PASSWORD_TOO_WEAK_ERROR_KEY -> strings.authPasswordTooWeak
    AUTH_PASSWORD_SAME_ERROR_KEY -> strings.authPasswordSame
    else -> null
}

private fun String.toPromoterValidationMessage(strings: KwaborStrings): String? = when (this) {
    AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY -> strings.authPromoterInviteInvalid
    AUTH_PROMOTER_INVITE_EXPIRED_ERROR_KEY -> strings.authPromoterInviteExpired
    AUTH_PROMOTER_INVITE_USED_ERROR_KEY -> strings.authPromoterInviteUsed
    else -> null
}

private fun String.toAccountDeletionValidationMessage(strings: KwaborStrings): String? = when (this) {
    AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY -> strings.authReauthenticationFailed
    AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY -> strings.authAccountDeletionOwnershipBlocked
    AUTH_ACCOUNT_DELETION_STORAGE_ERROR_KEY -> strings.authAccountDeletionStorageBlocked
    else -> null
}
