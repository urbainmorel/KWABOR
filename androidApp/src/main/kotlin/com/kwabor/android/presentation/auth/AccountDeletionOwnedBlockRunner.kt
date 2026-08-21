package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCleanupPendingCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCleanupPendingCancellation
import com.kwabor.shared.presentation.auth.AccountDeletionActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

internal class AccountDeletionOwnedBlockRunner(
    private val interactionLifecycle: AccountDeletionInteractionLifecycle,
    private val outcomeHandler: AccountDeletionOutcomeHandler,
    private val purgeWorker: AccountDeletionPurgeWorker,
    private val unexpectedErrorMessage: String,
) {
    suspend fun run(expectedAccountId: String, operation: suspend (() -> Unit) -> Unit) {
        val attempt = OwnedAccountDeletionAttempt()
        val ownerJob = currentCoroutineContext()[Job]
        try {
            val acquired = acquireInteractionBlock(
                expectedAccountId = expectedAccountId,
                ownerJob = ownerJob,
                attempt = attempt,
            )
            if (!acquired) return
            operation { attempt.remoteBoundaryCrossed = true }
        } catch (cancellation: AccountDeletionPreTransportCleanupPendingCancellation) {
            outcomeHandler.finishResolvedPreTransportCancellation(
                expectedAccountId = expectedAccountId,
                localCleanupPending = true,
            )
            cancellation.rethrow()
        } catch (cancellation: AccountDeletionOutcomeUnknownCleanupPendingCancellation) {
            outcomeHandler.finish(AccountDeletionActionResult.LocalCleanupPending, expectedAccountId)
            cancellation.rethrow()
        } catch (cancellation: AccountDeletionPreTransportCancellation) {
            handleResolvedPreTransportCancellation(expectedAccountId, attempt)
            cancellation.rethrow()
        } catch (cancellation: CancellationException) {
            handleUnclassifiedCancellation(expectedAccountId, attempt)
            cancellation.rethrow()
        } catch (_: Exception) {
            handleUnexpectedFailure(expectedAccountId, attempt)
        }
    }

    private suspend fun acquireInteractionBlock(
        expectedAccountId: String,
        ownerJob: Job?,
        attempt: OwnedAccountDeletionAttempt,
    ): Boolean {
        val handoff = purgeWorker.start(expectedAccountId)
        try {
            val result = handoff.awaitResult()
            if (result == null) {
                interactionLifecycle.publishAcquisitionTimeout()
                return false
            }
            return acceptPurgeResult(expectedAccountId, ownerJob, attempt, handoff, result)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                if (!attempt.ownsInteractionBlock) interactionLifecycle.publishAcquisitionCancelled()
            }
            throw cancellation
        } finally {
            if (!attempt.ownsInteractionBlock) {
                withContext(NonCancellable) {
                    if (attempt.localOwnershipClaimed) {
                        interactionLifecycle.discardLocalOwnership(expectedAccountId)
                    }
                    handoff.abandon()?.let { ownership ->
                        purgeWorker.resumeAbandonedAcquisition(ownership, handoff)
                    }
                }
            }
        }
    }

    private suspend fun acceptPurgeResult(
        expectedAccountId: String,
        ownerJob: Job?,
        attempt: OwnedAccountDeletionAttempt,
        handoff: AccountDeletionPurgeHandoff,
        result: AccountDeletionPurgeWorkerResult,
    ): Boolean = withContext(NonCancellable) {
        if (result !is AccountDeletionPurgeWorkerResult.Acquired) {
            return@withContext interactionLifecycle.acceptNonAcquiredPurgeResult(result)
        }
        if (!interactionLifecycle.claimLocalOwnership(expectedAccountId, result.ownership)) return@withContext false
        attempt.localOwnershipClaimed = true
        if (!handoff.claimAcquisition()) {
            interactionLifecycle.discardLocalOwnership(expectedAccountId)
            attempt.localOwnershipClaimed = false
            return@withContext false
        }
        attempt.ownsInteractionBlock = true
        attempt.localOwnershipClaimed = false
        val ownerActiveAfterPurge = ownerJob?.isActive == true
        if (!ownerActiveAfterPurge) {
            interactionLifecycle.resumeAfterFailure(expectedAccountId, errorMessage = null)
            attempt.ownsInteractionBlock = false
        }
        ownerActiveAfterPurge
    }

    private suspend fun handleResolvedPreTransportCancellation(
        expectedAccountId: String,
        attempt: OwnedAccountDeletionAttempt,
    ) {
        if (!attempt.ownsInteractionBlock) return
        outcomeHandler.finishResolvedPreTransportCancellation(
            expectedAccountId = expectedAccountId,
            localCleanupPending = false,
        )
    }

    private suspend fun handleUnclassifiedCancellation(
        expectedAccountId: String,
        attempt: OwnedAccountDeletionAttempt,
    ) {
        if (!attempt.ownsInteractionBlock) return
        if (attempt.remoteBoundaryCrossed) {
            outcomeHandler.finishPostBoundaryUnknown(expectedAccountId)
        } else {
            outcomeHandler.finishResolvedPreTransportCancellation(
                expectedAccountId = expectedAccountId,
                localCleanupPending = false,
            )
        }
    }

    private suspend fun handleUnexpectedFailure(expectedAccountId: String, attempt: OwnedAccountDeletionAttempt) {
        if (attempt.remoteBoundaryCrossed) {
            outcomeHandler.finishPostBoundaryUnknown(expectedAccountId)
        } else {
            outcomeHandler.finishResolvedPreTransportFailure(
                expectedAccountId = expectedAccountId,
                errorMessage = unexpectedErrorMessage,
            )
        }
    }
}

private class OwnedAccountDeletionAttempt(
    var ownsInteractionBlock: Boolean = false,
    var remoteBoundaryCrossed: Boolean = false,
    var localOwnershipClaimed: Boolean = false,
)

private fun CancellationException.rethrow(): Nothing = throw this
