package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.ActiveNotificationAccountProvider
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationInboxRepository
import com.kwabor.shared.domain.notification.NotificationItemMutation
import com.kwabor.shared.domain.notification.NotificationPreferencesRepository
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.NotificationSyncConfirmation
import com.kwabor.shared.domain.notification.NotificationSyncOperationOutcome
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class NotificationSyncOperationProcessor(
    private val settlementStore: NotificationOutboxSettlementStore,
    private val inboxRepository: NotificationInboxRepository,
    private val preferencesRepository: NotificationPreferencesRepository,
    private val inboxStore: NotificationInboxStore,
    private val preferencesStore: NotificationPreferencesStore,
    private val activeAccountProvider: ActiveNotificationAccountProvider,
) {
    suspend fun process(
        scope: NotificationAccountScope,
        operation: NotificationSyncOperation,
        now: Long,
    ): NotificationSyncOperationOutcome {
        activeAccountProvider.requireExactScope(scope)
        val command = operation.toCommand(scope)
        val result = command.performRemote(inboxRepository, preferencesRepository)
        return withContext(NonCancellable) {
            when (result) {
                is DomainResult.Success -> settleSuccess(scope, operation, command, result.value, now)
                is DomainResult.Failure -> settleFailure(scope, operation, command, result.error, now)
            }
        }
    }

    private suspend fun settleSuccess(
        scope: NotificationAccountScope,
        operation: NotificationSyncOperation,
        command: NotificationSyncCommand,
        confirmation: NotificationSyncConfirmation,
        now: Long,
    ): NotificationSyncOperationOutcome {
        activeAccountProvider.requireExactScope(scope)
        val settlement = settleConfirmation(scope, operation, confirmation, now)
        activeAccountProvider.requireExactScope(scope)
        return when (settlement) {
            NotificationConfirmedOperationSettlement.Settled ->
                NotificationSyncOperationOutcome.Confirmed(command, operation.operationId, confirmation)
            NotificationConfirmedOperationSettlement.Superseded ->
                NotificationSyncOperationOutcome.Superseded(command, operation.operationId)
        }
    }

    private suspend fun settleFailure(
        scope: NotificationAccountScope,
        operation: NotificationSyncOperation,
        command: NotificationSyncCommand,
        error: DomainError,
        now: Long,
    ): NotificationSyncOperationOutcome = when (error) {
        is DomainError.NetworkUnavailable -> scheduleRetry(scope, operation, command, now)
        is DomainError.AuthenticationRequired -> pause(scope, operation, command, NOTIFICATION_TERMINAL_SESSION)
        is DomainError.Validation -> pause(scope, operation, command, NOTIFICATION_TERMINAL_VALIDATION)
        is DomainError.NotFound -> pause(scope, operation, command, NOTIFICATION_TERMINAL_NOT_FOUND)
        is DomainError.PermissionDenied -> pause(scope, operation, command, NOTIFICATION_TERMINAL_PERMISSION)
        is DomainError.LocalStorageUnavailable,
        is DomainError.Unexpected,
        -> pause(scope, operation, command, NOTIFICATION_TERMINAL_MANUAL)
    }

    private suspend fun scheduleRetry(
        scope: NotificationAccountScope,
        operation: NotificationSyncOperation,
        command: NotificationSyncCommand,
        now: Long,
    ): NotificationSyncOperationOutcome {
        activeAccountProvider.requireExactScope(scope)
        operation.attemptCount.requireNotificationRetryableAttemptCount()
        val nextAttempt = now.saturatingAdd(notificationRetryDelay(operation.attemptCount + 1))
        val updated = settlementStore.recordOperationRetry(
            accountId = scope.accountId,
            operationId = operation.operationId,
            expectedAttemptCount = operation.attemptCount,
            nextAttemptAtEpochMilliseconds = nextAttempt,
        )
        activeAccountProvider.requireExactScope(scope)
        return if (updated) {
            NotificationSyncOperationOutcome.Retrying(
                command,
                operation.copy(
                    attemptCount = operation.attemptCount + 1,
                    nextAttemptAtEpochMilliseconds = nextAttempt,
                ).toDomain(scope),
            )
        } else {
            NotificationSyncOperationOutcome.Superseded(command, operation.operationId)
        }
    }

    private suspend fun pause(
        scope: NotificationAccountScope,
        operation: NotificationSyncOperation,
        command: NotificationSyncCommand,
        terminalErrorCode: String,
    ): NotificationSyncOperationOutcome {
        activeAccountProvider.requireExactScope(scope)
        operation.attemptCount.requireNotificationRetryableAttemptCount()
        val updated = settlementStore.recordOperationTerminalFailure(
            accountId = scope.accountId,
            operationId = operation.operationId,
            expectedAttemptCount = operation.attemptCount,
            terminalErrorCode = terminalErrorCode,
        )
        activeAccountProvider.requireExactScope(scope)
        return if (updated) {
            NotificationSyncOperationOutcome.Paused(
                command,
                operation.copy(
                    attemptCount = operation.attemptCount + 1,
                    terminalErrorCode = terminalErrorCode,
                ).toDomain(scope),
            )
        } else {
            NotificationSyncOperationOutcome.Superseded(command, operation.operationId)
        }
    }

    private suspend fun settleConfirmation(
        scope: NotificationAccountScope,
        operation: NotificationSyncOperation,
        confirmation: NotificationSyncConfirmation,
        now: Long,
    ): NotificationConfirmedOperationSettlement = when (confirmation) {
        is NotificationSyncConfirmation.Status -> settlementStore.settleConfirmedStatus(
            accountId = scope.accountId,
            operationId = operation.operationId,
            expectedAttemptCount = operation.attemptCount,
        ) {
            activeAccountProvider.prepareInExactScope(scope) {
                inboxStore.prepareConfirmedStatusLocked(scope.accountId, confirmation.status, now)
            }
        }
        is NotificationSyncConfirmation.Item -> settlementStore.settleConfirmedItem(
            accountId = scope.accountId,
            operationId = operation.operationId,
            expectedAttemptCount = operation.attemptCount,
        ) {
            activeAccountProvider.prepareInExactScope(scope) {
                inboxStore.prepareConfirmedItemAndStatusLocked(
                    scope.accountId,
                    confirmation.mutation,
                    confirmation.status,
                    now,
                )
            }
        }
        is NotificationSyncConfirmation.MarkAllRead -> settlementStore.settleConfirmedMarkAllRead(
            accountId = scope.accountId,
            operationId = operation.operationId,
            expectedAttemptCount = operation.attemptCount,
        ) {
            activeAccountProvider.prepareInExactScope(scope) {
                inboxStore.prepareConfirmedMarkAllReadLocked(scope.accountId, confirmation.confirmation, now)
            }
        }
        is NotificationSyncConfirmation.Preference -> settlementStore.settleConfirmedPreferences(
            accountId = scope.accountId,
            operationId = operation.operationId,
            expectedAttemptCount = operation.attemptCount,
        ) {
            activeAccountProvider.prepareInExactScope(scope) {
                preferencesStore.prepareConfirmedPreferenceLocked(scope.accountId, confirmation.preference, now)
            }
        }
    }
}

