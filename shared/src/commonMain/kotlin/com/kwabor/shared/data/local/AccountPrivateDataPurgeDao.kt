package com.kwabor.shared.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal abstract class AccountPrivateDataPurgeDao {
    @Query("SELECT COUNT(*) FROM interaction_outbox_operations WHERE account_id = :accountId")
    protected abstract suspend fun countInteractionOperations(accountId: String): Int

    @Query("SELECT COUNT(*) FROM notification_inbox_items WHERE account_id = :accountId")
    protected abstract suspend fun countNotificationItems(accountId: String): Int

    @Query("SELECT COUNT(*) FROM notification_inbox_snapshots WHERE account_id = :accountId")
    protected abstract suspend fun countNotificationSnapshots(accountId: String): Int

    @Query("SELECT COUNT(*) FROM notification_sync_operations WHERE account_id = :accountId")
    protected abstract suspend fun countNotificationOperations(accountId: String): Int

    @Query("SELECT COUNT(*) FROM notification_preferences_cache WHERE account_id = :accountId")
    protected abstract suspend fun countNotificationPreferences(accountId: String): Int

    @Query("DELETE FROM interaction_outbox_operations WHERE account_id = :accountId")
    protected abstract suspend fun deleteInteractionOperations(accountId: String): Int

    @Query("DELETE FROM notification_sync_operations WHERE account_id = :accountId")
    protected abstract suspend fun deleteNotificationOperations(accountId: String): Int

    @Query("DELETE FROM notification_preferences_cache WHERE account_id = :accountId")
    protected abstract suspend fun deleteNotificationPreferences(accountId: String): Int

    @Query("DELETE FROM notification_inbox_snapshots WHERE account_id = :accountId")
    protected abstract suspend fun deleteNotificationSnapshot(accountId: String): Int

    @Transaction
    open suspend fun purgeAccount(accountId: String): AccountPrivateDataPurgeRecord {
        val record = AccountPrivateDataPurgeRecord(
            interactionOperationCount = countInteractionOperations(accountId),
            notificationItemCount = countNotificationItems(accountId),
            notificationSnapshotCount = countNotificationSnapshots(accountId),
            notificationOperationCount = countNotificationOperations(accountId),
            notificationPreferenceCount = countNotificationPreferences(accountId),
        )
        deleteInteractionOperations(accountId)
        deleteNotificationOperations(accountId)
        deleteNotificationPreferences(accountId)
        deleteNotificationSnapshot(accountId)
        return record
    }
}

internal data class AccountPrivateDataPurgeRecord(
    val interactionOperationCount: Int,
    val notificationItemCount: Int,
    val notificationSnapshotCount: Int,
    val notificationOperationCount: Int,
    val notificationPreferenceCount: Int,
)
