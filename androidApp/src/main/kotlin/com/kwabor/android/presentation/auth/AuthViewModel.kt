package com.kwabor.android.presentation.auth

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
    dependencies: AuthViewModelDependencies,
    strings: KwaborStrings,
    coroutineScope: CoroutineScope,
) : ViewModel() {
    private val runtime = AuthViewModelRuntime(
        registrationPresenter = dependencies.registrationPresenter,
        strings = strings,
        coroutineScope = coroutineScope,
    )
    private val sessionCoordinator = AuthSessionCoordinator(runtime, dependencies)
    private val countdownCoordinator = OtpCountdownCoordinator(runtime, dependencies)
    private val credentialsCoordinator = AuthCredentialsCoordinator(
        runtime,
        countdownCoordinator,
        dependencies.notificationPrimingStore,
    )
    private val profileCoordinator = AuthProfileCoordinator(runtime, dependencies)
    private val platformCoordinator = AuthPlatformCoordinator(runtime, dependencies)

    val state: StateFlow<AuthUiState> = runtime.authState.asStateFlow()
    val registrationState: StateFlow<RegistrationUiState> = runtime.registrationState.asStateFlow()
    val platformState: StateFlow<AuthPlatformUiState> = runtime.platformState.asStateFlow()
    val isSessionRestoreComplete: StateFlow<Boolean> = runtime.sessionRestoreComplete.asStateFlow()
    val effects: Flow<AuthEffect> = runtime.effectChannel.receiveAsFlow()
    val platformEffects: Flow<AuthPlatformEffect> = runtime.platformEffectChannel.receiveAsFlow()

    init {
        sessionCoordinator.loadCurrentSession()
    }

    fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.Journey -> sessionCoordinator.handle(intent)
            is AuthIntent.Credentials -> credentialsCoordinator.handle(intent)
            is AuthIntent.ProfileField -> profileCoordinator.handle(intent)
            is AuthIntent.ProfileProgress -> profileCoordinator.handle(intent)
            is AuthIntent.Platform -> platformCoordinator.handle(intent)
        }
    }

    override fun onCleared() {
        runtime.effectChannel.close()
        runtime.platformEffectChannel.close()
        runtime.coroutineScope.cancel()
        super.onCleared()
    }
}
