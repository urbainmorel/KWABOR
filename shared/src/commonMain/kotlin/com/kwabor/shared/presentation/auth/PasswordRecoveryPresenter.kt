package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.i18n.KwaborStrings

private const val MINIMUM_PASSWORD_LENGTH = 8
private const val OTP_RESEND_DELAY_MILLISECONDS = 30_000L

class PasswordRecoveryPresenter(
    private val authRepository: AuthRepository,
    private val clockProvider: ClockProvider,
) {
    fun resumeVerifiedSession(state: PasswordRecoveryUiState, email: String?): PasswordRecoveryUiState = state.copy(
        step = PasswordRecoveryStep.NewPassword,
        email = email?.trim().orEmpty(),
        resendAvailableAtEpochMilliseconds = null,
        isLoading = false,
        errorMessage = null,
        noticeMessage = null,
    )

    suspend fun requestCode(
        state: PasswordRecoveryUiState,
        email: String,
        strings: KwaborStrings,
    ): PasswordRecoveryUiState {
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return requestCode(loadingState, email, strings, PasswordRecoveryStep.Otp)
    }

    suspend fun resendCode(state: PasswordRecoveryUiState, strings: KwaborStrings): PasswordRecoveryUiState {
        val now = clockProvider.nowEpochMilliseconds()
        if (!state.canResendOtp(now)) {
            return state.copy(errorMessage = strings.registrationOtpWait, noticeMessage = null)
        }
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return requestCode(loadingState, state.email, strings, PasswordRecoveryStep.Otp)
    }

    suspend fun verifyOtp(
        state: PasswordRecoveryUiState,
        otpCode: String,
        strings: KwaborStrings,
    ): PasswordRecoveryUiState {
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (val result = authRepository.verifyPasswordRecoveryOtp(state.email, otpCode)) {
            is DomainResult.Success -> if (result.value.purpose == AuthSessionPurpose.PasswordRecovery) {
                loadingState.copy(
                    step = PasswordRecoveryStep.NewPassword,
                    isLoading = false,
                )
            } else {
                loadingState.copy(
                    isLoading = false,
                    errorMessage = DomainError.AuthenticationRequired().toAuthMessage(strings),
                )
            }
            is DomainResult.Failure -> loadingState.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
    }

    suspend fun complete(
        state: PasswordRecoveryUiState,
        password: String,
        confirmation: String,
        strings: KwaborStrings,
    ): PasswordRecoveryUiState {
        if (password.length < MINIMUM_PASSWORD_LENGTH) {
            return state.copy(errorMessage = strings.registrationPasswordTooShort, noticeMessage = null)
        }
        if (password != confirmation) {
            return state.copy(errorMessage = strings.registrationPasswordMismatch, noticeMessage = null)
        }
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (val result = authRepository.completePasswordRecovery(password)) {
            is DomainResult.Success -> loadingState.copy(
                step = PasswordRecoveryStep.Completed,
                isLoading = false,
                noticeMessage = strings.passwordRecoverySuccess,
            )
            is DomainResult.Failure -> loadingState.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
    }

    suspend fun cancel(state: PasswordRecoveryUiState, strings: KwaborStrings): PasswordRecoveryUiState {
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (val result = authRepository.cancelPasswordRecovery()) {
            is DomainResult.Success -> initialPasswordRecoveryUiState()
            is DomainResult.Failure -> loadingState.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
    }

    private suspend fun requestCode(
        loadingState: PasswordRecoveryUiState,
        email: String,
        strings: KwaborStrings,
        successStep: PasswordRecoveryStep,
    ): PasswordRecoveryUiState = when (val result = authRepository.requestPasswordRecovery(email)) {
        is DomainResult.Success -> {
            val now = clockProvider.nowEpochMilliseconds()
            loadingState.copy(
                step = successStep,
                email = email.trim(),
                resendAvailableAtEpochMilliseconds = now + OTP_RESEND_DELAY_MILLISECONDS,
                isLoading = false,
                noticeMessage = strings.passwordRecoveryCodeSent,
            )
        }
        is DomainResult.Failure -> loadingState.copy(
            isLoading = false,
            errorMessage = result.error.toAuthMessage(strings),
        )
    }
}
