package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationInboxRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal class NotificationRuntimeStatusController(
    private val context: NotificationRuntimeContext,
    private val inboxRepository: NotificationInboxRepository,
    private val pageController: NotificationRuntimePageController,
    private val persistence: NotificationRuntimePersistence,
    private val publisher: NotificationRuntimePublisher,
) {
    suspend fun startStatusLoad(scope: NotificationAccountScope) {
        val job =
            context.lifecycleMutex.withLock {
                if (!context.session.isCurrent(scope)) return@withLock null
                context.session.statusJob?.cancel()
                val token = NotificationStatusOperationToken()
                context.session.statusToken = token
                context.session.viewerCoroutineScope.launch(start = CoroutineStart.LAZY) {
                    performStatusLoad(scope, token)
                }.also { created -> context.session.statusJob = created }
            }
        job?.start()
    }

    private suspend fun performStatusLoad(
        scope: NotificationAccountScope,
        token: NotificationStatusOperationToken,
    ) {
        val lease = context.syncCoordinator.beginOperation(scope) ?: return clearStatusJob(scope, token)
        try {
            val local = pageController.loadLocalProjection(scope)
            commitLocalStatus(scope, token, lease, local)
            val statusResult = inboxRepository.getStatus(scope)
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return
            val status = (statusResult as? DomainResult.Success)?.value
            if (status == null) {
                publishNetworkFailure(scope, token, lease, statusResult.failureOrNull())
                return
            }
            val persisted = persistence.storeStatus(scope, status)
            val persistedInbox = (persisted as? DomainResult.Success)?.value?.toRuntimeInbox()
            context.lifecycleMutex.withLock {
                if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock
                if (!context.session.accepts(scope, token)) return@withLock
                context.session.inbox = persistedInbox ?: context.session.inbox?.copy(
                    status = context.session.inbox?.status?.mergeMonotone(status) ?: status,
                ) ?: NotificationRuntimeInbox(scope.accountId, status.latestSequence, null, status, emptyList())
                publisher.publishBadge(scope)
            }
        } finally {
            context.syncCoordinator.endOperation(lease)
            clearStatusJob(scope, token)
        }
    }

    private suspend fun commitLocalStatus(
        scope: NotificationAccountScope,
        token: NotificationStatusOperationToken,
        lease: NotificationAccountOperationLease,
        local: NotificationLocalProjection,
    ) {
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock
            if (!context.session.accepts(scope, token)) return@withLock
            context.session.pending = local.pending
            local.inbox?.let { inbox -> context.session.inbox = inbox }
            publisher.publishBadge(scope)
        }
    }

    private suspend fun publishNetworkFailure(
        scope: NotificationAccountScope,
        token: NotificationStatusOperationToken,
        lease: NotificationAccountOperationLease,
        error: DomainError?,
    ) {
        context.lifecycleMutex.withLock {
            if (!context.syncCoordinator.isOperationLeaseCurrent(lease, scope)) return@withLock
            if (context.session.accepts(scope, token) && error is DomainError.NetworkUnavailable) {
                context.stateStore.update(scope) { current ->
                    current.copy(page = current.page.copy(isOffline = true))
                }
            }
        }
    }

    private suspend fun clearStatusJob(
        scope: NotificationAccountScope,
        token: NotificationStatusOperationToken,
    ) {
        context.lifecycleMutex.withLock {
            if (context.session.accepts(scope, token)) context.session.statusJob = null
        }
    }
}
