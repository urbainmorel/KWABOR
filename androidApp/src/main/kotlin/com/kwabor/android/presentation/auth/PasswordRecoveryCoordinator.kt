package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.presentation.auth.PasswordRecoveryStep
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.auth.initialPasswordRecoveryUiState
import kotlinx.coroutines.launch

internal class PasswordRecoveryCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
) {
    fun handle(intent: AuthIntent.PasswordRecovery) {
        when (intent) {
            is AuthIntent.ChangeRecoveryEmail -> changeEmail(intent.email)
            AuthIntent.RequestRecoveryOtp -> requestCode()
            AuthIntent.ResendRecoveryOtp -> resendCode()
            is AuthIntent.SubmitRecoveryOtp -> verifyOtp(intent.code)
            is AuthIntent.SubmitRecoveryPassword -> complete(intent.password, intent.confirmation)
        }
    }

    fun open() {
        if (runtime.accessState.value.isLoading) return
        val email = runtime.accessState.value.signInEmail
        runtime.passwordRecoveryRequestedEmail = null
        runtime.passwordRecoveryState.value = initialPasswordRecoveryUiState().copy(email = email)
        runtime.platformState.value = runtime.platformState.value.copy(surface = AuthSurface.PasswordRecovery)
        startCountdown(null)
    }

    suspend fun resumeSession(session: AuthSession) {
        runtime.passwordRecoveryState.value = runtime.passwordRecoveryPresenter.resumeVerifiedSession(
            state = initialPasswordRecoveryUiState(),
            email = session.email,
        )
        runtime.platformState.value = AuthPlatformUiState(surface = AuthSurface.PasswordRecovery)
    }

    fun goBack() {
        val state = runtime.passwordRecoveryState.value
        if (state.isLoading) return
        when (state.step) {
            PasswordRecoveryStep.Email -> cancelAndReturnToSignIn()
            PasswordRecoveryStep.Otp -> {
                runtime.passwordRecoveryState.value = state.copy(
                    step = PasswordRecoveryStep.Email,
                    errorMessage = null,
                    noticeMessage = null,
                )
                startCountdown(state.resendAvailableAtEpochMilliseconds)
            }
            PasswordRecoveryStep.NewPassword,
            PasswordRecoveryStep.Completed,
            -> cancelAndReturnToSignIn()
        }
    }

    private fun changeEmail(email: String) {
        val state = runtime.passwordRecoveryState.value
        if (state.isLoading) return
        runtime.passwordRecoveryState.value = state.copy(
            email = email,
            errorMessage = null,
            noticeMessage = null,
        )
    }

    private fun requestCode() {
        val state = runtime.passwordRecoveryState.value
        if (state.isLoading) return
        if (!state.email.looksLikeEmail()) {
            runtime.passwordRecoveryState.value = state.copy(errorMessage = runtime.strings.authInvalidInput)
            return
        }
        runtime.passwordRecoveryState.value = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val normalizedEmail = state.email.trim()
            val isRepeatedRequest = runtime.passwordRecoveryRequestedEmail?.equals(
                normalizedEmail,
                ignoreCase = true,
            ) == true
            val updatedState = if (isRepeatedRequest) {
                runtime.passwordRecoveryPresenter.resendCode(state, runtime.strings)
            } else {
                runtime.passwordRecoveryPresenter.requestCode(
                    state = state,
                    email = normalizedEmail,
                    strings = runtime.strings,
                )
            }
            runtime.passwordRecoveryState.value = updatedState
            if (updatedState.step == PasswordRecoveryStep.Otp && updatedState.errorMessage == null) {
                runtime.passwordRecoveryRequestedEmail = normalizedEmail
            }
            startCountdown(updatedState.resendAvailableAtEpochMilliseconds)
        }
    }

    private fun resendCode() {
        val state = runtime.passwordRecoveryState.value
        if (state.isLoading || runtime.accessState.value.recoveryResendSecondsRemaining > 0) return
        runtime.passwordRecoveryState.value = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedState = runtime.passwordRecoveryPresenter.resendCode(state, runtime.strings)
            runtime.passwordRecoveryState.value = updatedState
            startCountdown(updatedState.resendAvailableAtEpochMilliseconds)
        }
    }

    private fun verifyOtp(code: String) {
        val state = runtime.passwordRecoveryState.value
        if (state.isLoading) return
        runtime.passwordRecoveryState.value = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedState = runtime.passwordRecoveryPresenter.verifyOtp(state, code, runtime.strings)
            runtime.passwordRecoveryState.value = updatedState
            if (updatedState.step == PasswordRecoveryStep.NewPassword) {
                runtime.authState.value = runtime.authState.value.copy(currentSession = null)
                startCountdown(null)
            }
        }
    }

    private fun complete(password: String, confirmation: String) {
        val state = runtime.passwordRecoveryState.value
        if (state.isLoading) return
        runtime.passwordRecoveryState.value = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedState = runtime.passwordRecoveryPresenter.complete(
                state = state,
                password = password,
                confirmation = confirmation,
                strings = runtime.strings,
            )
            runtime.passwordRecoveryState.value = updatedState
            if (updatedState.step == PasswordRecoveryStep.Completed) {
                runtime.passwordRecoveryRequestedEmail = null
                runtime.authState.value = initialAuthUiState()
                runtime.accessState.value = AuthAccessUiState(
                    signInStep = SignInStep.Password,
                    signInEmail = state.email,
                    noticeMessage = updatedState.noticeMessage,
                )
                runtime.platformState.value = AuthPlatformUiState(surface = AuthSurface.SignIn)
                runtime.passwordRecoveryState.value = initialPasswordRecoveryUiState()
            }
        }
    }

    private fun cancelAndReturnToSignIn() {
        val state = runtime.passwordRecoveryState.value
        runtime.passwordRecoveryState.value = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val cancelledState = runtime.passwordRecoveryPresenter.cancel(state, runtime.strings)
            if (cancelledState.errorMessage != null) {
                runtime.passwordRecoveryState.value = cancelledState
                return@launch
            }
            runtime.authState.value = initialAuthUiState()
            runtime.passwordRecoveryRequestedEmail = null
            runtime.passwordRecoveryState.value = initialPasswordRecoveryUiState()
            runtime.accessState.value = AuthAccessUiState(
                signInStep = SignInStep.Password,
                signInEmail = state.email,
            )
            runtime.platformState.value = AuthPlatformUiState(surface = AuthSurface.SignIn)
        }
    }

    private fun startCountdown(availableAtEpochMilliseconds: Long?) {
        runtime.recoveryCountdownJob?.cancel()
        if (availableAtEpochMilliseconds == null) {
            runtime.accessState.value = runtime.accessState.value.copy(recoveryResendSecondsRemaining = 0)
            return
        }
        runtime.recoveryCountdownJob = runtime.coroutineScope.launch {
            var remainingSeconds: Int
            do {
                remainingSeconds = (
                    (
                        availableAtEpochMilliseconds - dependencies.clockProvider.nowEpochMilliseconds() +
                            MILLISECONDS_ROUNDING_OFFSET
                        ) / MILLISECONDS_PER_SECOND
                    ).coerceAtLeast(0L).toInt()
                runtime.accessState.value = runtime.accessState.value.copy(
                    recoveryResendSecondsRemaining = remainingSeconds,
                )
                if (remainingSeconds > 0) kotlinx.coroutines.delay(MILLISECONDS_PER_SECOND)
            } while (remainingSeconds > 0)
        }
    }
}

private const val MILLISECONDS_PER_SECOND = 1_000L
private const val MILLISECONDS_ROUNDING_OFFSET = MILLISECONDS_PER_SECOND - 1L
