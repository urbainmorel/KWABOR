package com.kwabor.shared.data.notification

import com.kwabor.shared.data.local.NotificationInboxItemEntity
import com.kwabor.shared.data.local.NotificationInboxSnapshotEntity
import com.kwabor.shared.data.local.NotificationInboxStatusUpdate
import com.kwabor.shared.domain.notification.NotificationCachedInbox
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences

internal const val DEFAULT_MAX_NOTIFICATION_CACHE_ITEMS = NotificationCachedInbox.MAX_ITEMS
internal const val DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS = 512
internal const val DEFAULT_NOTIFICATION_SYNC_READ_LIMIT = 50
internal const val MAX_NOTIFICATION_SYNC_READ_LIMIT = DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS

internal data class CachedNotificationInbox(
    val accountId: String,
    val snapshotSequence: Long,
    val nextCursor: String?,
    val status: NotificationInboxStatus,
    val cachedAtEpochMilliseconds: Long,
    val items: List<NotificationInboxItem>,
)

internal data class CachedNotificationPreferences(
    val accountId: String,
    val preferences: NotificationPreferences,
    val cachedAtEpochMilliseconds: Long?,
)

internal data class PreparedNotificationStatusSettlement(
    val status: NotificationInboxStatusUpdate,
    val snapshotWhenAbsent: NotificationInboxSnapshotEntity?,
)

internal data class PreparedNotificationItemSettlement(
    val item: NotificationInboxItemEntity?,
    val status: NotificationInboxStatusUpdate,
    val snapshotWhenAbsent: NotificationInboxSnapshotEntity?,
)

internal data class PreparedNotificationMarkAllReadSettlement(
    val throughSequence: Long,
    val mutationAtEpochMilliseconds: Long,
    val snapshot: NotificationInboxSnapshotEntity,
    val snapshotWasPresent: Boolean,
)

internal enum class NotificationSyncOperationKind(internal val storedValue: String) {
    AdvanceSeenThrough(storedValue = "advance_seen_through"),
    MarkRead(storedValue = "mark_read"),
    MarkAllReadThrough(storedValue = "mark_all_read_through"),
    Hide(storedValue = "hide"),
    SetFamilyEnabled(storedValue = "set_family_enabled"),
    ;

    internal companion object {
        fun fromStoredValue(value: String): NotificationSyncOperationKind? = entries.firstOrNull { kind ->
            kind.storedValue == value
        }
    }
}

internal data class NotificationSyncOperation(
    val operationId: Long,
    val accountId: String,
    val kind: NotificationSyncOperationKind,
    val notificationId: String?,
    val throughSequence: Long?,
    val family: NotificationPreferenceFamily?,
    val desiredEnabled: Boolean?,
    val enqueuedAtEpochMilliseconds: Long,
    val attemptCount: Int,
    val nextAttemptAtEpochMilliseconds: Long,
    val terminalErrorCode: String?,
)

internal class NotificationCacheCapacityExceededException(maxItemCount: Int) :
    IllegalStateException("Notification cache capacity of $maxItemCount items was reached.")

internal class NotificationCacheSnapshotMismatchException :
    IllegalStateException("Notification cache snapshot no longer matches the requested append.")

internal class NotificationStorageUnavailableException :
    IllegalStateException("Durable notification storage is unavailable.")
