package com.kwabor.shared.app

import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.PromoterActivationResult
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class IosAuthSessionRestoreResult internal constructor(
    val isFailure: Boolean,
    val hasSession: Boolean,
) {
    val isReady: Boolean get() = !isFailure
    val isUnauthenticated: Boolean get() = isReady && !hasSession
}

class IosAuthController internal constructor(
    private val presenter: AuthPresenter?,
    dispatcherProvider: DispatcherProvider,
    accountDeletionInteractionLifecycle: IosAccountDeletionInteractionLifecycle,
) {
    private val strings = stringsFor(AppLocale.French)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private var observer: ((AuthUiState) -> Unit)? = null
    private var operationJob: Job? = null
    private var state = initialAuthUiState()
    private var accountDeletionCleanupPending = false
    private var closed = false
    private val authMutationBlocked: Boolean get() = state.isLoading || accountDeletionCleanupPending
    internal val hasPendingAccountDeletionCleanup: Boolean get() = accountDeletionCleanupPending
    private val accountDeletionCoordinator = IosAccountDeletionCoordinator(
        presenter = presenter,
        strings = strings,
        coroutineScope = scope,
        interactionLifecycle = accountDeletionInteractionLifecycle,
        host = IosAccountDeletionHost(
            currentState = { state },
            publishState = { updatedState ->
                state = updatedState
                observer.publish(updatedState)
            },
            onLocalCleanupPending = { accountDeletionCleanupPending = true },
            isClosed = { closed },
        ),
    )

    internal constructor(
        presenter: AuthPresenter?,
        dispatcherProvider: DispatcherProvider,
        interactionCoordinator: InteractionCoordinator? = null,
    ) : this(
        presenter = presenter,
        dispatcherProvider = dispatcherProvider,
        accountDeletionInteractionLifecycle = IosAccountDeletionInteractionLifecycle(interactionCoordinator),
    )

    val isConfigured: Boolean get() = presenter != null

    fun observe(observer: (AuthUiState) -> Unit) {
        this.observer = observer
        observer(state)
    }

    fun restoreSession(onCompleted: (IosAuthSessionRestoreResult) -> Unit) {
        val currentPresenter = presenter
        if (currentPresenter == null) {
            onCompleted(IosAuthSessionRestoreResult(isFailure = true, hasSession = false))
            return
        }
        operationJob?.cancel()
        val retryingAccountDeletionCleanup = accountDeletionCleanupPending
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        observer.publish(state)
        operationJob = scope.launch {
            state = currentPresenter.loadCurrentSession(state, strings).copy(isLoading = false)
            if (state.errorMessage == null) accountDeletionCleanupPending = false
            if (retryingAccountDeletionCleanup && accountDeletionCleanupPending) {
                state = initialAuthUiState().copy(errorMessage = strings.authAccountDeletionOutcomeUnknown)
            }
            observer.publish(state)
            onCompleted(
                IosAuthSessionRestoreResult(
                    isFailure = state.errorMessage != null,
                    hasSession = state.currentSession != null,
                ),
            )
        }
    }

    fun signInWithEmail(email: String, password: String, onCompleted: (Boolean) -> Unit) {
        val currentPresenter = presenter
        if (currentPresenter == null || authMutationBlocked) {
            onCompleted(false)
            return
        }
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        observer.publish(state)
        operationJob = scope.launch {
            state = currentPresenter.signInWithEmail(
                state = state,
                email = email,
                password = password,
                strings = strings,
            )
            observer.publish(state)
            onCompleted(state.currentSession != null && state.errorMessage == null)
        }
    }

    fun signInWithSocialIdToken(request: SocialSignInRequest, onCompleted: (Boolean) -> Unit) {
        val currentPresenter = presenter
        if (currentPresenter == null || authMutationBlocked) {
            onCompleted(false)
            return
        }
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        observer.publish(state)
        operationJob = scope.launch {
            state = currentPresenter.signInWithSocialIdToken(
                state = state,
                request = request,
                strings = strings,
            )
            observer.publish(state)
            onCompleted(state.currentSession != null && state.errorMessage == null)
        }
    }

    fun handlePromoterActivationCallback(callbackUrl: String, onCompleted: (PromoterActivationContext?) -> Unit) {
        val currentPresenter = presenter
        if (currentPresenter == null || authMutationBlocked) {
            onCompleted(null)
            return
        }
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        observer.publish(state)
        operationJob = scope.launch {
            val result = currentPresenter.handlePromoterActivationCallback(callbackUrl, strings)
            state = state.copy(isLoading = false, errorMessage = result.errorMessage, noticeMessage = null)
            observer.publish(state)
            onCompleted(result.value)
        }
    }

    fun activatePromoterInviteWithPassword(
        inviteToken: String,
        password: String,
        onCompleted: (PromoterActivationResult?) -> Unit,
    ) {
        activatePromoterInvite(
            request = PromoterActivationRequest(
                inviteToken = inviteToken,
                password = password,
                socialSignInRequest = null,
            ),
            onCompleted = onCompleted,
        )
    }

    fun activatePromoterInviteWithSocial(
        inviteToken: String,
        request: SocialSignInRequest,
        onCompleted: (PromoterActivationResult?) -> Unit,
    ) {
        activatePromoterInvite(
            request = PromoterActivationRequest(
                inviteToken = inviteToken,
                password = null,
                socialSignInRequest = request,
            ),
            onCompleted = onCompleted,
        )
    }

    fun deleteAccountWithPassword(password: String, idempotencyKey: String, onCompleted: (Boolean) -> Unit) {
        accountDeletionCoordinator.deleteWithPassword(
            password = password,
            idempotencyKey = idempotencyKey,
            onCompleted = onCompleted,
        )
    }

    fun deleteAccountWithSocial(request: SocialSignInRequest, idempotencyKey: String, onCompleted: (Boolean) -> Unit) {
        accountDeletionCoordinator.deleteWithSocial(
            credential = AccountDeletionCredential.Social(request),
            idempotencyKey = idempotencyKey,
            onCompleted = onCompleted,
        )
    }

    fun prepareAccountDeletion(onCompleted: (Boolean) -> Unit) {
        accountDeletionCoordinator.prepareFederated(onCompleted)
    }

    fun cancelPreparedAccountDeletion(onCompleted: (Boolean) -> Unit) {
        accountDeletionCoordinator.cancelPrepared(onCompleted)
    }

    fun signOut(onCompleted: (Boolean) -> Unit) {
        val currentPresenter = presenter
        if (currentPresenter == null || authMutationBlocked) {
            onCompleted(false)
            return
        }
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        observer.publish(state)
        operationJob = scope.launch {
            state = currentPresenter.signOut(state, strings).copy(isLoading = false)
            observer.publish(state)
            onCompleted(state.currentSession == null && state.errorMessage == null)
        }
    }

    fun close() {
        closed = true
        observer = null
        scope.cancel()
    }

    private fun activatePromoterInvite(
        request: PromoterActivationRequest,
        onCompleted: (PromoterActivationResult?) -> Unit,
    ) {
        val currentPresenter = presenter
        if (currentPresenter == null || authMutationBlocked) {
            onCompleted(null)
            return
        }
        operationJob?.cancel()
        state = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        observer.publish(state)
        operationJob = scope.launch {
            val result = currentPresenter.activatePromoterInvite(request, strings)
            val activation = result.value
            state = state.copy(
                isLoading = false,
                currentSession = activation?.session ?: state.currentSession,
                errorMessage = result.errorMessage,
                noticeMessage = if (activation != null) strings.promoterActivationSuccess else null,
            )
            observer.publish(state)
            onCompleted(activation)
        }
    }
}

private fun ((AuthUiState) -> Unit)?.publish(state: AuthUiState) {
    this?.invoke(state)
}
