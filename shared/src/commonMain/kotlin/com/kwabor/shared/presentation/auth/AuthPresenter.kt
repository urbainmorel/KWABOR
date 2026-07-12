package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.EmailOtpProfileRequest
import com.kwabor.shared.domain.auth.OnboardingProfileInput
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
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

    fun updateEmail(state: AuthUiState, email: String): AuthUiState =
        state.copy(email = email, errorMessage = null, noticeMessage = null)

    fun updateOtpCode(state: AuthUiState, otpCode: String): AuthUiState =
        state.copy(otpCode = otpCode, errorMessage = null, noticeMessage = null)

    fun updateFirstName(state: AuthUiState, firstName: String): AuthUiState =
        state.copy(firstName = firstName, errorMessage = null, noticeMessage = null)

    fun updateLastName(state: AuthUiState, lastName: String): AuthUiState =
        state.copy(lastName = lastName, errorMessage = null, noticeMessage = null)

    fun updateLegalAccepted(state: AuthUiState, accepted: Boolean): AuthUiState =
        state.copy(legalAccepted = accepted, errorMessage = null, noticeMessage = null)

    suspend fun requestEmailOtp(state: AuthUiState, strings: KwaborStrings): AuthUiState {
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (val result = authRepository.requestEmailOtp(state.email)) {
            is DomainResult.Success -> loadingState.copy(
                isLoading = false,
                step = AuthStep.Otp,
                noticeMessage = strings.authOtpSent,
            )
            is DomainResult.Failure -> loadingState.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
    }

    suspend fun verifyEmailOtpWithProfile(state: AuthUiState, strings: KwaborStrings): AuthUiState {
        val onboarding = when (
            val result = OnboardingProfileInput.create(
                firstName = state.firstName.trim(),
                lastName = state.lastName.trim(),
                cityId = null,
                preferredLocale = AppLocale.French,
                preferredCurrency = KwaborCurrency.Xof,
                termsAccepted = state.legalAccepted,
                privacyPolicyAccepted = state.legalAccepted,
                ugcLicenseAccepted = state.legalAccepted,
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return state.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }

        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (
            val result = authRepository.verifyEmailOtpWithProfile(
                EmailOtpProfileRequest(
                    email = state.email,
                    otpCode = state.otpCode,
                    onboarding = onboarding,
                ),
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

    suspend fun signInWithSocialIdToken(
        state: AuthUiState,
        provider: SocialAuthProvider,
        idToken: String,
        strings: KwaborStrings,
    ): AuthUiState {
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (
            val result = authRepository.signInWithSocialProvider(
                SocialSignInRequest(provider = provider, idToken = idToken, onboarding = null),
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
            is DomainResult.Failure -> state.copy(errorMessage = result.error.toAuthMessage(strings))
        }

    private fun DomainError.toAuthMessage(strings: KwaborStrings): String = when (this) {
        is DomainError.AuthenticationRequired -> strings.authSessionExpired
        is DomainError.NetworkUnavailable -> strings.offlineBanner
        is DomainError.PermissionDenied -> strings.authPermissionDenied
        is DomainError.NotFound,
        is DomainError.Unexpected,
        is DomainError.Validation,
        -> strings.authInvalidInput
    }
}
