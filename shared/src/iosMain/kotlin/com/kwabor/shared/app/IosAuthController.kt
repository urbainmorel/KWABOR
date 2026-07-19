package com.kwabor.shared.app

import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
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
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        publish()
        operationJob = scope.launch {
            state = currentPresenter.loadCurrentSession(state, strings).copy(isLoading = false)
            publish()
            onCompleted(state.isAuthenticated)
        }
    }

    fun signInWithEmail(email: String, password: String) {
        signInWithEmail(email = email, password = password, onCompleted = {})
    }

    fun signInWithEmail(email: String, password: String, onCompleted: (Boolean) -> Unit) {
        val currentPresenter = presenter
        if (currentPresenter == null || state.isLoading) {
            onCompleted(false)
            return
        }
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        publish()
        operationJob = scope.launch {
            state = currentPresenter.signInWithEmail(
                state = state,
                email = email,
                password = password,
                strings = strings,
            )
            publish()
            onCompleted(state.currentSession != null && state.errorMessage == null)
        }
    }

    fun signInWithSocialIdToken(provider: SocialAuthProvider, idToken: String) {
        val currentPresenter = presenter ?: return
        if (state.isLoading) return
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        publish()
        operationJob = scope.launch {
            state = currentPresenter.signInWithSocialIdToken(
                state = state,
                provider = provider,
                idToken = idToken,
                strings = strings,
            )
            publish()
        }
    }

    fun signOut(onCompleted: (Boolean) -> Unit) {
        val currentPresenter = presenter
        if (currentPresenter == null || state.isLoading) {
            onCompleted(false)
            return
        }
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        publish()
        operationJob = scope.launch {
            state = currentPresenter.signOut(state, strings).copy(isLoading = false)
            publish()
            onCompleted(state.currentSession == null && state.errorMessage == null)
        }
    }

    fun close() {
        observer = null
        scope.cancel()
    }

    private fun publish() {
        observer?.invoke(state)
    }
}
