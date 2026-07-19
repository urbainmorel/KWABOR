package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.NotificationPrimingStore
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationStep
import kotlinx.coroutines.launch

internal class AuthCredentialsCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val countdown: OtpCountdownCoordinator,
    private val notificationPrimingStore: NotificationPrimingStore,
) {
    fun handle(intent: AuthIntent.Credentials) {
        when (intent) {
            is AuthIntent.ChangeEmail -> runtime.reduce(RegistrationIntent.UpdateEmail(intent.email))
            AuthIntent.RequestOtp,
            AuthIntent.ResendOtp,
            -> requestOtp()
            is AuthIntent.SubmitOtp -> verifyOtp(intent.code)
            is AuthIntent.SubmitPassword -> setInitialPassword(intent.password, intent.confirmation)
            AuthIntent.RetryRequirements -> loadRequirements()
        }
    }

    private fun requestOtp() {
        if (runtime.registrationState.value.isLoading) return
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedState = runtime.registrationPresenter.requestOtp(
                runtime.registrationState.value,
                runtime.strings,
            )
            runtime.registrationState.value = updatedState
            countdown.start(updatedState.resendAvailableAtEpochMilliseconds)
        }
    }

    private fun verifyOtp(code: String) {
        if (runtime.registrationState.value.isLoading) return
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedState = runtime.registrationPresenter.verifyOtp(
                runtime.registrationState.value,
                code,
                runtime.strings,
            )
            runtime.registrationState.value = updatedState
            updatedState.currentSession?.let { session ->
                runtime.authState.value = runtime.authState.value.copy(currentSession = session, errorMessage = null)
            }
            if (updatedState.step == RegistrationStep.Completed) {
                routeCompletedSession()
            }
        }
    }

    private fun routeCompletedSession() {
        if (notificationPrimingStore.isResolved()) {
            runtime.completeAuthenticatedJourney()
            return
        }
        runtime.registrationState.value = runtime.registrationState.value.copy(
            step = RegistrationStep.NotificationPriming,
        )
        runtime.platformState.value = runtime.platformState.value.copy(surface = AuthSurface.Registration)
    }

    private fun setInitialPassword(password: String, confirmation: String) {
        if (runtime.registrationState.value.isLoading) return
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            var updatedState = runtime.registrationPresenter.setInitialPassword(
                state = runtime.registrationState.value,
                password = password,
                confirmation = confirmation,
                strings = runtime.strings,
            )
            if (updatedState.step == RegistrationStep.Identity) {
                updatedState = runtime.registrationPresenter.loadRequirements(updatedState, runtime.strings)
            }
            runtime.registrationState.value = updatedState
        }
    }

    private fun loadRequirements() {
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            runtime.registrationState.value = runtime.registrationPresenter.loadRequirements(
                runtime.registrationState.value,
                runtime.strings,
            )
        }
    }
}

internal class OtpCountdownCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
) {
    fun start(availableAtEpochMilliseconds: Long?) {
        runtime.otpCountdownJob?.cancel()
        if (availableAtEpochMilliseconds == null) {
            runtime.platformState.value = runtime.platformState.value.copy(otpResendSecondsRemaining = 0)
            return
        }
        runtime.otpCountdownJob = runtime.coroutineScope.launch {
            var remainingSeconds: Int
            do {
                remainingSeconds = (
                    (
                        availableAtEpochMilliseconds - dependencies.clockProvider.nowEpochMilliseconds() +
                            MILLISECONDS_ROUNDING_OFFSET
                        ) / MILLISECONDS_PER_SECOND
                    ).coerceAtLeast(0L).toInt()
                runtime.platformState.value = runtime.platformState.value.copy(
                    otpResendSecondsRemaining = remainingSeconds,
                )
                if (remainingSeconds > 0) kotlinx.coroutines.delay(MILLISECONDS_PER_SECOND)
            } while (remainingSeconds > 0)
        }
    }
}

private const val MILLISECONDS_PER_SECOND = 1_000L
private const val MILLISECONDS_ROUNDING_OFFSET = MILLISECONDS_PER_SECOND - 1L
