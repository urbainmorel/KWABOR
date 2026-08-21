package com.kwabor.shared.data.notification

import androidx.sqlite.SQLiteException
import com.kwabor.shared.data.local.NotificationOutboxCapacityExceededException
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.ActiveNotificationAccountProvider
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationDrainOutcome
import com.kwabor.shared.domain.notification.NotificationPendingSyncStatus
import com.kwabor.shared.domain.notification.NotificationSubmitOutcome
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.NotificationSyncConfirmation
import com.kwabor.shared.domain.notification.NotificationSyncOperationOutcome
import com.kwabor.shared.domain.notification.NotificationSyncRepository
import com.kwabor.shared.domain.notification.PendingNotificationSync
import kotlinx.coroutines.CancellationException

class DataNotificationSyncRepository internal constructor(
    private val outboxStore: NotificationOutboxStore,
    private val settlementStore: NotificationOutboxSettlementStore,
    private val drainSingleFlight: NotificationDrainSingleFlight,
    dependencies: NotificationSyncDependencies,
    private val clockProvider: ClockProvider,
) : NotificationSyncRepository {
    private val activeAccountProvider = dependencies.activeAccountProvider
    private val operationProcessor = NotificationSyncOperationProcessor(
        settlementStore = settlementStore,
        inboxRepository = dependencies.inboxRepository,
        preferencesRepository = dependencies.preferencesRepository,
        inboxStore = dependencies.inboxStore,
        preferencesStore = dependencies.preferencesStore,
        activeAccountProvider = dependencies.activeAccountProvider,
    )

    override suspend fun submit(command: NotificationSyncCommand): DomainResult<NotificationSubmitOutcome> =
        runNotificationSyncCall {
            requireDurableOutbox()
            val scope = command.scope.toCanonicalScope()
            activeAccountProvider.requireExactScope(scope)
            val now = clockProvider.requireNotificationTime()
            val stored = enqueue(command.withScope(scope), now)
            activeAccountProvider.requireExactScope(scope)
            val pending = stored.toDomain(scope).rearmIfPaused(now)
            if (pending.command != command.withScope(scope)) {
                NotificationSubmitOutcome.Superseded(command.withScope(scope), pending.operationId)
            } else {
                NotificationSubmitOutcome.Queued(command.withScope(scope), pending)
            }
        }

    override suspend fun loadPending(
        expectedScope: NotificationAccountScope,
    ): DomainResult<List<PendingNotificationSync>> = runNotificationSyncCall {
        requireDurableOutbox()
        val scope = expectedScope.toCanonicalScope()
        activeAccountProvider.requireExactScope(scope)
        val pending = outboxStore.listOperations(
            accountId = scope.accountId,
            limit = DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS,
        ).map { operation -> operation.toDomain(scope) }
        activeAccountProvider.requireExactScope(scope)
        pending
    }

    override suspend fun drainDue(
        expectedScope: NotificationAccountScope,
    ): DomainResult<NotificationDrainOutcome> = runNotificationSyncCall {
        requireDurableOutbox()
        val scope = expectedScope.toCanonicalScope()
        activeAccountProvider.requireExactScope(scope)
        drainSingleFlight.execute(scope) {
            drainOnce(scope)
        }
    }

    override suspend fun nextAttemptAt(expectedScope: NotificationAccountScope): DomainResult<Long?> =
        runNotificationSyncCall {
            requireDurableOutbox()
            val scope = expectedScope.toCanonicalScope()
            activeAccountProvider.requireExactScope(scope)
            val next = outboxStore.nextAttemptAt(scope.accountId)
            activeAccountProvider.requireExactScope(scope)
            next
        }

    override suspend fun retryAccount(
        expectedScope: NotificationAccountScope,
        includeManualFailures: Boolean,
    ): DomainResult<Int> = runNotificationSyncCall {
        requireDurableOutbox()
        val scope = expectedScope.toCanonicalScope()
        activeAccountProvider.requireExactScope(scope)
        val now = clockProvider.requireNotificationTime()
        var rearmed = rearmPaused(scope, NOTIFICATION_TERMINAL_SESSION, now)
        if (includeManualFailures) rearmed += rearmPaused(scope, NOTIFICATION_TERMINAL_MANUAL, now)
        activeAccountProvider.requireExactScope(scope)
        rearmed
    }

    private suspend fun PendingNotificationSync.rearmIfPaused(now: Long): PendingNotificationSync {
        val paused = status as? NotificationPendingSyncStatus.Paused ?: return this
        activeAccountProvider.requireExactScope(command.scope)
        val rearmed = settlementStore.rearmOperation(
            accountId = command.scope.accountId,
            operationId = operationId,
            expectedTerminalErrorCode = paused.errorCode,
            rearmedAtEpochMilliseconds = now,
        )
        activeAccountProvider.requireExactScope(command.scope)
        if (!rearmed) return this
        return copy(
            enqueuedAtEpochMilliseconds = now,
            attemptCount = 0,
            status = NotificationPendingSyncStatus.Scheduled(now),
        )
    }

    private suspend fun rearmPaused(scope: NotificationAccountScope, errorCode: String, now: Long): Int {
        var totalRearmed = 0
        while (true) {
            activeAccountProvider.requireExactScope(scope)
            val paused = outboxStore.listPausedOperations(scope.accountId, errorCode, NOTIFICATION_DRAIN_LIMIT)
            if (paused.isEmpty()) return totalRearmed
            var batchRearmed = 0
            paused.forEach { operation ->
                activeAccountProvider.requireExactScope(scope)
                if (settlementStore.rearmOperation(scope.accountId, operation.operationId, errorCode, now)) {
                    batchRearmed += 1
                }
            }
            totalRearmed += batchRearmed
            if (batchRearmed == 0 || paused.size < NOTIFICATION_DRAIN_LIMIT) return totalRearmed
        }
    }

    private suspend fun drainOnce(scope: NotificationAccountScope): NotificationDrainOutcome {
        activeAccountProvider.requireExactScope(scope)
        val now = clockProvider.requireNotificationTime()
        val ready = outboxStore.listReadyOperations(scope.accountId, now, NOTIFICATION_DRAIN_LIMIT)
        activeAccountProvider.requireExactScope(scope)
        val outcomes = mutableListOf<NotificationSyncOperationOutcome>()
        for (operation in ready) {
            val outcome = operationProcessor.process(scope, operation, now)
            outcomes += outcome
            if (outcome.stopsDrain()) break
        }
        activeAccountProvider.requireExactScope(scope)
        return NotificationDrainOutcome(scope, outcomes.toList())
    }

    private suspend fun enqueue(command: NotificationSyncCommand, now: Long): NotificationSyncOperation =
        when (command) {
            is NotificationSyncCommand.AdvanceSeenThrough -> outboxStore.enqueueAdvanceSeenThrough(
                command.scope.accountId,
                command.throughSequence,
                now,
            )
            is NotificationSyncCommand.MarkRead -> outboxStore.enqueueMarkRead(
                command.scope.accountId,
                command.notificationId,
                now,
            )
            is NotificationSyncCommand.MarkAllReadThrough -> outboxStore.enqueueMarkAllReadThrough(
                command.scope.accountId,
                command.throughSequence,
                now,
            )
            is NotificationSyncCommand.Hide -> outboxStore.enqueueHide(
                command.scope.accountId,
                command.notificationId,
                now,
            )
            is NotificationSyncCommand.SetFamilyEnabled -> outboxStore.enqueueSetFamilyEnabled(
                command.scope.accountId,
                command.family,
                command.enabled,
                now,
            )
        }

    private fun requireDurableOutbox() {
        if (!outboxStore.isDurable || !settlementStore.isDurable) throw NotificationStorageUnavailableException()
    }
}

