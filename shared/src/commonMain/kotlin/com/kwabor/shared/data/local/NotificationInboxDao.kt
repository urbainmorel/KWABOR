package com.kwabor.shared.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
internal abstract class NotificationInboxDao {
    @Query("SELECT * FROM notification_inbox_snapshots WHERE account_id = :accountId")
    abstract suspend fun findSnapshot(accountId: String): NotificationInboxSnapshotEntity?

    @Query(
        """
        SELECT *
        FROM notification_inbox_items
        WHERE account_id = :accountId
        ORDER BY sequence_number DESC, notification_id DESC
        """,
    )
    abstract suspend fun findItems(accountId: String): List<NotificationInboxItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSnapshot(snapshot: NotificationInboxSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertItems(items: List<NotificationInboxItemEntity>)

    @Query(
        """
        UPDATE notification_inbox_snapshots
        SET next_cursor = NULL,
            item_count = :itemCount
        WHERE account_id = :accountId
        """,
    )
    abstract suspend fun repairSnapshotAfterItemEviction(accountId: String, itemCount: Long): Int

    @Update(entity = NotificationInboxSnapshotEntity::class)
    abstract suspend fun updateStatus(update: NotificationInboxStatusUpdate): Int

    @Update
    abstract suspend fun updateItem(item: NotificationInboxItemEntity): Int

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

    @Query(
        """
        DELETE FROM notification_inbox_items
        WHERE account_id = :accountId
          AND notification_id = :notificationId
        """,
    )
    abstract suspend fun deleteItem(accountId: String, notificationId: String): Int

    @Query("DELETE FROM notification_inbox_snapshots WHERE account_id = :accountId")
    abstract suspend fun deleteInbox(accountId: String): Int

    @Transaction
    open suspend fun replaceSnapshot(
        snapshot: NotificationInboxSnapshotEntity,
        items: List<NotificationInboxItemEntity>,
    ) {
        insertSnapshot(snapshot)
        if (items.isNotEmpty()) insertItems(items)
    }

    @Transaction
    open suspend fun applyMarkAllRead(
        throughSequence: Long,
        mutationAtEpochMilliseconds: Long,
        snapshot: NotificationInboxSnapshotEntity,
    ): Int {
        val updatedItems = markItemsReadThrough(
            snapshot.accountId,
            throughSequence,
            mutationAtEpochMilliseconds,
        )
        check(updateStatus(snapshot.toStatusUpdate()) == 1) {
            "Notification status disappeared while applying mark-all-read."
        }
        return updatedItems
    }

    @Transaction
    open suspend fun applyItemMutation(
        item: NotificationInboxItemEntity,
        status: NotificationInboxStatusUpdate,
    ) {
        check(updateItem(item) == 1) {
            "Notification item disappeared while applying its confirmed mutation."
        }
        check(updateStatus(status) == 1) {
            "Notification status disappeared while applying an item mutation."
        }
    }
}

internal data class NotificationInboxStatusUpdate(
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "latest_sequence")
    val latestSequence: Long,
    @ColumnInfo(name = "confirmed_seen_through_sequence")
    val confirmedSeenThroughSequence: Long,
    @ColumnInfo(name = "unseen_count")
    val unseenCount: Long,
    @ColumnInfo(name = "unread_count")
    val unreadCount: Long,
    @ColumnInfo(name = "cached_at_epoch_milliseconds")
    val cachedAtEpochMilliseconds: Long,
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
