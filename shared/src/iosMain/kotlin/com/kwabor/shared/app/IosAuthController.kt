package com.kwabor.shared.app

import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.AuthStep
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.initialAuthUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class IosAuthController internal constructor(
    private val presenter: AuthPresenter?,
    dispatcherProvider: DispatcherProvider,
) {
    private val strings = stringsFor(AppLocale.French)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private var observer: ((AuthUiState) -> Unit)? = null
    private var operationJob: Job? = null
    private var state = initialAuthUiState()

    val isConfigured: Boolean get() = presenter != null

    fun observe(observer: (AuthUiState) -> Unit) {
        this.observer = observer
        observer(state)
    }

    fun restoreSession(onCompleted: (Boolean) -> Unit) {
        val currentPresenter = presenter
        if (currentPresenter == null) {
            onCompleted(false)
            return
        }
        scope.launch {
            state = currentPresenter.loadCurrentSession(state, strings)
            publish()
            onCompleted(state.isAuthenticated)
        }
    }

    fun updateEmail(email: String) = updateState { currentState ->
        presenter?.updateEmail(currentState, email) ?: currentState
    }

    fun updateFirstName(firstName: String) = updateState { currentState ->
        presenter?.updateFirstName(currentState, firstName) ?: currentState
    }

    fun updateLastName(lastName: String) = updateState { currentState ->
        presenter?.updateLastName(currentState, lastName) ?: currentState
    }

    fun updateOtpCode(otpCode: String) = updateState { currentState ->
        presenter?.updateOtpCode(currentState, otpCode) ?: currentState
    }

    fun updateLegalAccepted(accepted: Boolean) = updateState { currentState ->
        presenter?.updateLegalAccepted(currentState, accepted) ?: currentState
    }

    fun submit() {
        when (state.step) {
            AuthStep.Email -> requestEmailOtp()
            AuthStep.Otp -> verifyEmailOtp()
        }
    }

    fun requestEmailOtp() {
        val currentPresenter = presenter ?: return
        if (state.isLoading) return
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        publish()
        operationJob = scope.launch {
            state = currentPresenter.requestEmailOtp(state, strings)
            publish()
        }
    }

    fun verifyEmailOtp() {
        val currentPresenter = presenter ?: return
        if (state.isLoading) return
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        publish()
        operationJob = scope.launch {
            state = currentPresenter.verifyEmailOtpWithProfile(state, strings)
            publish()
        }
    }

    fun close() {
        observer = null
        scope.cancel()
    }

    private fun updateState(transform: (AuthUiState) -> AuthUiState) {
        state = transform(state)
        publish()
    }

    private fun publish() {
        observer?.invoke(state)
    }
}
