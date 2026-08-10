package com.kwabor.android.presentation.auth

import com.kwabor.shared.presentation.auth.AccountDeletionActionResult
import com.kwabor.shared.presentation.auth.initialAuthUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class AccountDeletionOutcomeHandler(
    private val runtime: AuthViewModelRuntime,
    private val interactionLifecycle: AccountDeletionInteractionLifecycle,
    private val providerCleanup: AccountDeletionProviderCleanupCoordinator,
    private val callbacks: AccountDeletionTerminalCallbacks,
) {
    suspend fun finish(result: AccountDeletionActionResult, expectedAccountId: String) {
        when (result) {
            AccountDeletionActionResult.Deleted -> finishDeleted(expectedAccountId)
            AccountDeletionActionResult.OutcomeUnknown,
            AccountDeletionActionResult.LocalCleanupPending,
            -> finishUncertainRemoteOutcome(expectedAccountId)
            is AccountDeletionActionResult.RejectedCleanupPending ->
                finishRejectedCleanupPending(expectedAccountId, result.errorMessage)
            is AccountDeletionActionResult.Rejected -> finishRejected(expectedAccountId, result.errorMessage)
        }
    }

    suspend fun finishResolvedPreTransportCancellation(expectedAccountId: String, localCleanupPending: Boolean) =
        withContext(NonCancellable) {
            val markerCleared = providerCleanup.clearAfterResolvedPreTransport()
            val mustFailClosed = localCleanupPending || !markerCleared
            interactionLifecycle.resumeAfterFailure(
                expectedAccountId = expectedAccountId,
                errorMessage = runtime.strings.settings.privacyPersistenceError.takeIf { mustFailClosed },
            )
            if (mustFailClosed) failAuthClosed()
        }

    suspend fun finishResolvedPreTransportFailure(expectedAccountId: String, errorMessage: String) =
        withContext(NonCancellable) {
            val markerCleared = providerCleanup.clearAfterResolvedPreTransport()
            interactionLifecycle.resumeAfterFailure(
                expectedAccountId = expectedAccountId,
                errorMessage = if (markerCleared) errorMessage else runtime.strings.settings.privacyPersistenceError,
            )
            if (!markerCleared) failAuthClosed()
        }

    suspend fun finishPostBoundaryUnknown(expectedAccountId: String) = withContext(NonCancellable) {
        val deletedTerminalPublished = runtime.accountDeletionNavigationPending.value
        interactionLifecycle.confirmRemoteSuccess(expectedAccountId)
        callbacks.consumeIdempotencyKey()
        failAuthClosed()
        if (!deletedTerminalPublished) runtime.accountDeletionOutcomeUnknown.value = true
        callbacks.publishError(
            if (deletedTerminalPublished) {
                runtime.strings.settings.privacyPersistenceError
            } else {
                runtime.strings.authAccountDeletionOutcomeUnknown
            },
        )
    }

    private suspend fun finishDeleted(expectedAccountId: String) {
        val effectQueued = withContext(NonCancellable) {
            interactionLifecycle.confirmRemoteSuccess(expectedAccountId)
            callbacks.consumeIdempotencyKey()
            callbacks.markDeletionCompleted()
            callbacks.publishReady()
            runtime.effectChannel.trySend(AuthEffect.AccountDeleted).isSuccess
        }
        val providerCleanupCompleted = try {
            providerCleanup.clearAfterRemoteBoundary()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                failAuthClosed()
                callbacks.publishError(runtime.strings.settings.privacyPersistenceError)
            }
            throw cancellation
        }
        withContext(NonCancellable) {
            if (!providerCleanupCompleted) failAuthClosed()
            if (!effectQueued || !providerCleanupCompleted) {
                callbacks.publishError(runtime.strings.settings.privacyPersistenceError)
            }
        }
    }

    private suspend fun finishUncertainRemoteOutcome(expectedAccountId: String) {
        withContext(NonCancellable) {
            interactionLifecycle.confirmRemoteSuccess(expectedAccountId)
            callbacks.consumeIdempotencyKey()
            runtime.accountDeletionOutcomeUnknown.value = true
            failAuthClosed()
            callbacks.publishError(runtime.strings.authAccountDeletionOutcomeUnknown)
        }
        providerCleanup.clearAfterRemoteBoundary()
    }

    private suspend fun finishRejectedCleanupPending(expectedAccountId: String, errorMessage: String) =
        withContext(NonCancellable) {
            val markerCleared = providerCleanup.clearAfterResolvedPreTransport()
            interactionLifecycle.resumeAfterFailure(
                expectedAccountId = expectedAccountId,
                errorMessage = if (markerCleared) errorMessage else runtime.strings.settings.privacyPersistenceError,
            )
            failAuthClosed()
        }

    private suspend fun finishRejected(expectedAccountId: String, errorMessage: String) = withContext(NonCancellable) {
        val markerCleared = providerCleanup.clearAfterResolvedPreTransport()
        interactionLifecycle.resumeAfterFailure(
            expectedAccountId = expectedAccountId,
            errorMessage = if (markerCleared) errorMessage else runtime.strings.settings.privacyPersistenceError,
        )
        if (!markerCleared) failAuthClosed()
    }

    private fun failAuthClosed() {
        runtime.authState.value = initialAuthUiState()
        publishRestoreFailure()
    }

    private fun publishRestoreFailure() {
        runtime.sessionRestoreStatus.value = AuthSessionRestoreStatus.Failed
        runtime.sessionRestoreComplete.value = true
        runtime.platformState.value = AuthPlatformUiState(
            surface = AuthSurface.SessionRestoreFailure,
        )
    }
}

internal data class AccountDeletionTerminalCallbacks(
    val consumeIdempotencyKey: () -> Unit,
    val markDeletionCompleted: () -> Unit,
    val publishReady: () -> Unit,
    val publishError: (String) -> Unit,
)