private suspend fun <T> ActiveNotificationAccountProvider.prepareInExactScope(
    scope: NotificationAccountScope,
    prepare: suspend () -> T,
): T {
    requireExactScope(scope)
    val prepared = prepare()
    requireExactScope(scope)
    return prepared
}

private suspend fun NotificationSyncCommand.performRemote(
    inboxRepository: NotificationInboxRepository,
    preferencesRepository: NotificationPreferencesRepository,
): DomainResult<NotificationSyncConfirmation> = when (this) {
    is NotificationSyncCommand.AdvanceSeenThrough -> inboxRepository.markSeenThrough(scope, throughSequence)
        .mapNotificationConfirmation(NotificationSyncConfirmation::Status)
    is NotificationSyncCommand.MarkRead -> inboxRepository.markRead(scope, notificationId)
        .withAuthoritativeStatus(inboxRepository, scope)
    is NotificationSyncCommand.MarkAllReadThrough -> inboxRepository.markAllReadThrough(scope, throughSequence)
        .mapNotificationConfirmation(NotificationSyncConfirmation::MarkAllRead)
    is NotificationSyncCommand.Hide -> inboxRepository.hide(scope, notificationId)
        .withAuthoritativeStatus(inboxRepository, scope)
    is NotificationSyncCommand.SetFamilyEnabled -> preferencesRepository.setPreference(scope, family, enabled)
        .mapNotificationConfirmation(NotificationSyncConfirmation::Preference)
}

private suspend fun DomainResult<NotificationItemMutation>.withAuthoritativeStatus(
    inboxRepository: NotificationInboxRepository,
    scope: NotificationAccountScope,
): DomainResult<NotificationSyncConfirmation> = when (this) {
    is DomainResult.Failure -> this
    is DomainResult.Success -> when (val status = inboxRepository.getStatus(scope)) {
        is DomainResult.Failure -> status
        is DomainResult.Success -> DomainResult.Success(NotificationSyncConfirmation.Item(value, status.value))
    }
}

private fun <T> DomainResult<T>.mapNotificationConfirmation(
    transform: (T) -> NotificationSyncConfirmation,
): DomainResult<NotificationSyncConfirmation> = when (this) {
    is DomainResult.Success -> DomainResult.Success(transform(value))
    is DomainResult.Failure -> this
}

private fun notificationRetryDelay(attemptCount: Int): Long {
    val shift = (attemptCount - 1).coerceIn(0, NOTIFICATION_MAXIMUM_RETRY_SHIFT)
    return (NOTIFICATION_INITIAL_RETRY_MILLISECONDS shl shift).coerceAtMost(NOTIFICATION_MAXIMUM_RETRY_MILLISECONDS)
}

private fun Long.saturatingAdd(increment: Long): Long =
    if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

private const val NOTIFICATION_INITIAL_RETRY_MILLISECONDS = 1_000L
private const val NOTIFICATION_MAXIMUM_RETRY_MILLISECONDS = 300_000L
private const val NOTIFICATION_MAXIMUM_RETRY_SHIFT = 8
private const val NOTIFICATION_TERMINAL_VALIDATION = "validation"
private const val NOTIFICATION_TERMINAL_NOT_FOUND = "not_found"
private const val NOTIFICATION_TERMINAL_PERMISSION = "permission"
