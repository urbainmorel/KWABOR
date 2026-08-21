package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationOfflineRepository
import com.kwabor.shared.domain.notification.NotificationPreferences
import com.kwabor.shared.domain.notification.NotificationPreferencesRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal class NotificationRuntimePreferencesController(
    private val context: NotificationRuntimeContext,
    private val repository: NotificationPreferencesRepository,
    private val offlineRepository: NotificationOfflineRepository?,
    private val persistence: NotificationRuntimePersistence,
    private val publisher: NotificationRuntimePublisher,
) {
    suspend fun startLoad(scope: NotificationAccountScope) {
        val job =
            context.lifecycleMutex.withLock {
                if (!context.session.isCurrent(scope) || !context.session.preferencesVisible) return@withLock null
                context.session.preferencesJob?.cancel()
                val token = NotificationPreferencesOperationToken()
                context.session.preferencesToken = token
                context.stateStore.update(scope) { current ->
                    current.copy(
                        preferences =
                            current.preferences.copy(
                                isLoading = current.preferences.entries.isEmpty(),
                                message = null,
                            ),
                    )
                }
                context.session.viewerCoroutineScope.launch(start = CoroutineStart.LAZY) {
                    performLoad(scope, token)
                }.also { created -> context.session.preferencesJob = created }
            }
        job?.start()
    }

    private suspend fun performLoad(
        scope: NotificationAccountScope,
        token: NotificationPreferencesOperationToken,
    ) {
        val lease = context.syncCoordinator.beginOperation(scope) ?: return clearJob(scope, token)
        try {
            val local = loadLocal(scope)
            commitLocal(scope, token, lease, local)
            val network = repository.getPreferences(scope)
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return
            val preferences = (network as? DomainResult.Success)?.value
            if (preferences == null) {
                publishNetworkFailure(scope, token, lease, local, network.failureOrNull())
                return
            }
            val persisted = persistence.replacePreferences(scope, preferences)
            val confirmed = (persisted as? DomainResult.Success)?.value?.preferences ?: preferences
            commitNetwork(
                scope,
                token,
                lease,
                confirmed,
                local.localStorageUnavailable || persisted.isLocalStorageFailure(),
            )
        } finally {
            context.syncCoordinator.endOperation(lease)
            clearJob(scope, token)
        }
    }

    private suspend fun loadLocal(scope: NotificationAccountScope): NotificationLocalPreferences {
        val availableOffline =
            offlineRepository
                ?: return NotificationLocalPreferences(
                    NotificationPreferences.disabled(),
                    NotificationPendingProjection(emptyList()),
                    true,
                )
        return coroutineScope {
            val preferences = async { availableOffline.readPreferences(scope) }
            val pending = async { context.syncCoordinator.loadPending(scope) }
            val preferencesResult = preferences.await()
            val pendingResult = pending.await()
            val cachedPreferences = (preferencesResult as? DomainResult.Success)?.value
            val cacheTargetsAnotherAccount =
                cachedPreferences != null && cachedPreferences.accountId != scope.accountId
            NotificationLocalPreferences(
                preferences =
                    cachedPreferences
                        ?.takeIf { cached -> cached.accountId == scope.accountId }
                        ?.preferences
                        ?: NotificationPreferences.disabled(),
                pending =
                    NotificationPendingProjection(
                        (pendingResult as? DomainResult.Success)
                            ?.value
                            .orEmpty()
                            .filter { operation -> operation.command.scope == scope },
                    ),
                localStorageUnavailable =
                    preferencesResult.isLocalStorageFailure() ||
                        pendingResult.isLocalStorageFailure() ||
                        cacheTargetsAnotherAccount,
            )
        }
    }

    private suspend fun commitLocal(
        scope: NotificationAccountScope,
        token: NotificationPreferencesOperationToken,
        lease: NotificationAccountOperationLease,
        local: NotificationLocalPreferences,
    ) {
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock
            if (!context.session.accepts(scope, token)) return@withLock
            context.session.preferences = local.preferences
            context.session.pending = local.pending
            publisher.publishPreferences(scope, false, false, local.localStorageUnavailable, null)
        }
    }

    private suspend fun publishNetworkFailure(
        scope: NotificationAccountScope,
        token: NotificationPreferencesOperationToken,
        lease: NotificationAccountOperationLease,
        local: NotificationLocalPreferences,
        error: DomainError?,
    ) {
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock
            if (!context.session.accepts(scope, token)) return@withLock
            publisher.publishPreferences(
                scope,
                false,
                error is DomainError.NetworkUnavailable,
                local.localStorageUnavailable,
                frenchNotificationStrings.errors.loadFailed,
            )
        }
    }

    private suspend fun commitNetwork(
        scope: NotificationAccountScope,
        token: NotificationPreferencesOperationToken,
        lease: NotificationAccountOperationLease,
        preferences: NotificationPreferences,
        localStorageUnavailable: Boolean,
    ) {
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock
            if (!context.session.accepts(scope, token)) return@withLock
            context.session.preferences = preferences
            publisher.publishPreferences(scope, false, false, localStorageUnavailable, null)
        }
    }

    private suspend fun clearJob(
        scope: NotificationAccountScope,
        token: NotificationPreferencesOperationToken,
    ) {
        context.lifecycleMutex.withLock {
            if (context.session.accepts(scope, token)) context.session.preferencesJob = null
        }
    }
}
