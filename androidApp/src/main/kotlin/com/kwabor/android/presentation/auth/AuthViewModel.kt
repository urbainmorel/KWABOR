package com.kwabor.android.presentation.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.RegistrationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

internal class AuthViewModel(
    private val dependencies: AuthViewModelDependencies,
    strings: KwaborStrings,
    coroutineScope: CoroutineScope,
) : ViewModel() {
    private val runtime = AuthViewModelRuntime(
        registrationPresenter = dependencies.registrationPresenter,
        passwordRecoveryPresenter = dependencies.passwordRecoveryPresenter,
        strings = strings,
        coroutineScope = coroutineScope,
    )
    private val sessionCoordinator = AuthSessionCoordinator(runtime, dependencies)
    private val countdownCoordinator = OtpCountdownCoordinator(runtime, dependencies)
    private val credentialsCoordinator = AuthCredentialsCoordinator(
        runtime,
        countdownCoordinator,
        dependencies.authJourneyStore,
        sessionCoordinator,
        dependencies.track,
    )
    private val signInCoordinator = AuthSignInCoordinator(runtime, dependencies, sessionCoordinator)
    private val federatedCoordinator = AuthFederatedCoordinator(runtime, dependencies, sessionCoordinator)
    private val accountDeletionCoordinator = AccountDeletionCoordinator(runtime, dependencies)
    private val promoterActivationCoordinator = PromoterActivationCoordinator(
        runtime = runtime,
        dependencies = dependencies,
        retrySessionRestore = sessionCoordinator::retrySessionRestore,
    )
    private val passwordRecoveryCoordinator = PasswordRecoveryCoordinator(
        runtime = runtime,
        dependencies = dependencies,
    )
    private val profileCoordinator = AuthProfileCoordinator(runtime, dependencies)
    private val platformCoordinator = AuthPlatformCoordinator(runtime)

    val state: StateFlow<AuthUiState> = runtime.authState.asStateFlow()
    val accessState: StateFlow<AuthAccessUiState> = runtime.accessState.asStateFlow()
    val registrationState: StateFlow<RegistrationUiState> = runtime.registrationState.asStateFlow()
    val passwordRecoveryState = runtime.passwordRecoveryState.asStateFlow()
    val promoterActivationState: StateFlow<PromoterActivationUiState> =
        runtime.promoterActivationState.asStateFlow()
    val platformState: StateFlow<AuthPlatformUiState> = runtime.platformState.asStateFlow()
    val isSessionRestoreComplete: StateFlow<Boolean> = runtime.sessionRestoreComplete.asStateFlow()
    val sessionRestoreStatus: StateFlow<AuthSessionRestoreStatus> =
        runtime.sessionRestoreStatus.asStateFlow()
    val effects: Flow<AuthEffect> = runtime.effectChannel.receiveAsFlow()
    val platformEffects: Flow<AuthPlatformEffect> = runtime.platformEffectChannel.receiveAsFlow()

    init {
        sessionCoordinator.loadCurrentSession(passwordRecoveryCoordinator::resumeSession)
    }

    fun onIntent(intent: AuthIntent) {
        if (blocksWhileSessionCleanupFailed(intent)) return
        if (promoterActivationCoordinator.blocks(intent)) return
        dispatch(intent)
    }

    private fun dispatch(intent: AuthIntent) {
        when (intent) {
            AuthIntent.OpenPasswordRecovery -> passwordRecoveryCoordinator.open()
            is AuthIntent.Journey -> handleJourneyIntent(intent)
            is AuthIntent.Credentials -> credentialsCoordinator.handle(intent)
            is AuthIntent.SignIn -> signInCoordinator.handle(intent)
            is AuthIntent.Federated -> federatedCoordinator.handle(intent)
            is AuthIntent.AccountSecurity -> accountDeletionCoordinator.handle(intent)
            is AuthIntent.PromoterActivation -> promoterActivationCoordinator.handle(intent)
            is AuthIntent.PasswordRecovery -> passwordRecoveryCoordinator.handle(intent)
            is AuthIntent.Profile -> profileCoordinator.handle(intent)
            is AuthIntent.Platform -> platformCoordinator.handle(intent)
        }
    }

    private fun handleJourneyIntent(intent: AuthIntent.Journey) {
        if (intent == AuthIntent.Back && runtime.platformState.value.surface == AuthSurface.PasswordRecovery) {
            passwordRecoveryCoordinator.goBack()
        } else {
            sessionCoordinator.handle(intent)
        }
        if (intent == AuthIntent.RetrySessionRestore) {
            promoterActivationCoordinator.resumePendingCallbackAfterSessionRestoreRetry()
        }
    }

    private fun blocksWhileSessionCleanupFailed(intent: AuthIntent): Boolean {
        if (runtime.sessionRestoreStatus.value != AuthSessionRestoreStatus.Failed) return false
        return when (intent) {
            AuthIntent.RetrySessionRestore -> false
            is AuthIntent.OpenPromoterActivation -> false
            else -> true
        }
    }

    fun attachGoogleIdentityActivity(activity: Activity) {
        dependencies.googleIdentityProvider.attachActivity(activity)
    }

    fun detachGoogleIdentityActivity(activity: Activity) {
        dependencies.googleIdentityProvider.detachActivity(activity)
    }

    override fun onCleared() {
        accountDeletionCoordinator.clearSensitiveState()
        promoterActivationCoordinator.clearSensitiveState()
        runtime.effectChannel.close()
        runtime.platformEffectChannel.close()
        runtime.coroutineScope.cancel()
        super.onCleared()
    }
}
