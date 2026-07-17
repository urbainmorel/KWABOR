package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationStep
import kotlinx.coroutines.launch

internal class AuthProfileCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
) {
    fun handle(intent: AuthIntent.ProfileField) {
        when (intent) {
            is AuthIntent.ChangeFirstName -> runtime.reduce(RegistrationIntent.UpdateFirstName(intent.firstName))
            is AuthIntent.ChangeLastName -> runtime.reduce(RegistrationIntent.UpdateLastName(intent.lastName))
            is AuthIntent.SelectCity -> {
                runtime.reduce(RegistrationIntent.SelectCity(intent.cityId))
                runtime.platformState.value = runtime.platformState.value.copy(
                    locationStatus = RegistrationLocationStatus.Idle,
                )
            }
            is AuthIntent.SelectCurrency -> runtime.reduce(RegistrationIntent.SelectCurrency(intent.currency))
            is AuthIntent.ChangeLegalAcceptance -> runtime.reduce(
                RegistrationIntent.UpdateLegalAcceptance(intent.type, intent.accepted),
            )
            is AuthIntent.ChangeAnalyticsConsent -> updateObservabilityConsent { consent ->
                consent.copy(analyticsAllowed = intent.accepted)
            }
            is AuthIntent.ChangeDiagnosticsConsent -> updateObservabilityConsent { consent ->
                consent.copy(diagnosticsAllowed = intent.accepted)
            }
            is AuthIntent.ChangeRemoteConfigurationConsent -> updateObservabilityConsent { consent ->
                consent.copy(remoteConfigurationAllowed = intent.accepted)
            }
        }
    }

    fun handle(intent: AuthIntent.ProfileProgress) {
        when (intent) {
            AuthIntent.ContinueFromIdentity -> runtime.reduce(RegistrationIntent.ContinueFromIdentity)
            AuthIntent.ContinueFromCity -> runtime.reduce(RegistrationIntent.ContinueFromCity)
            AuthIntent.ContinueFromCurrency -> runtime.reduce(RegistrationIntent.ContinueFromCurrency)
            AuthIntent.ContinueFromLegal -> runtime.reduce(RegistrationIntent.ContinueFromLegal)
            AuthIntent.CompleteOnboarding -> completeOnboarding()
        }
    }

    private fun updateObservabilityConsent(transform: (ObservabilityConsent) -> ObservabilityConsent) {
        val consent = transform(runtime.registrationState.value.observabilityConsent)
        runtime.platformState.value = runtime.platformState.value.copy(
            observabilityConsentPersistenceFailed = false,
        )
        runtime.reduce(RegistrationIntent.UpdateObservabilityConsent(consent))
    }

    private fun completeOnboarding() {
        if (runtime.registrationState.value.isLoading || runtime.operationJob?.isActive == true) return
        if (!dependencies.applyObservabilityConsent(runtime.registrationState.value.observabilityConsent)) {
            runtime.platformState.value = runtime.platformState.value.copy(
                observabilityConsentPersistenceFailed = true,
            )
            return
        }
        runtime.platformState.value = runtime.platformState.value.copy(
            observabilityConsentPersistenceFailed = false,
        )
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedState = runtime.registrationPresenter.completeOnboarding(
                runtime.registrationState.value,
                runtime.strings,
            )
            runtime.registrationState.value = updatedState
            val session = updatedState.currentSession
            if (
                updatedState.step == RegistrationStep.NotificationPriming &&
                session?.accountSetupStatus == AccountSetupStatus.Complete
            ) {
                runtime.authState.value = runtime.authState.value.copy(currentSession = session, errorMessage = null)
                if (dependencies.notificationPrimingStore.isResolved()) {
                    runtime.reduce(RegistrationIntent.FinishNotificationPriming)
                    runtime.completeAuthenticatedJourney()
                }
            }
        }
    }
}
