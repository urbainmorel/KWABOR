package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationSyncRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class NotificationSyncWakeProcessor(
    private val context: NotificationSyncWakeContext,
) {
    suspend fun process(request: NotificationWakeRequest) {
        if (request.scope != context.currentScope()) return
        context.scheduler.cancel()
        val repository = context.repository ?: return
        val lease = context.acquire(request.scope) ?: return
        try {
            if (!retryIfRequested(repository, request, lease)) return
            if (!drain(repository, request.scope, lease)) return
            schedule(repository, request.scope, lease)
        } finally {
            context.release(lease)
        }
    }

    private suspend fun retryIfRequested(
        repository: NotificationSyncRepository,
        request: NotificationWakeRequest,
        lease: NotificationAccountOperationLease,
    ): Boolean {
        if (request.retryMode == NotificationWakeRetryMode.None) return true
        val result =
            repository.retryAccount(
                expectedScope = request.scope,
                includeManualFailures = request.retryMode == NotificationWakeRetryMode.Manual,
            )
        if (!context.isCurrent(lease, request.scope)) return false
        if (result is DomainResult.Failure) {
            context.publish(NotificationSyncSignalDraft.Failed(request.scope, result.error))
        }
        return result is DomainResult.Success
    }

    private suspend fun drain(
        repository: NotificationSyncRepository,
        scope: NotificationAccountScope,
        lease: NotificationAccountOperationLease,
    ): Boolean {
        val result = withContext(NonCancellable) { repository.drainDue(scope) }
        if (!context.isCurrent(lease, scope)) return false
        when (result) {
            is DomainResult.Failure -> context.publish(NotificationSyncSignalDraft.Failed(scope, result.error))
            is DomainResult.Success -> context.publish(NotificationSyncSignalDraft.Reconcile(scope, result.value))
        }
        return result is DomainResult.Success
    }

    private suspend fun schedule(
        repository: NotificationSyncRepository,
        scope: NotificationAccountScope,
        lease: NotificationAccountOperationLease,
    ) {
        val result = repository.nextAttemptAt(scope)
        if (!context.isCurrent(lease, scope)) return
        when (result) {
            is DomainResult.Failure -> context.publish(NotificationSyncSignalDraft.Failed(scope, result.error))
            is DomainResult.Success ->
                result.value?.let { nextAttemptAt ->
                    context.scheduler.install(scope, nextAttemptAt)
                }
        }
    }
}

internal class NotificationSyncWakeContext(
    val repository: NotificationSyncRepository?,
    val acquire: suspend (NotificationAccountScope) -> NotificationAccountOperationLease?,
    val release: suspend (NotificationAccountOperationLease) -> Unit,
    val isCurrent: suspend (NotificationAccountOperationLease, NotificationAccountScope) -> Boolean,
    val currentScope: () -> NotificationAccountScope?,
    val scheduler: NotificationWakeScheduler,
    val publish: (NotificationSyncSignalDraft) -> Unit,
)

internal sealed interface NotificationSyncSignalDraft {
    val scope: NotificationAccountScope

    data class Reconcile(
        override val scope: NotificationAccountScope,
        val outcome: com.kwabor.shared.domain.notification.NotificationDrainOutcome,
    ) : NotificationSyncSignalDraft

    data class Failed(
        override val scope: NotificationAccountScope,
        val error: com.kwabor.shared.domain.core.DomainError,
    ) : NotificationSyncSignalDraft
}
