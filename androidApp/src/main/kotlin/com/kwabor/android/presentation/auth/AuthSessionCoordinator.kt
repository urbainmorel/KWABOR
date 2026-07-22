package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.InterruptedAuthJourney
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import kotlinx.coroutines.launch

internal class AuthSessionCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
) {
    private val sessionRestorer = AuthSessionRestorer(runtime, dependencies, this::redirectExistingAccountToSignIn)
    private val signOutCoordinator = AuthSignOutCoordinator(runtime, dependencies)

    fun handle(intent: AuthIntent.Journey) {
        if (signOutCoordinator.handle(intent)) return
        when (intent) {
            AuthIntent.OpenSoftWall -> openSoftWall()
            is AuthIntent.OpenRegistration -> openJourney(AuthSurface.Registration, intent.entryPoint)
            is AuthIntent.OpenSignIn -> openJourney(AuthSurface.SignIn, intent.entryPoint)
            AuthIntent.Dismiss -> cancelJourney()
            AuthIntent.ContinueAsGuest -> continueAsGuest()
            AuthIntent.Back -> goBack()
            AuthIntent.OpenPasswordRecovery -> Unit
            AuthIntent.RequestSignOut,
            AuthIntent.CancelSignOut,
            AuthIntent.ConfirmSignOut,
            AuthIntent.SignOutNavigationHandled,
            -> Unit
        }
    }

    fun loadCurrentSession(onPasswordRecoverySession: suspend (AuthSession) -> Unit) {
        sessionRestorer.load(onPasswordRecoverySession)
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
        if (surface == AuthSurface.SignIn) {
            runtime.accessState.value = AuthAccessUiState()
        }
        runtime.platformState.value = runtime.platformState.value.copy(
            surface = surface,
            entryPoint = entryPoint,
            locationStatus = RegistrationLocationStatus.Idle,
        )
    }

    private fun cancelJourney() {
        if (
            runtime.registrationState.value.currentSession != null ||
            runtime.authState.value.hasSession ||
            hasInterruptedRegistration()
        ) {
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
            if (!updatedAuthState.hasSession && updatedAuthState.errorMessage == null) {
                dependencies.authJourneyStore.clear()
                runtime.registrationState.value = initialRegistrationUiState()
                closeJourneyAfterCancellation()
            } else if (hasInterruptedRegistration()) {
                runtime.authState.value = initialAuthUiState().copy(errorMessage = updatedAuthState.errorMessage)
                runtime.accessState.value = runtime.accessState.value.copy(
                    signInStep = SignInStep.Password,
                    isLoading = false,
                    errorMessage = updatedAuthState.errorMessage,
                    noticeMessage = null,
                )
                runtime.platformState.value = runtime.platformState.value.copy(surface = AuthSurface.SignIn)
            } else {
                runtime.registrationState.value = runtime.registrationState.value.copy(
                    errorMessage = updatedAuthState.errorMessage,
                )
            }
        }
    }

    suspend fun redirectExistingAccountToSignIn(email: String) {
        if (runtime.accessState.value.isLoading) return
        runtime.accessState.value = runtime.accessState.value.copy(isLoading = true, errorMessage = null)
        val updatedAuthState = dependencies.authPresenter.signOut(runtime.authState.value, runtime.strings)
        if (updatedAuthState.errorMessage != null) {
            runtime.authState.value = initialAuthUiState().copy(errorMessage = updatedAuthState.errorMessage)
            runtime.registrationState.value = initialRegistrationUiState()
            runtime.accessState.value = AuthAccessUiState(
                signInStep = SignInStep.Password,
                signInEmail = email,
                errorMessage = updatedAuthState.errorMessage,
            )
            runtime.platformState.value = runtime.platformState.value.copy(surface = AuthSurface.SignIn)
            return
        }
        dependencies.authJourneyStore.clear()
        runtime.authState.value = updatedAuthState
        runtime.registrationState.value = initialRegistrationUiState()
        runtime.accessState.value = AuthAccessUiState(
            signInStep = SignInStep.Password,
            signInEmail = email,
        )
        runtime.platformState.value = runtime.platformState.value.copy(surface = AuthSurface.SignIn)
    }

    private fun closeJourneyAfterCancellation() {
        val entryPoint = runtime.platformState.value.entryPoint
        runtime.platformState.value = AuthPlatformUiState()
        if (entryPoint == AuthEntryPoint.SoftWall) {
            runtime.coroutineScope.launch { runtime.effectChannel.send(AuthEffect.GuestContinuationSelected) }
        }
    }

    private fun continueAsGuest() {
        if (
            runtime.registrationState.value.currentSession != null ||
            runtime.authState.value.hasSession ||
            hasInterruptedRegistration()
        ) {
            runtime.platformState.value = runtime.platformState.value.copy(entryPoint = AuthEntryPoint.SoftWall)
            signOutPartialSession()
        } else {
            runtime.platformState.value = AuthPlatformUiState()
            runtime.coroutineScope.launch { runtime.effectChannel.send(AuthEffect.GuestContinuationSelected) }
        }
    }

    private fun goBack() {
        if (runtime.platformState.value.surface == AuthSurface.SignIn) {
            if (runtime.accessState.value.signInStep == SignInStep.Password) {
                runtime.accessState.value = runtime.accessState.value.copy(
                    signInStep = SignInStep.Email,
                    errorMessage = null,
                    noticeMessage = null,
                )
            } else {
                cancelJourney()
            }
        } else if (runtime.registrationState.value.step == RegistrationStep.Email) {
            cancelJourney()
        } else {
            runtime.reduce(RegistrationIntent.GoBack)
        }
    }

    fun routeAuthenticatedSession(session: AuthSession) {
        runtime.registrationState.value = initialRegistrationUiState().copy(currentSession = session)
        if (dependencies.notificationPrimingStore.isResolved()) {
            runtime.completeAuthenticatedJourney()
            return
        }
        runtime.registrationState.value = runtime.registrationState.value.copy(
            step = RegistrationStep.NotificationPriming,
        )
        runtime.platformState.value = runtime.platformState.value.copy(surface = AuthSurface.Registration)
    }

    private fun hasInterruptedRegistration(): Boolean =
        dependencies.authJourneyStore.read() == InterruptedAuthJourney.Registration
}

