package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.AuthJourneyStore
import com.kwabor.android.auth.InterruptedAuthJourney
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.observability.AnalyticsAuthMethod
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationRequirementsStatus
import com.kwabor.shared.presentation.auth.mergeRequirementsFrom
import kotlinx.coroutines.launch

internal class AuthCredentialsCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val countdown: OtpCountdownCoordinator,
    private val authJourneyStore: AuthJourneyStore,
    private val sessionCoordinator: AuthSessionCoordinator,
    private val track: (AnalyticsEvent) -> Unit,
) {
    fun handle(intent: AuthIntent.Credentials) {
        when (intent) {
            is AuthIntent.ChangeEmail -> runtime.reduce(RegistrationIntent.UpdateEmail(intent.email))
            AuthIntent.RequestOtp,
            AuthIntent.ResendOtp,
            -> requestOtp()
            is AuthIntent.SubmitOtp -> verifyOtp(intent.code)
            is AuthIntent.SubmitPassword -> setInitialPassword(intent.password)
            AuthIntent.RetryRequirements -> preloadRequirements()
        }
    }

    private fun requestOtp() {
        val state = runtime.registrationState.value
        if (state.isLoading) return
        runtime.registrationState.value = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedState = runtime.registrationPresenter.requestOtp(
                state,
                runtime.strings,
            )
            runtime.registrationState.value = updatedState.mergeRequirementsFrom(runtime.registrationState.value)
            countdown.start(updatedState.resendAvailableAtEpochMilliseconds)
            if (state.step == com.kwabor.shared.presentation.auth.RegistrationStep.Email &&
                updatedState.step == com.kwabor.shared.presentation.auth.RegistrationStep.Otp
            ) {
                track(
                    AnalyticsEvent(
                        name = AnalyticsEventName.AuthMethod,
                        authMethod = AnalyticsAuthMethod.Email,
                    ),
                )
            }
        }
    }

    private fun verifyOtp(code: String) {
        val state = runtime.registrationState.value
        if (state.isLoading) return
        if (!authJourneyStore.write(InterruptedAuthJourney.Registration)) {
            runtime.registrationState.value = runtime.registrationState.value.copy(
                errorMessage = runtime.strings.authInvalidInput,
            )
            return
        }
        runtime.registrationState.value = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedState = runtime.registrationPresenter.verifyOtp(
                state,
                code,
                runtime.strings,
            )
            runtime.registrationState.value = updatedState.mergeRequirementsFrom(runtime.registrationState.value)
            val session = updatedState.currentSession
            if (session == null) {
                authJourneyStore.clear()
                return@launch
            }
            track(AnalyticsEvent(name = AnalyticsEventName.RegistrationOtpValidated))
            if (session.accountSetupStatus == AccountSetupStatus.Complete) {
                sessionCoordinator.redirectExistingAccountToSignIn(updatedState.email)
                return@launch
            }
            runtime.authState.value = runtime.authState.value.copy(currentSession = session, errorMessage = null)
            authJourneyStore.clear()
        }
    }

    private fun setInitialPassword(password: String) {
        val state = runtime.registrationState.value
        if (state.isLoading) return
        runtime.registrationState.value = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedState = runtime.registrationPresenter.setInitialPassword(
                state = state,
                password = password,
                strings = runtime.strings,
            )
            runtime.registrationState.value = updatedState.mergeRequirementsFrom(runtime.registrationState.value)
        }
    }

    fun preloadRequirements() {
        val status = runtime.registrationState.value.requirementsStatus
        if (
            status == RegistrationRequirementsStatus.Loading ||
            status == RegistrationRequirementsStatus.Ready ||
            runtime.registrationRequirementsJob?.isActive == true
        ) {
            return
        }
        runtime.registrationRequirementsJob?.cancel()
        runtime.registrationState.value = runtime.registrationState.value.copy(
            requirementsStatus = RegistrationRequirementsStatus.Loading,
            requirementsErrorMessage = null,
        )
        runtime.registrationRequirementsJob = runtime.coroutineScope.launch {
            val loadedRequirements = runtime.registrationPresenter.loadRequirements(
                runtime.registrationState.value,
                runtime.strings,
            )
            runtime.registrationState.value = runtime.registrationState.value.mergeRequirementsFrom(loadedRequirements)
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
