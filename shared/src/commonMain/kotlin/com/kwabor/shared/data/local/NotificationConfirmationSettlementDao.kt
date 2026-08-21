package com.kwabor.shared.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
internal abstract class NotificationConfirmationSettlementDao {
    @Query("SELECT * FROM notification_sync_operations WHERE account_id = :accountId AND operation_id = :operationId")
    protected abstract suspend fun findOperationById(
        accountId: String,
        operationId: Long,
    ): NotificationSyncOperationEntity?

    @Query(
        """
        DELETE FROM notification_sync_operations
        WHERE account_id = :accountId
          AND operation_id = :operationId
          AND attempt_count = :expectedAttemptCount
          AND terminal_error_code IS NULL
        """,
    )
    protected abstract suspend fun deleteOperationIfMatches(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSnapshot(snapshot: NotificationInboxSnapshotEntity)

    @Update(entity = NotificationInboxSnapshotEntity::class)
    protected abstract suspend fun updateStatus(status: NotificationInboxStatusUpdate): Int

    @Update
    protected abstract suspend fun updateItem(item: NotificationInboxItemEntity): Int

    @Query(
        """
        UPDATE notification_inbox_items
        SET seen_at_epoch_milliseconds = CASE
                WHEN seen_at_epoch_milliseconds IS NULL THEN CASE
                    WHEN created_at_epoch_milliseconds > :mutationAtEpochMilliseconds
                    THEN created_at_epoch_milliseconds
                    ELSE :mutationAtEpochMilliseconds
                  END
                ELSE seen_at_epoch_milliseconds
            END,
            read_at_epoch_milliseconds = CASE
                WHEN read_at_epoch_milliseconds IS NULL THEN CASE
                    WHEN created_at_epoch_milliseconds > :mutationAtEpochMilliseconds
                    THEN created_at_epoch_milliseconds
                    ELSE :mutationAtEpochMilliseconds
                  END
                ELSE read_at_epoch_milliseconds
            END
        WHERE account_id = :accountId
          AND sequence_number <= :throughSequence
          AND hidden_at_epoch_milliseconds IS NULL
        """,
    )
    protected abstract suspend fun markItemsReadThrough(
        accountId: String,
        throughSequence: Long,
        mutationAtEpochMilliseconds: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPreferences(preferences: List<NotificationPreferenceEntity>)

    @Query("DELETE FROM notification_preferences_cache WHERE account_id = :accountId")
    protected abstract suspend fun deletePreferences(accountId: String): Int

    @Transaction
    open suspend fun settleStatus(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Long,
        status: NotificationInboxStatusUpdate,
        snapshotWhenAbsent: NotificationInboxSnapshotEntity?,
    ): Boolean = settleIfCurrent(accountId, operationId, expectedAttemptCount) {
        require(status.accountId == accountId) { "Notification status settlement account differs from its debt." }
        require(snapshotWhenAbsent == null || snapshotWhenAbsent.accountId == accountId) {
            "Notification snapshot settlement account differs from its debt."
        }
        val updated = updateStatus(status)
        if (updated == 0) {
            checkNotNull(snapshotWhenAbsent) {
                "Notification status disappeared during its confirmed settlement."
            }
            insertSnapshot(snapshotWhenAbsent)
        } else {
            check(snapshotWhenAbsent == null) {
                "Notification status appeared during its serialized confirmed settlement."
            }
        }
    }

    @Transaction
    open suspend fun settleItemAndStatus(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Long,
        item: NotificationInboxItemEntity?,
        status: NotificationInboxStatusUpdate,
        snapshotWhenAbsent: NotificationInboxSnapshotEntity?,
    ): Boolean = settleIfCurrent(accountId, operationId, expectedAttemptCount) {
        require(item == null || item.accountId == accountId) {
            "Notification item settlement account differs from its debt."
        }
        require(status.accountId == accountId) { "Notification status settlement account differs from its debt." }
        require(snapshotWhenAbsent == null || snapshotWhenAbsent.accountId == accountId) {
            "Notification snapshot settlement account differs from its debt."
        }
        if (item != null) {
            check(updateItem(item) == 1) {
                "Notification item disappeared during its confirmed settlement."
            }
        }
        val updated = updateStatus(status)
        if (updated == 0) {
            check(item == null) {
                "Notification snapshot disappeared after preparing a cached item confirmation."
            }
            checkNotNull(snapshotWhenAbsent) {
                "Notification status disappeared during its confirmed item settlement."
            }
            insertSnapshot(snapshotWhenAbsent)
        } else {
            check(snapshotWhenAbsent == null) {
                "Notification status appeared during its serialized confirmed item settlement."
            }
        }
    }

    @Transaction
    open suspend fun settleMarkAllRead(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Long,
        settlement: NotificationMarkAllReadSettlementRecord,
    ): Boolean = settleIfCurrent(accountId, operationId, expectedAttemptCount) {
        require(settlement.snapshot.accountId == accountId) {
            "Notification mark-all settlement account differs from its debt."
        }
        if (settlement.snapshotWasPresent) {
            markItemsReadThrough(
                accountId,
                settlement.throughSequence,
                settlement.mutationAtEpochMilliseconds,
            )
            check(updateStatus(settlement.snapshot.toStatusUpdate()) == 1) {
                "Notification status disappeared during its confirmed mark-all settlement."
            }
        } else {
            insertSnapshot(settlement.snapshot)
        }
    }

    @Transaction
    open suspend fun settlePreferences(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Long,
        preferences: List<NotificationPreferenceEntity>,
    ): Boolean = settleIfCurrent(accountId, operationId, expectedAttemptCount) {
        require(preferences.isNotEmpty() && preferences.all { preference -> preference.accountId == accountId }) {
            "Notification preference settlement account differs from its debt."
        }
        deletePreferences(accountId)
        insertPreferences(preferences)
    }

    private suspend fun settleIfCurrent(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Long,
        persistConfirmation: suspend () -> Unit,
    ): Boolean {
        val current = findOperationById(accountId, operationId)
        if (
            current == null ||
            current.attemptCount != expectedAttemptCount ||
            current.terminalErrorCode != null
        ) {
            return false
        }
        persistConfirmation()
        check(deleteOperationIfMatches(accountId, operationId, expectedAttemptCount) == 1) {
            "Notification operation changed inside its confirmed settlement transaction."
        }
        return true
    }
}

internal data class NotificationMarkAllReadSettlementRecord(
    val throughSequence: Long,
    val mutationAtEpochMilliseconds: Long,
    val snapshot: NotificationInboxSnapshotEntity,
    val snapshotWasPresent: Boolean,
)

private fun NotificationInboxSnapshotEntity.toStatusUpdate(): NotificationInboxStatusUpdate =
    NotificationInboxStatusUpdate(
        accountId = accountId,
        latestSequence = latestSequence,
        confirmedSeenThroughSequence = confirmedSeenThroughSequence,
        unseenCount = unseenCount,
        unreadCount = unreadCount,
        cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    )
