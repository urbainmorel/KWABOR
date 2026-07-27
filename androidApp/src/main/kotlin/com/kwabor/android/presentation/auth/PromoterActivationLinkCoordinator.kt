package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.AndroidDeepLinkClassifier
import com.kwabor.android.auth.AndroidDeepLinkDestination
import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationDeepLinkParser
import com.kwabor.shared.domain.auth.PromoterActivationDeepLinkResult
import com.kwabor.shared.domain.auth.PromoterActivationSessionProof
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal class PromoterActivationLinkCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
    private val flow: PromoterActivationFlowState,
    private val cleanup: PromoterActivationCleanupCoordinator,
    private val retrySessionRestore: () -> Unit,
) {
    private val callbackGate = PromoterCallbackGate(runtime)

    fun open(callbackUrl: String) {
        if (!canOpen(callbackUrl)) return
        flow.clear()
        flow.pendingCallbackUrl = callbackUrl
        runtime.promoterActivationState.value = PromoterActivationUiState(
            stage = PromoterActivationStage.Loading,
        )
        schedulePendingCallback()
    }

    fun retry() {
        if (flow.pendingCallbackUrl == null) return
        if (runtime.sessionRestoreStatus.value == AuthSessionRestoreStatus.Failed) {
            retrySessionRestore()
        }
        runtime.promoterActivationState.value = PromoterActivationUiState(
            stage = PromoterActivationStage.Loading,
        )
        schedulePendingCallback()
    }

    private fun canOpen(callbackUrl: String): Boolean {
        val stage = runtime.promoterActivationState.value.stage
        val callbackAlreadyPending = flow.pendingCallbackUrl != null || flow.activationContext != null
        val promoterOperationRunning = stage == PromoterActivationStage.Activating ||
            stage == PromoterActivationStage.Cancelling
        return callbackUrl.isNotBlank() &&
            !runtime.accessState.value.accountDeletionInProgress &&
            !callbackAlreadyPending &&
            !promoterOperationRunning
    }

    private fun schedulePendingCallback() {
        runtime.promoterActivationJob?.cancel()
        runtime.promoterActivationJob = runtime.coroutineScope.launch {
            when (callbackGate.awaitSafeProcessingWindow()) {
                PromoterCallbackWindow.Ready -> exposeAndProcessPendingCallback()
                PromoterCallbackWindow.RestoreFailed -> exposeRestoreFailure()
                PromoterCallbackWindow.AccountDeletionWon -> discardPendingCallback()
            }
        }
    }

    private suspend fun exposeAndProcessPendingCallback() {
        val callbackUrl = flow.pendingCallbackUrl ?: return
        flow.exclusive = true
        runtime.platformState.value = AuthPlatformUiState(surface = AuthSurface.PromoterActivation)
        runtime.promoterActivationState.value = PromoterActivationUiState(
            stage = PromoterActivationStage.Loading,
        )
        processCallback(callbackUrl)
    }

    private fun exposeRestoreFailure() {
        if (flow.pendingCallbackUrl == null) return
        runtime.promoterActivationState.value = PromoterActivationUiState(
            stage = PromoterActivationStage.Error,
            errorMessage = runtime.strings.authFederatedUnavailable,
            retryAvailable = true,
        )
    }

    private fun discardPendingCallback() {
        flow.clear()
        runtime.promoterActivationState.value = PromoterActivationUiState()
    }

    private suspend fun processCallback(callbackUrl: String) {
        val preparedCallback = preparePromoterCallback(callbackUrl, runtime.authState.value.hasSession)
        if (preparedCallback == null) {
            publishInvalidCallback()
            return
        }
        if (!prepareProvisionalMarker(preparedCallback)) return
        val result = dependencies.authPresenter.handlePromoterActivationCallback(
            preparedCallback.callbackUrl,
            runtime.strings,
        )
        val context = result.value
        if (context == null) {
            publishCallbackError(result.errorMessage ?: runtime.strings.authInvalidInput)
            return
        }
        acceptContext(context)
    }

    private fun prepareProvisionalMarker(preparedCallback: PreparedPromoterCallback): Boolean {
        if (!preparedCallback.requiresProvisionalSessionMarker) return true
        flow.provisionalSessionMarkerPending = true
        if (dependencies.promoterActivationSessionStore.markImportedSessionPending()) return true
        cleanup.settleFailedProvisionalMarkerWrite()
        return false
    }

    private fun publishCallbackError(message: String) {
        val errorState = PromoterActivationUiState(
            stage = PromoterActivationStage.Error,
            errorMessage = message,
        )
        if (flow.provisionalSessionMarkerPending) {
            cleanup.begin(closeAfterCleanup = false, stateAfterCleanup = errorState)
        } else {
            runtime.promoterActivationState.value = errorState
        }
    }

    private fun acceptContext(context: PromoterActivationContext) {
        flow.activationContext = context
        if (context.sessionImportedForActivation) {
            flow.provisionalSessionMarkerPending = false
            flow.pendingCallbackUrl = null
            publishReady(context)
            return
        }
        clearUnusedProvisionalMarkerOrFail(context)
    }

    private fun clearUnusedProvisionalMarkerOrFail(context: PromoterActivationContext) {
        if (
            flow.provisionalSessionMarkerPending &&
            !dependencies.promoterActivationSessionStore.clear()
        ) {
            flow.pendingCallbackUrl = null
            flow.activationContext = null
            cleanup.begin(
                closeAfterCleanup = false,
                stateAfterCleanup = PromoterActivationUiState(
                    stage = PromoterActivationStage.Error,
                    errorMessage = runtime.strings.authFederatedUnavailable,
                    retryAvailable = false,
                ),
            )
            return
        }
        flow.provisionalSessionMarkerPending = false
        flow.pendingCallbackUrl = null
        publishReady(context)
    }

    private fun publishReady(context: PromoterActivationContext) {
        runtime.promoterActivationState.value = PromoterActivationUiState(
            stage = PromoterActivationStage.Ready,
            businessName = context.businessName,
        )
    }

    private fun publishInvalidCallback() {
        flow.pendingCallbackUrl = null
        runtime.promoterActivationState.value = PromoterActivationUiState(
            stage = PromoterActivationStage.Error,
            errorMessage = runtime.strings.authInvalidInput,
            retryAvailable = false,
        )
    }
}

