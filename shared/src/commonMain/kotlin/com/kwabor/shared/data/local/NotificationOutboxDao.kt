package com.kwabor.shared.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal abstract class NotificationOutboxDao {
    @Query("SELECT * FROM notification_sync_operations WHERE account_id = :accountId AND logical_key = :logicalKey")
    abstract suspend fun findOperationByKey(accountId: String, logicalKey: String): NotificationSyncOperationEntity?

    @Query("SELECT * FROM notification_sync_operations WHERE account_id = :accountId AND operation_id = :operationId")
    abstract suspend fun findOperationById(accountId: String, operationId: Long): NotificationSyncOperationEntity?

    @Query(
        """
        SELECT * FROM notification_sync_operations
        WHERE account_id = :accountId
        ORDER BY enqueued_at_epoch_milliseconds ASC, operation_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun findOperations(accountId: String, limit: Int): List<NotificationSyncOperationEntity>

    @Query(
        """
        SELECT * FROM notification_sync_operations
        WHERE account_id = :accountId
          AND terminal_error_code IS NULL
          AND next_attempt_at_epoch_milliseconds <= :readyAtEpochMilliseconds
        ORDER BY next_attempt_at_epoch_milliseconds ASC,
                 enqueued_at_epoch_milliseconds ASC,
                 operation_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun findReadyOperations(
        accountId: String,
        readyAtEpochMilliseconds: Long,
        limit: Int,
    ): List<NotificationSyncOperationEntity>

    @Query(
        """
        SELECT * FROM notification_sync_operations
        WHERE account_id = :accountId
          AND terminal_error_code IS NOT NULL
          AND (:terminalErrorCode IS NULL OR terminal_error_code = :terminalErrorCode)
        ORDER BY enqueued_at_epoch_milliseconds ASC, operation_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun findPausedOperations(
        accountId: String,
        terminalErrorCode: String?,
        limit: Int,
    ): List<NotificationSyncOperationEntity>

    @Query(
        """
        SELECT * FROM notification_sync_operations
        WHERE account_id = :accountId
          AND terminal_error_code IS NULL
        ORDER BY next_attempt_at_epoch_milliseconds ASC,
                 enqueued_at_epoch_milliseconds ASC,
                 operation_id ASC
        LIMIT 1
        """,
    )
    abstract suspend fun findNextScheduledOperation(accountId: String): NotificationSyncOperationEntity?

    @Query("SELECT COUNT(*) FROM notification_sync_operations WHERE account_id = :accountId")
    protected abstract suspend fun countOperations(accountId: String): Int

    @Insert
    abstract suspend fun insertOperation(operation: NotificationSyncOperationEntity): Long

    @Query("DELETE FROM notification_sync_operations WHERE account_id = :accountId AND operation_id = :operationId")
    abstract suspend fun deleteOperation(accountId: String, operationId: Long): Int

    @Query(
        """
        DELETE FROM notification_sync_operations
        WHERE account_id = :accountId
          AND operation_id = :operationId
          AND attempt_count = :expectedAttemptCount
          AND ((:expectedTerminalErrorCode IS NULL AND terminal_error_code IS NULL)
               OR terminal_error_code = :expectedTerminalErrorCode)
        """,
    )
    abstract suspend fun deleteOperationIfMatches(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Long,
        expectedTerminalErrorCode: String?,
    ): Int

    @Query(
        """
        UPDATE notification_sync_operations
        SET attempt_count = attempt_count + 1,
            next_attempt_at_epoch_milliseconds = :nextAttemptAtEpochMilliseconds
        WHERE account_id = :accountId
          AND operation_id = :operationId
          AND attempt_count = :expectedAttemptCount
          AND terminal_error_code IS NULL
        """,
    )
    abstract suspend fun recordOperationRetry(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Long,
        nextAttemptAtEpochMilliseconds: Long,
    ): Int

    @Query(
        """
        UPDATE notification_sync_operations
        SET attempt_count = attempt_count + 1,
            terminal_error_code = :terminalErrorCode
        WHERE account_id = :accountId
          AND operation_id = :operationId
          AND attempt_count = :expectedAttemptCount
          AND terminal_error_code IS NULL
        """,
    )
    abstract suspend fun recordOperationTerminalFailure(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Long,
        terminalErrorCode: String,
    ): Int

    @Query(
        """
        UPDATE notification_sync_operations
        SET enqueued_at_epoch_milliseconds = :rearmedAtEpochMilliseconds,
            attempt_count = 0,
            next_attempt_at_epoch_milliseconds = :rearmedAtEpochMilliseconds,
            terminal_error_code = NULL
        WHERE account_id = :accountId
          AND operation_id = :operationId
          AND terminal_error_code = :expectedTerminalErrorCode
        """,
    )
    abstract suspend fun rearmOperation(
        accountId: String,
        operationId: Long,
        expectedTerminalErrorCode: String,
        rearmedAtEpochMilliseconds: Long,
    ): Int

    @Transaction
    open suspend fun enqueueCoalesced(
        operation: NotificationSyncOperationEntity,
        maxOperationCount: Int,
    ): NotificationSyncOperationEntity {
        val current = findOperationByKey(operation.accountId, operation.logicalKey)
        if (current != null && current.covers(operation)) return current
        if (current == null && countOperations(operation.accountId) >= maxOperationCount) {
            throw NotificationOutboxCapacityExceededException(maxOperationCount)
        }
        if (current != null) deleteOperation(current.accountId, current.operationId)
        return operation.copy(operationId = insertOperation(operation))
    }
}

internal class NotificationOutboxCapacityExceededException(maxOperationCount: Int) :
    IllegalStateException("Notification outbox capacity of $maxOperationCount operations was reached.")

private fun NotificationSyncOperationEntity.covers(candidate: NotificationSyncOperationEntity): Boolean {
    if (kind != candidate.kind) return false
    return when (kind) {
        "advance_seen_through", "mark_all_read_through" -> coversBoundary(candidate)
        "mark_read", "hide" -> notificationId != null && notificationId == candidate.notificationId
        "set_family_enabled" -> coversPreference(candidate)
        else -> false
    }
}

private fun NotificationSyncOperationEntity.coversBoundary(candidate: NotificationSyncOperationEntity): Boolean =
    listOf(
        throughSequence != null,
        candidate.throughSequence != null,
        throughSequence != null && candidate.throughSequence != null && throughSequence >= candidate.throughSequence,
    ).all { condition -> condition }

private fun NotificationSyncOperationEntity.coversPreference(candidate: NotificationSyncOperationEntity): Boolean =
    listOf(
        family != null,
        desiredEnabledRaw != null,
        family == candidate.family,
        desiredEnabledRaw == candidate.desiredEnabledRaw,
    ).all { condition -> condition }