internal data class NotificationSyncDependencies(
    val inboxRepository: com.kwabor.shared.domain.notification.NotificationInboxRepository,
    val preferencesRepository: com.kwabor.shared.domain.notification.NotificationPreferencesRepository,
    val inboxStore: NotificationInboxStore,
    val preferencesStore: NotificationPreferencesStore,
    val activeAccountProvider: ActiveNotificationAccountProvider,
)

internal fun NotificationSyncOperation.toDomain(scope: NotificationAccountScope): PendingNotificationSync =
    PendingNotificationSync(
        operationId = operationId,
        command = toCommand(scope),
        enqueuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
        attemptCount = attemptCount,
        status = terminalErrorCode?.let(NotificationPendingSyncStatus::Paused)
            ?: NotificationPendingSyncStatus.Scheduled(nextAttemptAtEpochMilliseconds),
    )

internal fun NotificationSyncOperation.toCommand(
    scope: NotificationAccountScope,
): NotificationSyncCommand = when (kind) {
    NotificationSyncOperationKind.AdvanceSeenThrough -> NotificationSyncCommand.AdvanceSeenThrough(
        scope,
        requireNotNull(throughSequence),
    )
    NotificationSyncOperationKind.MarkRead -> NotificationSyncCommand.MarkRead(scope, requireNotNull(notificationId))
    NotificationSyncOperationKind.MarkAllReadThrough -> NotificationSyncCommand.MarkAllReadThrough(
        scope,
        requireNotNull(throughSequence),
    )
    NotificationSyncOperationKind.Hide -> NotificationSyncCommand.Hide(scope, requireNotNull(notificationId))
    NotificationSyncOperationKind.SetFamilyEnabled -> NotificationSyncCommand.SetFamilyEnabled(
        scope,
        requireNotNull(family),
        requireNotNull(desiredEnabled),
    )
}

