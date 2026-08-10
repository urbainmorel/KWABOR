package com.kwabor.android.presentation.auth

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

    fun captureAuthenticatedAccountId(): String? = runtime.authState.value
        .takeIf { state -> state.isAuthenticated }
        ?.currentSession
        ?.userId

    fun acceptNonAcquiredPurgeResult(result: AccountDeletionPurgeWorkerResult): Boolean = when (result) {
        AccountDeletionPurgeWorkerResult.Failed -> publishStorageFailure()
        AccountDeletionPurgeWorkerResult.AlreadyBlocked -> {
            publishError(runtime.strings.authAccountDeletionOutcomeUnknown)
            false
        }
        AccountDeletionPurgeWorkerResult.Acquired -> false
    }

    suspend fun claimLocalOwnership(expectedAccountId: String): Boolean {
        val ownershipAcquired = ownershipMutex.withLock {
            val currentOwnerId = blockedAccountOwnerId
            if (currentOwnerId == null || currentOwnerId == expectedAccountId) {
                blockedAccountOwnerId = expectedAccountId
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
            if (blockedAccountOwnerId == expectedAccountId) blockedAccountOwnerId = null
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
        val ownsBlock = ownershipMutex.withLock {
            blockedAccountOwnerId == expectedAccountId
        }
        if (!ownsBlock) return@withContext
        dependencies.resumeInteractionsAfterAccountDeletionFailure(expectedAccountId)
        ownershipMutex.withLock {
            if (blockedAccountOwnerId == expectedAccountId) blockedAccountOwnerId = null
        }
    }

    suspend fun confirmRemoteSuccess(expectedAccountId: String) = withContext(NonCancellable) {
        ownershipMutex.withLock {
            if (blockedAccountOwnerId == expectedAccountId) blockedAccountOwnerId = null
        }
    }

    private fun publishStorageFailure(): Boolean {
        publishError(runtime.strings.settings.privacyPersistenceError)
        return false
    }
}
