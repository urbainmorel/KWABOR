package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.RegistrationUiState
import kotlinx.coroutines.launch

internal class AuthProfileCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
) {
    fun handle(intent: AuthIntent.Profile) {
        when (intent) {
            is AuthIntent.ProfileField -> handleField(intent)
            is AuthIntent.ProfileProgress -> handleProgress(intent)
        }
    }

    private fun handleField(intent: AuthIntent.ProfileField) {
        when (intent) {
            is AuthIntent.ChangeFirstName -> runtime.reduce(RegistrationIntent.UpdateFirstName(intent.firstName))
            is AuthIntent.ChangeLastName -> runtime.reduce(RegistrationIntent.UpdateLastName(intent.lastName))
            is AuthIntent.SelectCity -> runtime.reduce(RegistrationIntent.SelectCity(intent.cityId))
            is AuthIntent.SelectCurrency -> runtime.reduce(RegistrationIntent.SelectCurrency(intent.currency))
            is AuthIntent.ChangeLegalAcceptance -> runtime.reduce(
                RegistrationIntent.UpdateLegalAcceptance(intent.type, intent.accepted),
            )
        }
    }

    private fun handleProgress(intent: AuthIntent.ProfileProgress) {
        when (intent) {
            AuthIntent.CompleteProfile -> completeProfile()
        }
    }

    private fun completeProfile() {
        if (runtime.registrationState.value.isLoading || runtime.operationJob?.isActive == true) return
        val validatedState = runtime.registrationPresenter.reducer.reduce(
            state = runtime.registrationState.value,
            intent = RegistrationIntent.CompleteProfile,
            strings = runtime.strings,
        )
        runtime.registrationState.value = validatedState
        if (validatedState.errorMessage != null) {
            dependencies.track(AnalyticsEvent(name = AnalyticsEventName.RegistrationProfileFailed))
            return
        }
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            submitValidatedProfile(validatedState)
        }
    }

    private suspend fun submitValidatedProfile(validatedState: RegistrationUiState) {
        val updatedState = runtime.registrationPresenter.completeOnboarding(validatedState, runtime.strings)
        runtime.registrationState.value = updatedState
        trackProfileResult(updatedState.step)
        val session = updatedState.currentSession
        if (
            updatedState.step != RegistrationStep.Completed ||
            session?.accountSetupStatus != AccountSetupStatus.Complete
        ) {
            return
        }
        if (!dependencies.authJourneyStore.clear()) {
            runtime.registrationState.value = updatedState.copy(errorMessage = runtime.strings.authInvalidInput)
            return
        }
        runtime.authState.value = runtime.authState.value.copy(currentSession = session, errorMessage = null)
        runtime.completeAuthenticatedJourney()
    }

    private fun trackProfileResult(step: RegistrationStep) {
        val eventName = if (step == RegistrationStep.Completed) {
            AnalyticsEventName.RegistrationProfileSucceeded
        } else {
            AnalyticsEventName.RegistrationProfileFailed
        }
        dependencies.track(AnalyticsEvent(name = eventName))
    }
}
