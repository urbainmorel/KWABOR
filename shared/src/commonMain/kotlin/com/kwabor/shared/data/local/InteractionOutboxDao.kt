package com.kwabor.shared.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal abstract class InteractionOutboxDao : InteractionOutboxReader {
    @Query(
        """
        SELECT *
        FROM interaction_outbox_operations
        WHERE account_id = :accountId
          AND listing_id = :listingId
          AND kind = :kind
        """,
    )
    abstract suspend fun findByKey(accountId: String, listingId: String, kind: String): InteractionOutboxEntity?

    @Query(
        """
        SELECT *
        FROM interaction_outbox_operations
        WHERE account_id = :accountId
        ORDER BY enqueued_at_epoch_milliseconds ASC, operation_id ASC
        LIMIT :limit
        """,
    )
    abstract override suspend fun findForAccount(accountId: String, limit: Int): List<InteractionOutboxEntity>

    @Query(
        """
        SELECT *
        FROM interaction_outbox_operations
        WHERE account_id = :accountId
          AND listing_id IN (:listingIds)
        ORDER BY listing_id ASC, kind ASC, operation_id ASC
        """,
    )
    abstract override suspend fun findForAccountAndListingIds(
        accountId: String,
        listingIds: List<String>,
    ): List<InteractionOutboxEntity>

    @Query(
        """
        SELECT *
        FROM interaction_outbox_operations
        WHERE account_id = :accountId
          AND terminal_error_code IS NULL
          AND next_attempt_at_epoch_milliseconds <= :readyAtEpochMilliseconds
        ORDER BY next_attempt_at_epoch_milliseconds ASC,
                 enqueued_at_epoch_milliseconds ASC,
                 operation_id ASC
        LIMIT :limit
        """,
    )
    abstract override suspend fun findReadyForAccount(
        accountId: String,
        readyAtEpochMilliseconds: Long,
        limit: Int,
    ): List<InteractionOutboxEntity>

    @Query(
        """
        SELECT *
        FROM interaction_outbox_operations
        WHERE account_id = :accountId
          AND terminal_error_code IS NULL
        ORDER BY next_attempt_at_epoch_milliseconds ASC,
                 enqueued_at_epoch_milliseconds ASC,
                 operation_id ASC
        LIMIT 1
        """,
    )
    abstract override suspend fun findNextScheduledForAccount(accountId: String): InteractionOutboxEntity?

    @Query(
        """
        SELECT *
        FROM interaction_outbox_operations
        WHERE account_id = :accountId
          AND terminal_error_code IS NOT NULL
          AND (:terminalErrorCode IS NULL OR terminal_error_code = :terminalErrorCode)
        ORDER BY enqueued_at_epoch_milliseconds ASC, operation_id ASC
        LIMIT :limit
        """,
    )
    abstract override suspend fun findPausedForAccount(
        accountId: String,
        terminalErrorCode: String?,
        limit: Int,
    ): List<InteractionOutboxEntity>

    @Query("SELECT COUNT(*) FROM interaction_outbox_operations WHERE account_id = :accountId")
    abstract suspend fun countForAccount(accountId: String): Int

    @Insert
    abstract suspend fun insert(operation: InteractionOutboxEntity): Long

    @Query(
        """
        DELETE FROM interaction_outbox_operations
        WHERE operation_id = :operationId
          AND (
              :expectedAttemptCount IS NULL
              OR (
                  attempt_count = :expectedAttemptCount
                  AND (
                      (:expectedTerminalErrorCode IS NULL AND terminal_error_code IS NULL)
                      OR terminal_error_code = :expectedTerminalErrorCode
                  )
              )
          )
        """,
    )
    abstract suspend fun deleteByOperationId(
        operationId: Long,
        expectedAttemptCount: Long? = null,
        expectedTerminalErrorCode: String? = null,
    ): Int

    @Query(
        """
        UPDATE interaction_outbox_operations
        SET attempt_count = attempt_count + 1,
            next_attempt_at_epoch_milliseconds = :nextAttemptAtEpochMilliseconds
        WHERE operation_id = :operationId
          AND attempt_count = :expectedAttemptCount
          AND terminal_error_code IS NULL
        """,
    )
    abstract suspend fun recordRetry(
        operationId: Long,
        expectedAttemptCount: Int,
        nextAttemptAtEpochMilliseconds: Long,
    ): Int

    @Query(
        """
        UPDATE interaction_outbox_operations
        SET attempt_count = attempt_count + 1,
            terminal_error_code = :terminalErrorCode
        WHERE operation_id = :operationId
          AND attempt_count = :expectedAttemptCount
          AND terminal_error_code IS NULL
        """,
    )
    abstract suspend fun recordTerminalFailure(
        operationId: Long,
        expectedAttemptCount: Int,
        terminalErrorCode: String,
    ): Int

    @Query(
        """
        UPDATE interaction_outbox_operations
        SET enqueued_at_epoch_milliseconds = :rearmedAtEpochMilliseconds,
            attempt_count = 0,
            next_attempt_at_epoch_milliseconds = :rearmedAtEpochMilliseconds,
            terminal_error_code = NULL
        WHERE operation_id = :operationId
          AND desired_selected = :expectedDesiredSelected
        """,
    )
    abstract suspend fun rearm(operationId: Long, expectedDesiredSelected: Long, rearmedAtEpochMilliseconds: Long): Int

    @Transaction
    open suspend fun enqueueCoalesced(
        operation: InteractionOutboxEntity,
        maxOperationCount: Int,
        garbageCollectedTerminalErrorCodes: List<String>,
    ): InteractionOutboxEntity {
        findPausedForAccount(
            accountId = operation.accountId,
            terminalErrorCode = null,
            limit = maxOperationCount,
        ).forEach { candidate ->
            val terminalErrorCode = candidate.terminalErrorCode ?: return@forEach
            if (terminalErrorCode in garbageCollectedTerminalErrorCodes) {
                deleteByOperationId(
                    operationId = candidate.operationId,
                    expectedAttemptCount = candidate.attemptCount,
                    expectedTerminalErrorCode = terminalErrorCode,
                )
            }
        }
        val current = findByKey(
            accountId = operation.accountId,
            listingId = operation.listingId,
            kind = operation.kind,
        )
        if (current?.desiredSelectedRaw == operation.desiredSelectedRaw) {
            return current
        }
        if (current == null && countForAccount(operation.accountId) >= maxOperationCount) {
            throw InteractionOutboxCapacityExceededException(maxOperationCount)
        }
        if (current != null) {
            deleteByOperationId(current.operationId)
        }
        return operation.copy(operationId = insert(operation))
    }
}

internal interface InteractionOutboxReader {
    suspend fun findForAccount(accountId: String, limit: Int): List<InteractionOutboxEntity>

    suspend fun findForAccountAndListingIds(accountId: String, listingIds: List<String>): List<InteractionOutboxEntity>

    suspend fun findReadyForAccount(
        accountId: String,
        readyAtEpochMilliseconds: Long,
        limit: Int,
    ): List<InteractionOutboxEntity>

    suspend fun findNextScheduledForAccount(accountId: String): InteractionOutboxEntity?

    suspend fun findPausedForAccount(
        accountId: String,
        terminalErrorCode: String?,
        limit: Int,
    ): List<InteractionOutboxEntity>
}
