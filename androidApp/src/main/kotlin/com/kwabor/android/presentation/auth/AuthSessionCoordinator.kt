package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import kotlinx.coroutines.launch

internal class AuthSessionCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
) {
    fun handle(intent: AuthIntent.Journey) {
        when (intent) {
            AuthIntent.OpenSoftWall -> openSoftWall()
            is AuthIntent.OpenRegistration -> openJourney(AuthSurface.Registration, intent.entryPoint)
            is AuthIntent.OpenSignIn -> openJourney(AuthSurface.SignIn, intent.entryPoint)
            AuthIntent.Dismiss -> cancelJourney()
            AuthIntent.ContinueAsGuest -> continueAsGuest()
            AuthIntent.Back -> goBack()
        }
    }

    fun loadCurrentSession() {
        runtime.coroutineScope.launch {
            try {
                val sessionState = dependencies.authPresenter.loadCurrentSession(initialAuthUiState(), runtime.strings)
                runtime.authState.value = sessionState
                val session = sessionState.currentSession
                if (session?.accountSetupStatus == AccountSetupStatus.OnboardingRequired) {
                    runtime.registrationState.value = initialRegistrationUiState().copy(
                        step = RegistrationStep.Password,
                        email = session.email.orEmpty(),
                        currentSession = session,
                    )
                    runtime.platformState.value = AuthPlatformUiState(surface = AuthSurface.Registration)
                    runtime.registrationState.value = runtime.registrationPresenter.loadRequirements(
                        runtime.registrationState.value,
                        runtime.strings,
                    )
                } else if (
                    session?.accountSetupStatus == AccountSetupStatus.Complete &&
                    !dependencies.notificationPrimingStore.isResolved()
                ) {
                    runtime.registrationState.value = initialRegistrationUiState().copy(
                        step = RegistrationStep.NotificationPriming,
                        email = session.email.orEmpty(),
                        currentSession = session,
                    )
                    runtime.platformState.value = AuthPlatformUiState(surface = AuthSurface.Registration)
                }
            } finally {
                runtime.sessionRestoreComplete.value = true
            }
        }
    }

    private fun openSoftWall() {
        runtime.platformState.value = runtime.platformState.value.copy(
            surface = AuthSurface.SoftWall,
            entryPoint = AuthEntryPoint.SoftWall,
        )
    }

    private fun openJourney(surface: AuthSurface, entryPoint: AuthEntryPoint) {
        if (runtime.registrationState.value.currentSession == null) {
            runtime.registrationState.value = initialRegistrationUiState()
        }
        runtime.platformState.value = runtime.platformState.value.copy(
            surface = surface,
            entryPoint = entryPoint,
            locationStatus = RegistrationLocationStatus.Idle,
        )
    }

    private fun cancelJourney() {
        if (runtime.registrationState.value.currentSession != null || runtime.authState.value.hasSession) {
            signOutPartialSession()
        } else {
            closeJourneyAfterCancellation()
        }
    }

    private fun signOutPartialSession() {
        if (runtime.authState.value.isLoading) return
        runtime.operationJob?.cancel()
        runtime.authState.value = runtime.authState.value.copy(isLoading = true, errorMessage = null)
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedAuthState = dependencies.authPresenter.signOut(runtime.authState.value, runtime.strings)
            runtime.authState.value = updatedAuthState
            if (!updatedAuthState.hasSession) {
                runtime.registrationState.value = initialRegistrationUiState()
                closeJourneyAfterCancellation()
            } else {
                runtime.registrationState.value = runtime.registrationState.value.copy(
                    errorMessage = updatedAuthState.errorMessage,
                )
            }
        }
    }

    private fun closeJourneyAfterCancellation() {
        val entryPoint = runtime.platformState.value.entryPoint
        runtime.platformState.value = AuthPlatformUiState()
        if (entryPoint == AuthEntryPoint.SoftWall) {
            runtime.coroutineScope.launch { runtime.effectChannel.send(AuthEffect.GuestContinuationSelected) }
        }
    }

    private fun continueAsGuest() {
        if (runtime.registrationState.value.currentSession != null || runtime.authState.value.hasSession) {
            runtime.platformState.value = runtime.platformState.value.copy(entryPoint = AuthEntryPoint.SoftWall)
            signOutPartialSession()
        } else {
            runtime.platformState.value = AuthPlatformUiState()
            runtime.coroutineScope.launch { runtime.effectChannel.send(AuthEffect.GuestContinuationSelected) }
        }
    }

    private fun goBack() {
        if (runtime.registrationState.value.step == RegistrationStep.Email) {
            cancelJourney()
        } else {
            runtime.reduce(RegistrationIntent.GoBack)
        }
    }
}
