package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.RegistrationIntent
import com.kwabor.shared.presentation.auth.RegistrationPresenter
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class AuthViewModelRuntime(
    val registrationPresenter: RegistrationPresenter,
    val strings: KwaborStrings,
    val coroutineScope: CoroutineScope,
) {
    val authState = MutableStateFlow(initialAuthUiState())
    val registrationState = MutableStateFlow(initialRegistrationUiState())
    val platformState = MutableStateFlow(AuthPlatformUiState())
    val sessionRestoreComplete = MutableStateFlow(false)
    val effectChannel = Channel<AuthEffect>(capacity = Channel.BUFFERED)
    val platformEffectChannel = Channel<AuthPlatformEffect>(capacity = Channel.BUFFERED)

    var operationJob: Job? = null
    var otpCountdownJob: Job? = null

    fun reduce(intent: RegistrationIntent) {
        registrationState.value = registrationPresenter.reducer.reduce(
            state = registrationState.value,
            intent = intent,
            strings = strings,
        )
    }

    fun completeAuthenticatedJourney() {
        val session = registrationState.value.currentSession ?: authState.value.currentSession ?: return
        if (session.accountSetupStatus != AccountSetupStatus.Complete) return
        authState.value = authState.value.copy(currentSession = session, isVisible = false, errorMessage = null)
        platformState.value = AuthPlatformUiState()
        coroutineScope.launch { effectChannel.send(AuthEffect.AuthenticationCompleted) }
    }
}

internal data class AuthViewModelDependencies(
    val authPresenter: com.kwabor.shared.presentation.auth.AuthPresenter,
    val registrationPresenter: RegistrationPresenter,
    val locationService: com.kwabor.android.auth.RegistrationLocationService,
    val notificationPermissionPolicy: com.kwabor.android.auth.NotificationPermissionPolicy,
    val notificationPrimingStore: com.kwabor.android.auth.NotificationPrimingStore,
    val clockProvider: com.kwabor.shared.domain.core.ClockProvider,
    val applyObservabilityConsent: (com.kwabor.shared.domain.observability.ObservabilityConsent) -> Boolean,
)
