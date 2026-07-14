package com.kwabor.android.presentation.auth

import androidx.lifecycle.ViewModel
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.initialAuthUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal sealed interface AuthIntent {
    data class ChangeEmail(val email: String) : AuthIntent

    data class ChangeFirstName(val firstName: String) : AuthIntent

    data class ChangeLastName(val lastName: String) : AuthIntent

    data class ChangeOtpCode(val otpCode: String) : AuthIntent

    data class ChangeLegalAccepted(val accepted: Boolean) : AuthIntent

    data object Open : AuthIntent

    data object Dismiss : AuthIntent

    data object RequestOtp : AuthIntent

    data object VerifyOtp : AuthIntent

    data object ContinueAsGuest : AuthIntent
}

internal sealed interface AuthEffect {
    data object AuthenticationCompleted : AuthEffect

    data object GuestContinuationSelected : AuthEffect
}

internal class AuthViewModel(
    private val presenter: AuthPresenter,
    private val strings: KwaborStrings,
    private val coroutineScope: CoroutineScope,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialAuthUiState())
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<AuthEffect>(capacity = Channel.BUFFERED)
    val effects: Flow<AuthEffect> = effectChannel.receiveAsFlow()

    private var operationJob: Job? = null

    init {
        loadCurrentSession()
    }

    fun onIntent(intent: AuthIntent) {
        when (intent) {
            is AuthIntent.ChangeEmail -> updateState { state -> presenter.updateEmail(state, intent.email) }
            is AuthIntent.ChangeFirstName -> updateState { state ->
                presenter.updateFirstName(state, intent.firstName)
            }
            is AuthIntent.ChangeLastName -> updateState { state -> presenter.updateLastName(state, intent.lastName) }
            is AuthIntent.ChangeOtpCode -> updateState { state -> presenter.updateOtpCode(state, intent.otpCode) }
            is AuthIntent.ChangeLegalAccepted -> updateState { state ->
                presenter.updateLegalAccepted(state, intent.accepted)
            }
            AuthIntent.Open -> updateState { state -> state.copy(isVisible = true) }
            AuthIntent.Dismiss -> updateState { state -> state.copy(isVisible = false) }
            AuthIntent.RequestOtp -> requestOtp()
            AuthIntent.VerifyOtp -> verifyOtp()
            AuthIntent.ContinueAsGuest -> continueAsGuest()
        }
    }

    override fun onCleared() {
        effectChannel.close()
        coroutineScope.cancel()
        super.onCleared()
    }

    private fun loadCurrentSession() {
        coroutineScope.launch {
            val sessionState = presenter.loadCurrentSession(initialAuthUiState(), strings)
            val currentState = mutableState.value
            mutableState.value = currentState.copy(
                currentSession = sessionState.currentSession,
                errorMessage = currentState.errorMessage ?: sessionState.errorMessage,
                noticeMessage = currentState.noticeMessage ?: sessionState.noticeMessage,
            )
        }
    }

    private fun requestOtp() {
        if (mutableState.value.isLoading) return
        operationJob?.cancel()
        mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        operationJob = coroutineScope.launch {
            val updatedState = presenter.requestEmailOtp(mutableState.value, strings)
            mutableState.value = updatedState.copy(isVisible = mutableState.value.isVisible)
        }
    }

    private fun verifyOtp() {
        if (mutableState.value.isLoading) return
        operationJob?.cancel()
        mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        operationJob = coroutineScope.launch {
            val updatedState = presenter.verifyEmailOtpWithProfile(mutableState.value, strings)
            mutableState.value = updatedState.copy(
                isVisible = !updatedState.isAuthenticated && mutableState.value.isVisible,
            )
            if (updatedState.isAuthenticated) {
                effectChannel.send(AuthEffect.AuthenticationCompleted)
            }
        }
    }

    private fun continueAsGuest() {
        mutableState.value = mutableState.value.copy(isVisible = false)
        coroutineScope.launch {
            effectChannel.send(AuthEffect.GuestContinuationSelected)
        }
    }

    private fun updateState(transform: (AuthUiState) -> AuthUiState) {
        mutableState.value = transform(mutableState.value)
    }
}
