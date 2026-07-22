package com.kwabor.shared.app

import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.PasswordRecoveryPresenter
import com.kwabor.shared.presentation.auth.PasswordRecoveryStep
import com.kwabor.shared.presentation.auth.PasswordRecoveryUiState
import com.kwabor.shared.presentation.auth.initialPasswordRecoveryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class IosPasswordRecoveryController internal constructor(
    private val presenter: PasswordRecoveryPresenter?,
    dispatcherProvider: DispatcherProvider,
) {
    private val strings = stringsFor(AppLocale.French)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private var observer: ((PasswordRecoveryUiState) -> Unit)? = null
    private var operationJob: Job? = null
    private var state = initialPasswordRecoveryUiState()
        set(value) {
            field = value
            observer?.invoke(value)
        }

    val isConfigured: Boolean get() = presenter != null

    fun observe(observer: (PasswordRecoveryUiState) -> Unit) {
        this.observer = observer
        observer(state)
    }

    fun resumeVerifiedSession(email: String?) {
        val currentPresenter = presenter ?: return
        operationJob?.cancel()
        operationJob = null
        state = currentPresenter.resumeVerifiedSession(state, email)
    }

    fun requestCode(email: String, onCompleted: (Boolean) -> Unit) {
        launchOperation(
            onCompleted = onCompleted,
            success = { updatedState ->
                updatedState.step == PasswordRecoveryStep.Otp && updatedState.errorMessage == null
            },
        ) { currentPresenter, currentState ->
            currentPresenter.requestCode(currentState, email, strings)
        }
    }

    fun resendCode(onCompleted: (Boolean) -> Unit) {
        launchOperation(
            onCompleted = onCompleted,
            success = { updatedState ->
                updatedState.step == PasswordRecoveryStep.Otp && updatedState.errorMessage == null
            },
        ) { currentPresenter, currentState ->
            currentPresenter.resendCode(currentState, strings)
        }
    }

    fun verifyOtp(otpCode: String, onCompleted: (Boolean) -> Unit) {
        launchOperation(
            onCompleted = onCompleted,
            success = { updatedState ->
                updatedState.step == PasswordRecoveryStep.NewPassword && updatedState.errorMessage == null
            },
        ) { currentPresenter, currentState ->
            currentPresenter.verifyOtp(currentState, otpCode, strings)
        }
    }

    fun complete(password: String, confirmation: String, onCompleted: (Boolean) -> Unit) {
        launchOperation(
            onCompleted = onCompleted,
            success = { updatedState ->
                updatedState.step == PasswordRecoveryStep.Completed && updatedState.errorMessage == null
            },
        ) { currentPresenter, currentState ->
            currentPresenter.complete(currentState, password, confirmation, strings)
        }
    }

    fun cancel(onCompleted: (Boolean) -> Unit) {
        launchOperation(
            onCompleted = onCompleted,
            success = { updatedState ->
                updatedState.step == PasswordRecoveryStep.Email && updatedState.errorMessage == null
            },
        ) { currentPresenter, currentState ->
            currentPresenter.cancel(currentState, strings)
        }
    }

    fun reset() {
        operationJob?.cancel()
        operationJob = null
        state = initialPasswordRecoveryUiState()
    }

    fun close() {
        observer = null
        scope.cancel()
    }

    private fun launchOperation(
        onCompleted: (Boolean) -> Unit,
        success: (PasswordRecoveryUiState) -> Boolean,
        operation: suspend (PasswordRecoveryPresenter, PasswordRecoveryUiState) -> PasswordRecoveryUiState,
    ) {
        val currentPresenter = presenter
        if (currentPresenter == null || state.isLoading) {
            onCompleted(false)
            return
        }
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        operationJob = scope.launch {
            state = operation(currentPresenter, state).copy(isLoading = false)
            onCompleted(success(state))
        }
    }
}
