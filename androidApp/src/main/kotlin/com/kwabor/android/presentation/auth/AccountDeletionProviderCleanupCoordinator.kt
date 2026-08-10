package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.AccountDeletionProviderCleanupStore
import com.kwabor.android.auth.GoogleIdentityProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AccountDeletionProviderCleanupCoordinator(
    private val store: AccountDeletionProviderCleanupStore,
    private val googleIdentityProvider: GoogleIdentityProvider,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val operationMutex = Mutex()
    private val pendingState = MutableStateFlow(true)

    val pending: StateFlow<Boolean> = pendingState.asStateFlow()

    suspend fun armBeforeRemoteBoundary(): Boolean = withContext(ioDispatcher) {
        operationMutex.withLock {
            val marked = runCatching { store.markPending() }.getOrDefault(false)
            val pending = refreshPending()
            marked && pending
        }
    }

    suspend fun clearAfterResolvedPreTransport(): Boolean = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (!refreshPending()) return@withLock true
            val cleared = runCatching { store.clear() }.getOrDefault(false)
            val pending = refreshPending()
            cleared && !pending
        }
    }

    suspend fun clearAfterRemoteBoundary(): Boolean = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (!refreshPending()) return@withLock true
            if (!clearGoogleCredentialState()) return@withLock false
            val cleared = runCatching { store.clear() }.getOrDefault(false)
            val pending = refreshPending()
            cleared && !pending
        }
    }

    private fun refreshPending(): Boolean = readPendingFailClosed().also { isPending ->
        pendingState.value = isPending
    }

    private fun readPendingFailClosed(): Boolean = runCatching {
        store.hasPendingCleanup()
    }.getOrDefault(true)

    private suspend fun clearGoogleCredentialState(): Boolean = withTimeoutOrNull(PROVIDER_CLEAR_TIMEOUT_MILLIS) {
        try {
            googleIdentityProvider.clearCredentialState()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    } ?: false
}

private const val PROVIDER_CLEAR_TIMEOUT_MILLIS = 10_000L
