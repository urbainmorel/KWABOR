package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.InterruptedAuthJourney
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import kotlinx.coroutines.CancellationException
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
            AuthIntent.RetrySessionRestore -> retrySessionRestore()
        }
    }

    fun loadCurrentSession(onPasswordRecoverySession: suspend (AuthSession) -> Unit) {
        sessionRestorer.load(onPasswordRecoverySession)
    }

    fun retrySessionRestore() {
        sessionRestorer.retry()
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
        if (!dependencies.revokeObservabilityConsent()) {
            val errorMessage = runtime.strings.settings.privacyPersistenceError
            runtime.authState.value = runtime.authState.value.copy(isLoading = false, errorMessage = errorMessage)
            runtime.registrationState.value = runtime.registrationState.value.copy(errorMessage = errorMessage)
            runtime.accessState.value = runtime.accessState.value.copy(isLoading = false, errorMessage = errorMessage)
            return
        }
        runtime.operationJob?.cancel()
        runtime.authState.value = runtime.authState.value.copy(isLoading = true, errorMessage = null)
        runtime.operationJob = runtime.coroutineScope.launch {
            val updatedAuthState = dependencies.authPresenter.signOut(runtime.authState.value, runtime.strings)
            runtime.authState.value = updatedAuthState
            if (!updatedAuthState.hasSession && updatedAuthState.errorMessage == null) {
                dependencies.googleIdentityProvider.clearCredentialState()
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
        if (!dependencies.revokeObservabilityConsent()) {
            val errorMessage = runtime.strings.settings.privacyPersistenceError
            runtime.registrationState.value = runtime.registrationState.value.copy(errorMessage = errorMessage)
            runtime.accessState.value = runtime.accessState.value.copy(isLoading = false, errorMessage = errorMessage)
            return
        }
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
        dependencies.authJourneyStore.read() != InterruptedAuthJourney.None
}

private class AuthSessionRestorer(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
    private val onExistingAccount: suspend (String) -> Unit,
) {
    private var passwordRecoverySessionHandler: (suspend (AuthSession) -> Unit)? = null

    private val resumeIncompleteSession: suspend (AuthSession) -> Unit = { session ->
        val interruptedJourney = dependencies.authJourneyStore.read()
        runtime.registrationState.value = initialRegistrationUiState().copy(
            step = interruptedJourney.resumeRegistrationStep(),
            email = session.email.orEmpty(),
            firstName = session.suggestedFirstName.orEmpty(),
            lastName = session.suggestedLastName.orEmpty(),
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
        passwordRecoverySessionHandler = onPasswordRecoverySession
        start(onPasswordRecoverySession)
    }

    fun retry() {
        if (runtime.sessionRestoreStatus.value != AuthSessionRestoreStatus.Failed) return
        val handler = passwordRecoverySessionHandler ?: return
        start(handler)
    }

    private fun start(onPasswordRecoverySession: suspend (AuthSession) -> Unit) {
        if (runtime.sessionRestoreJob?.isActive == true) return
        runtime.sessionRestoreStatus.value = AuthSessionRestoreStatus.InProgress
        runtime.sessionRestoreComplete.value = false
        runtime.sessionRestoreJob = runtime.coroutineScope.launch {
            var restoreSucceeded = false
            try {
                if (!revokePendingPromoterActivationSession()) return@launch
                val state = dependencies.authPresenter.loadCurrentSession(initialAuthUiState(), runtime.strings)
                if (state.errorMessage != null) {
                    runtime.authState.value = state
                    return@launch
                }
                route(state, onPasswordRecoverySession)
                restoreSucceeded = true
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                runtime.authState.value = initialAuthUiState().copy(
                    errorMessage = runtime.strings.authFederatedUnavailable,
                )
            } finally {
                runtime.sessionRestoreStatus.value = if (restoreSucceeded) {
                    AuthSessionRestoreStatus.Ready
                } else {
                    AuthSessionRestoreStatus.Failed
                }
                publishRestoreSurface(restoreSucceeded)
                runtime.sessionRestoreComplete.value = true
            }
        }
    }

    private fun publishRestoreSurface(restoreSucceeded: Boolean) {
        if (!restoreSucceeded) {
            runtime.platformState.value = AuthPlatformUiState(
                surface = AuthSurface.SessionRestoreFailure,
            )
            return
        }
        if (runtime.platformState.value.surface == AuthSurface.SessionRestoreFailure) {
            runtime.platformState.value = AuthPlatformUiState()
        }
    }

    private suspend fun revokePendingPromoterActivationSession(): Boolean {
        if (!dependencies.promoterActivationSessionStore.hasPendingImportedSession()) return true
        if (!dependencies.revokeObservabilityConsent()) {
            runtime.authState.value = initialAuthUiState().copy(
                errorMessage = runtime.strings.settings.privacyPersistenceError,
            )
            return false
        }
        val signedOutState = dependencies.authPresenter.signOut(initialAuthUiState(), runtime.strings)
        if (signedOutState.errorMessage != null) {
            runtime.authState.value = signedOutState
            return false
        }
        runtime.authState.value = signedOutState
        dependencies.googleIdentityProvider.clearCredentialState()
        if (!dependencies.promoterActivationSessionStore.clear()) {
            runtime.authState.value = signedOutState.copy(
                errorMessage = runtime.strings.authFederatedUnavailable,
            )
            return false
        }
        return true
    }

    private suspend fun route(
        state: com.kwabor.shared.presentation.auth.AuthUiState,
        onPasswordRecoverySession: suspend (AuthSession) -> Unit,
    ) {
        val session = state.currentSession
        val registrationInterrupted = dependencies.authJourneyStore.read() != InterruptedAuthJourney.None
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

private fun InterruptedAuthJourney.resumeRegistrationStep(): RegistrationStep = when (this) {
    InterruptedAuthJourney.SocialRegistration -> RegistrationStep.Identity
    InterruptedAuthJourney.None,
    InterruptedAuthJourney.Registration,
    -> RegistrationStep.Password
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
        AuthIntent.RetrySessionRestore -> false
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
        if (!dependencies.revokeObservabilityConsent()) {
            runtime.accessState.value = accessState.copy(
                signOutConfirmationVisible = false,
                signOutErrorMessage = runtime.strings.settings.privacyPersistenceError,
            )
            return
        }
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
            dependencies.googleIdentityProvider.clearCredentialState()
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