private fun NotificationSyncCommand.withScope(scope: NotificationAccountScope): NotificationSyncCommand = when (this) {
    is NotificationSyncCommand.AdvanceSeenThrough -> copy(scope = scope)
    is NotificationSyncCommand.MarkRead -> copy(scope = scope)
    is NotificationSyncCommand.MarkAllReadThrough -> copy(scope = scope)
    is NotificationSyncCommand.Hide -> copy(scope = scope)
    is NotificationSyncCommand.SetFamilyEnabled -> copy(scope = scope)
}

private fun NotificationSyncOperationOutcome.stopsDrain(): Boolean = when (this) {
    is NotificationSyncOperationOutcome.Confirmed,
    is NotificationSyncOperationOutcome.Superseded,
    -> false
    is NotificationSyncOperationOutcome.Retrying,
    is NotificationSyncOperationOutcome.Paused,
    -> true
}

private fun ClockProvider.requireNotificationTime(): Long = nowEpochMilliseconds().also { now ->
    if (now < 0L) throw NotificationSyncDataException.Unexpected
}

private suspend inline fun <T> runNotificationSyncCall(crossinline block: suspend () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: CancellationException) {
    throw exception
} catch (exception: NotificationPersistenceException) {
    DomainResult.Failure(exception.domainError)
} catch (_: NotificationSyncDataException.Unexpected) {
    DomainResult.Failure(DomainError.Unexpected())
} catch (_: NotificationStorageUnavailableException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable())
} catch (_: NotificationOutboxCapacityExceededException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable("error.notifications.outbox_full"))
} catch (_: SQLiteException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable())
} catch (_: IllegalArgumentException) {
    DomainResult.Failure(DomainError.Validation("error.notifications.invalid_request"))
} catch (_: IllegalStateException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable())
}

internal sealed class NotificationSyncDataException : RuntimeException() {
    data object Unexpected : NotificationSyncDataException()
}

private const val NOTIFICATION_DRAIN_LIMIT = 50
internal const val NOTIFICATION_TERMINAL_SESSION = "session"
internal const val NOTIFICATION_TERMINAL_MANUAL = "manual"
