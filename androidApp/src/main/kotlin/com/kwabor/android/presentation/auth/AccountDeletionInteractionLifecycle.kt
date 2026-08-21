package com.kwabor.android.presentation.auth

import com.kwabor.shared.presentation.auth.AccountPrivateDataPurgeOwnership
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AccountDeletionInteractionLifecycle(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
    private val publishReady: () -> Unit,
    private val publishError: (String) -> Unit,
) {
    private val ownershipMutex = Mutex()
    private var blockedAccountOwnerId: String? = null
    private var blockedOwnership: AccountPrivateDataPurgeOwnership? = null

    fun captureAuthenticatedAccountId(): String? = runtime.authState.value
        .takeIf { state -> state.isAuthenticated }
        ?.currentSession
        ?.userId

    fun acceptNonAcquiredPurgeResult(result: AccountDeletionPurgeWorkerResult): Boolean = when (result) {
        AccountDeletionPurgeWorkerResult.Failed -> publishStorageFailure()
        is AccountDeletionPurgeWorkerResult.Recovery -> {
            if (result.resumed) clearLocalOwnership(result.ownership)
            publishStorageFailure()
        }
        AccountDeletionPurgeWorkerResult.AlreadyBlocked -> {
            publishError(runtime.strings.authAccountDeletionOutcomeUnknown)
            false
        }
        is AccountDeletionPurgeWorkerResult.Acquired -> false
    }

    suspend fun claimLocalOwnership(
        expectedAccountId: String,
        ownership: AccountPrivateDataPurgeOwnership,
    ): Boolean {
        val ownershipAcquired = ownershipMutex.withLock {
            val currentOwnerId = blockedAccountOwnerId
            if (currentOwnerId == null) {
                blockedAccountOwnerId = expectedAccountId
                blockedOwnership = ownership
                true
            } else {
                false
            }
        }
        if (ownershipAcquired) return true
        return publishStorageFailure()
    }

    suspend fun discardLocalOwnership(expectedAccountId: String) {
        ownershipMutex.withLock {
            if (blockedAccountOwnerId == expectedAccountId) {
                blockedAccountOwnerId = null
                blockedOwnership = null
            }
        }
    }

    private suspend fun clearLocalOwnership(ownership: AccountPrivateDataPurgeOwnership) {
        ownershipMutex.withLock {
            if (blockedOwnership === ownership) {
                blockedAccountOwnerId = null
                blockedOwnership = null
            }
        }
    }

    fun publishAcquisitionCancelled() {
        publishReady()
    }

    fun publishAcquisitionTimeout() {
        publishStorageFailure()
    }

    suspend fun verifyCapturedAccount(expectedAccountId: String): Boolean {
        val currentState = runtime.authState.value
        if (
            currentState.isAuthenticated &&
            currentState.currentSession?.userId == expectedAccountId
        ) {
            return true
        }
        resumeAfterFailure(expectedAccountId, runtime.strings.authSessionExpired)
        return false
    }

    suspend fun resumeAfterFailure(expectedAccountId: String, errorMessage: String?) {
        resumeOwnedBlock(expectedAccountId)
        if (errorMessage == null) publishReady() else publishError(errorMessage)
    }

    private suspend fun resumeOwnedBlock(expectedAccountId: String) = withContext(NonCancellable) {
        val ownership = ownershipMutex.withLock {
            blockedOwnership.takeIf { blockedAccountOwnerId == expectedAccountId }
        } ?: return@withContext
        val resumed = dependencies.resumePrivateDataAfterAccountDeletionFailure(ownership)
        if (!resumed) return@withContext
        ownershipMutex.withLock {
            if (blockedAccountOwnerId == expectedAccountId && blockedOwnership === ownership) {
                blockedAccountOwnerId = null
                blockedOwnership = null
            }
        }
    }

    suspend fun confirmRemoteSuccess(expectedAccountId: String) = withContext(NonCancellable) {
        val ownership = ownershipMutex.withLock {
            blockedOwnership.takeIf { blockedAccountOwnerId == expectedAccountId }
        }
        val retained = ownership?.let { current ->
            dependencies.retainPrivateDataBlockAfterAccountDeletion(current)
        } ?: true
        if (!retained) return@withContext
        ownershipMutex.withLock {
            if (blockedAccountOwnerId == expectedAccountId && (ownership == null || blockedOwnership === ownership)) {
                blockedAccountOwnerId = null
                blockedOwnership = null
            }
        }
    }

    private fun publishStorageFailure(): Boolean {
        publishError(runtime.strings.settings.privacyPersistenceError)
        return false
    }
}
