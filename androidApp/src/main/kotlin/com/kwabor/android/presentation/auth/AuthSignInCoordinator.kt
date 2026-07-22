package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.InterruptedAuthJourney
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import kotlinx.coroutines.launch

internal class AuthSignInCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
    private val sessionCoordinator: AuthSessionCoordinator,
) {
    fun handle(intent: AuthIntent.SignIn) {
        when (intent) {
            is AuthIntent.ChangeSignInEmail -> changeEmail(intent.email)
            AuthIntent.ContinueFromSignInEmail -> continueFromEmail()
            is AuthIntent.SubmitSignInPassword -> submitPassword(intent.password)
        }
    }

    private fun changeEmail(email: String) {
        if (runtime.accessState.value.isLoading) return
        runtime.accessState.value = runtime.accessState.value.copy(
            signInEmail = email,
            errorMessage = null,
            noticeMessage = null,
        )
    }

    private fun continueFromEmail() {
        val state = runtime.accessState.value
        if (state.isLoading) return
        if (!state.signInEmail.looksLikeEmail()) {
            runtime.accessState.value = state.copy(errorMessage = runtime.strings.authInvalidInput)
            return
        }
        runtime.accessState.value = state.copy(
            signInEmail = state.signInEmail.trim(),
            signInStep = SignInStep.Password,
            errorMessage = null,
            noticeMessage = null,
        )
    }

    private fun submitPassword(password: String) {
        val state = runtime.accessState.value
        if (state.isLoading) return
        if (password.isEmpty()) {
            runtime.accessState.value = state.copy(errorMessage = runtime.strings.authInvalidInput)
            return
        }
        runtime.accessState.value = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            completePasswordSignIn(email = state.signInEmail, password = password)
        }
    }

    private suspend fun completePasswordSignIn(email: String, password: String) {
        val updatedAuthState = dependencies.authPresenter.signInWithEmail(
            state = runtime.authState.value,
            email = email,
            password = password,
            strings = runtime.strings,
        )
        val authenticatedSession = publishSignInResult(updatedAuthState) ?: return
        if (!clearInterruptedRegistration()) return
        runtime.authState.value = updatedAuthState
        when (authenticatedSession.accountSetupStatus) {
            AccountSetupStatus.OnboardingRequired -> resumeIncompleteAccount(authenticatedSession)
            AccountSetupStatus.Complete -> sessionCoordinator.routeAuthenticatedSession(authenticatedSession)
        }
    }

    private fun publishSignInResult(updatedAuthState: com.kwabor.shared.presentation.auth.AuthUiState): AuthSession? {
        runtime.accessState.value = runtime.accessState.value.copy(
            isLoading = false,
            errorMessage = updatedAuthState.errorMessage,
            noticeMessage = updatedAuthState.noticeMessage,
        )
        if (updatedAuthState.errorMessage != null) {
            runtime.authState.value = initialAuthUiState().copy(errorMessage = updatedAuthState.errorMessage)
            return null
        }
        val session = updatedAuthState.currentSession
        if (session?.purpose == AuthSessionPurpose.Standard) return session
        publishInvalidSignInResult()
        return null
    }

    private fun clearInterruptedRegistration(): Boolean {
        if (dependencies.authJourneyStore.read() != InterruptedAuthJourney.Registration) return true
        if (dependencies.authJourneyStore.clear()) return true
        publishInvalidSignInResult()
        return false
    }

    private fun publishInvalidSignInResult() {
        runtime.authState.value = initialAuthUiState().copy(errorMessage = runtime.strings.authInvalidInput)
        runtime.accessState.value = runtime.accessState.value.copy(
            isLoading = false,
            errorMessage = runtime.strings.authInvalidInput,
            noticeMessage = null,
        )
    }

    private suspend fun resumeIncompleteAccount(session: AuthSession) {
        var registrationState = initialRegistrationUiState().copy(
            step = RegistrationStep.Identity,
            email = session.email.orEmpty(),
            currentSession = session,
        )
        registrationState = runtime.registrationPresenter.loadRequirements(registrationState, runtime.strings)
        runtime.registrationState.value = registrationState
        runtime.platformState.value = runtime.platformState.value.copy(surface = AuthSurface.Registration)
    }
}

internal fun String.looksLikeEmail(): Boolean = isNotBlank() && '@' in this && none(Char::isWhitespace)