private fun preparePromoterCallback(callbackUrl: String, hasExistingSession: Boolean): PreparedPromoterCallback? {
    if (
        AndroidDeepLinkClassifier.classify(callbackUrl) !=
        AndroidDeepLinkDestination.PromoterActivation
    ) {
        return null
    }
    val accepted = PromoterActivationDeepLinkParser.parse(callbackUrl)
        as? PromoterActivationDeepLinkResult.Accepted
        ?: return null
    return accepted.toPreparedCallback(callbackUrl, hasExistingSession)
}

private fun PromoterActivationDeepLinkResult.Accepted.toPreparedCallback(
    callbackUrl: String,
    hasExistingSession: Boolean,
): PreparedPromoterCallback = when (sessionProof) {
    is PromoterActivationSessionProof.PkceCode -> PreparedPromoterCallback(
        callbackUrl = if (hasExistingSession) {
            promoterActivationCallbackForExistingSession(inviteToken)
        } else {
            callbackUrl
        },
        requiresProvisionalSessionMarker = !hasExistingSession,
    )
    PromoterActivationSessionProof.ExistingSession -> PreparedPromoterCallback(
        callbackUrl = callbackUrl,
        requiresProvisionalSessionMarker = false,
    )
}

private data class PreparedPromoterCallback(
    val callbackUrl: String,
    val requiresProvisionalSessionMarker: Boolean,
)

private enum class PromoterCallbackWindow {
    Ready,
    RestoreFailed,
    AccountDeletionWon,
}

private class PromoterCallbackGate(
    private val runtime: AuthViewModelRuntime,
) {
    suspend fun awaitSafeProcessingWindow(): PromoterCallbackWindow {
        val restoreStatus = runtime.sessionRestoreStatus.first { status ->
            status != AuthSessionRestoreStatus.InProgress
        }
        if (restoreStatus == AuthSessionRestoreStatus.Failed) {
            return PromoterCallbackWindow.RestoreFailed
        }
        return awaitConflictingAuthOperations()
    }

    private suspend fun awaitConflictingAuthOperations(): PromoterCallbackWindow {
        while (true) {
            if (accountDeletionIsRunning()) return PromoterCallbackWindow.AccountDeletionWon
            listOfNotNull(runtime.operationJob, runtime.accountDeletionJob).joinAll()
            if (accountDeletionIsRunning()) return PromoterCallbackWindow.AccountDeletionWon
            if (runtime.accessState.value.signOutInProgress) {
                runtime.accessState.first { state -> !state.signOutInProgress }
                continue
            }
            if (runtime.operationJob?.isActive != true) return PromoterCallbackWindow.Ready
        }
    }

    private fun accountDeletionIsRunning(): Boolean = runtime.accessState.value.accountDeletionInProgress ||
        runtime.accountDeletionJob?.isActive == true
}

private fun promoterActivationCallbackForExistingSession(inviteToken: String): String =
    "kwabor://auth/promoter-activate?token=$inviteToken"
