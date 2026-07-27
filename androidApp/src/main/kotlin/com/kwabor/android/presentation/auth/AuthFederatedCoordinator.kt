package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.GoogleIdentityResult
import com.kwabor.android.auth.InterruptedAuthJourney
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import kotlinx.coroutines.launch

internal class AuthFederatedCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
    private val sessionCoordinator: AuthSessionCoordinator,
) {
    fun handle(intent: AuthIntent.Federated) {
        when (intent) {
            AuthIntent.ContinueWithGoogle -> continueWithGoogle()
        }
    }

    private fun continueWithGoogle() {
        val sourceSurface = runtime.platformState.value.surface
        if (!sourceSurface.acceptsFederatedAuthentication() || isLoading(sourceSurface)) return
        if (
            sourceSurface == AuthSurface.Registration &&
            runtime.registrationState.value.step != RegistrationStep.Email
        ) {
            return
        }
        publishLoading(sourceSurface, loading = true)
        runtime.operationJob?.cancel()
        runtime.operationJob = runtime.coroutineScope.launch {
            when (val result = dependencies.googleIdentityProvider.acquireIdToken()) {
                GoogleIdentityResult.Cancelled -> publishLoading(sourceSurface, loading = false)
                GoogleIdentityResult.Unavailable -> publishUnavailable(sourceSurface)
                is GoogleIdentityResult.Success -> authenticate(sourceSurface, result)
            }
        }
    }

    private suspend fun authenticate(sourceSurface: AuthSurface, credential: GoogleIdentityResult.Success) {
        if (runtime.platformState.value.surface != sourceSurface) {
            publishLoading(sourceSurface, loading = false)
            return
        }
        val updatedAuthState = dependencies.authPresenter.signInWithSocialIdToken(
            state = runtime.authState.value,
            request = SocialSignInRequest(
                provider = SocialAuthProvider.Google,
                idToken = credential.idToken,
                rawNonce = credential.nonce,
                suggestedFirstName = credential.profileHint.firstName,
                suggestedLastName = credential.profileHint.lastName,
            ),
            strings = runtime.strings,
        )
        val session = publishResult(sourceSurface, updatedAuthState) ?: return
        when (session.accountSetupStatus) {
            AccountSetupStatus.OnboardingRequired -> resumeOnboarding(session)
            AccountSetupStatus.Complete -> {
                dependencies.authJourneyStore.clear()
                sessionCoordinator.routeAuthenticatedSession(session)
            }
        }
    }

    private fun publishResult(
        sourceSurface: AuthSurface,
        updatedAuthState: com.kwabor.shared.presentation.auth.AuthUiState,
    ): AuthSession? {
        val errorMessage = updatedAuthState.errorMessage
        if (errorMessage != null) {
            runtime.authState.value = initialAuthUiState().copy(errorMessage = errorMessage)
            publishMessage(sourceSurface, errorMessage)
            return null
        }
        val session = updatedAuthState.currentSession
        if (session?.purpose != AuthSessionPurpose.Standard) {
            publishMessage(sourceSurface, runtime.strings.authInvalidInput)
            return null
        }
        runtime.authState.value = updatedAuthState
        publishLoading(sourceSurface, loading = false)
        return session
    }

    private suspend fun resumeOnboarding(session: AuthSession) {
        if (!dependencies.authJourneyStore.write(InterruptedAuthJourney.SocialRegistration)) {
            val signedOutState = dependencies.authPresenter.signOut(runtime.authState.value, runtime.strings)
            dependencies.googleIdentityProvider.clearCredentialState()
            runtime.authState.value = signedOutState
            publishMessage(runtime.platformState.value.surface, runtime.strings.authInvalidInput)
            return
        }
        var registrationState = initialRegistrationUiState().copy(
            step = RegistrationStep.Identity,
            email = session.email.orEmpty(),
            firstName = session.suggestedFirstName.orEmpty(),
            lastName = session.suggestedLastName.orEmpty(),
            currentSession = session,
        )
        registrationState = runtime.registrationPresenter.loadRequirements(registrationState, runtime.strings)
        runtime.registrationState.value = registrationState
        runtime.platformState.value = runtime.platformState.value.copy(surface = AuthSurface.Registration)
    }

    private fun isLoading(surface: AuthSurface): Boolean = when (surface) {
        AuthSurface.SignIn -> runtime.accessState.value.isLoading
        AuthSurface.Registration -> runtime.registrationState.value.isLoading
        AuthSurface.Hidden,
        AuthSurface.SoftWall,
        AuthSurface.PasswordRecovery,
        AuthSurface.PromoterActivation,
        AuthSurface.SessionRestoreFailure,
        -> false
    }

    private fun publishLoading(surface: AuthSurface, loading: Boolean) {
        runtime.platformState.value = runtime.platformState.value.copy(
            federatedSignInInProgress = loading,
        )
        when (surface) {
            AuthSurface.SignIn -> runtime.accessState.value = runtime.accessState.value.copy(
                isLoading = loading,
                errorMessage = null,
                noticeMessage = null,
            )
            AuthSurface.Registration -> runtime.registrationState.value = runtime.registrationState.value.copy(
                isLoading = loading,
                errorMessage = null,
                noticeMessage = null,
            )
            AuthSurface.Hidden,
            AuthSurface.SoftWall,
            AuthSurface.PasswordRecovery,
            AuthSurface.PromoterActivation,
            AuthSurface.SessionRestoreFailure,
            -> Unit
        }
    }

    private fun publishUnavailable(surface: AuthSurface) {
        publishMessage(surface, dependencies.googleIdentityUnavailableMessage)
    }

    private fun publishMessage(surface: AuthSurface, message: String) {
        runtime.platformState.value = runtime.platformState.value.copy(
            federatedSignInInProgress = false,
        )
        when (surface) {
            AuthSurface.SignIn -> runtime.accessState.value = runtime.accessState.value.copy(
                isLoading = false,
                errorMessage = message,
                noticeMessage = null,
            )
            AuthSurface.Registration -> runtime.registrationState.value = runtime.registrationState.value.copy(
                isLoading = false,
                errorMessage = message,
                noticeMessage = null,
            )
            AuthSurface.Hidden,
            AuthSurface.SoftWall,
            AuthSurface.PasswordRecovery,
            AuthSurface.PromoterActivation,
            AuthSurface.SessionRestoreFailure,
            -> Unit
        }
    }
}

private fun AuthSurface.acceptsFederatedAuthentication(): Boolean = when (this) {
    AuthSurface.SignIn, AuthSurface.Registration -> true
    AuthSurface.Hidden,
    AuthSurface.SoftWall,
    AuthSurface.PasswordRecovery,
    AuthSurface.PromoterActivation,
    AuthSurface.SessionRestoreFailure,
    -> false
}