private class AuthSessionRestorer(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
    private val onExistingAccount: suspend (String) -> Unit,
) {
    private val resumeIncompleteSession: suspend (AuthSession) -> Unit = { session ->
        dependencies.authJourneyStore.clear()
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
    }

    private val showNotificationPriming: (AuthSession) -> Unit = { session ->
        runtime.registrationState.value = initialRegistrationUiState().copy(
            step = RegistrationStep.NotificationPriming,
            email = session.email.orEmpty(),
            currentSession = session,
        )
        runtime.platformState.value = AuthPlatformUiState(surface = AuthSurface.Registration)
    }

    fun load(onPasswordRecoverySession: suspend (AuthSession) -> Unit) {
        runtime.coroutineScope.launch {
            try {
                val state = dependencies.authPresenter.loadCurrentSession(initialAuthUiState(), runtime.strings)
                route(state, onPasswordRecoverySession)
            } finally {
                runtime.sessionRestoreComplete.value = true
            }
        }
    }

    private suspend fun route(
        state: com.kwabor.shared.presentation.auth.AuthUiState,
        onPasswordRecoverySession: suspend (AuthSession) -> Unit,
    ) {
        val session = state.currentSession
        val registrationInterrupted = dependencies.authJourneyStore.read() == InterruptedAuthJourney.Registration
        val mustRevokeOtpSession = registrationInterrupted &&
            session?.accountSetupStatus == AccountSetupStatus.Complete
        runtime.authState.value = if (mustRevokeOtpSession) initialAuthUiState() else state
        if (session == null && registrationInterrupted) dependencies.authJourneyStore.clear()
        when {
            session != null && state.hasPasswordRecoverySession -> onPasswordRecoverySession(session)
            mustRevokeOtpSession -> {
                runtime.platformState.value = AuthPlatformUiState(surface = AuthSurface.SignIn)
                onExistingAccount(session.email.orEmpty())
            }
            session?.accountSetupStatus == AccountSetupStatus.OnboardingRequired -> resumeIncompleteSession(session)
            session?.accountSetupStatus == AccountSetupStatus.Complete &&
                !dependencies.notificationPrimingStore.isResolved() -> showNotificationPriming(session)
        }
    }
}

private class AuthSignOutCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
) {
    fun handle(intent: AuthIntent.Journey): Boolean = when (intent) {
        AuthIntent.RequestSignOut -> true.also { request() }
        AuthIntent.CancelSignOut -> true.also { cancel() }
        AuthIntent.ConfirmSignOut -> true.also { confirm() }
        AuthIntent.SignOutNavigationHandled -> true.also { completeNavigation() }
        AuthIntent.OpenSoftWall,
        is AuthIntent.OpenRegistration,
        is AuthIntent.OpenSignIn,
        AuthIntent.Dismiss,
        AuthIntent.ContinueAsGuest,
        AuthIntent.Back,
        AuthIntent.OpenPasswordRecovery,
        -> false
    }

    private fun request() {
        if (!runtime.authState.value.isAuthenticated || runtime.accessState.value.signOutInProgress) return
        runtime.accessState.value = runtime.accessState.value.copy(
            signOutConfirmationVisible = true,
            signOutErrorMessage = null,
        )
    }

    private fun cancel() {
        if (runtime.accessState.value.signOutInProgress) return
        runtime.accessState.value = runtime.accessState.value.copy(
            signOutConfirmationVisible = false,
            signOutErrorMessage = null,
        )
    }

    private fun confirm() {
        val accessState = runtime.accessState.value
        if (!runtime.authState.value.isAuthenticated || accessState.signOutInProgress) return
        runtime.accessState.value = accessState.copy(signOutInProgress = true, signOutErrorMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            val signedOutState = dependencies.authPresenter.signOut(runtime.authState.value, runtime.strings)
            if (signedOutState.hasSession) {
                runtime.authState.value = signedOutState
                runtime.accessState.value = runtime.accessState.value.copy(
                    signOutInProgress = false,
                    signOutErrorMessage = signedOutState.errorMessage,
                )
                return@launch
            }
            runtime.pendingSignedOutState = signedOutState
            runtime.effectChannel.send(AuthEffect.SignedOut)
        }
    }

    private fun completeNavigation() {
        val signedOutState = runtime.pendingSignedOutState ?: return
        runtime.pendingSignedOutState = null
        runtime.authState.value = signedOutState
        runtime.registrationState.value = initialRegistrationUiState()
        runtime.accessState.value = AuthAccessUiState()
        runtime.platformState.value = AuthPlatformUiState()
    }
}
