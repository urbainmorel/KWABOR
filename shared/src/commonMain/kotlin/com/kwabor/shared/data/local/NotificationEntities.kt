package com.kwabor.shared.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notification_inbox_snapshots")
internal data class NotificationInboxSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "snapshot_sequence")
    val snapshotSequence: Long,
    @ColumnInfo(name = "next_cursor")
    val nextCursor: String?,
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
    @ColumnInfo(name = "item_count")
    val itemCount: Long,
)

@Entity(
    tableName = "notification_inbox_items",
    primaryKeys = ["account_id", "notification_id"],
    foreignKeys = [
        ForeignKey(
            entity = NotificationInboxSnapshotEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "sequence_number"], unique = true),
    ],
)
internal data class NotificationInboxItemEntity(
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "notification_id")
    val notificationId: String,
    @ColumnInfo(name = "sequence_number")
    val sequenceNumber: Long,
    @ColumnInfo(name = "family")
    val family: String,
    @ColumnInfo(name = "title_key")
    val titleKey: String,
    @ColumnInfo(name = "body_key")
    val bodyKey: String,
    @ColumnInfo(name = "content_listing_name")
    val contentListingName: String,
    @ColumnInfo(name = "content_city_name")
    val contentCityName: String?,
    @ColumnInfo(name = "content_event_start_at_epoch_milliseconds")
    val contentEventStartAtEpochMilliseconds: Long?,
    @ColumnInfo(name = "target_available")
    val targetAvailableRaw: Long,
    @ColumnInfo(name = "target_listing_id")
    val targetListingId: String?,
    @ColumnInfo(name = "target_listing_type")
    val targetListingType: String?,
    @ColumnInfo(name = "target_listing_name")
    val targetListingName: String?,
    @ColumnInfo(name = "target_city_id")
    val targetCityId: String?,
    @ColumnInfo(name = "target_city_name")
    val targetCityName: String?,
    @ColumnInfo(name = "target_cover_image_url")
    val targetCoverImageUrl: String?,
    @ColumnInfo(name = "target_cover_image_alt")
    val targetCoverImageAlt: String?,
    @ColumnInfo(name = "target_event_start_at_epoch_milliseconds")
    val targetEventStartAtEpochMilliseconds: Long?,
    @ColumnInfo(name = "sponsored")
    val sponsoredRaw: Long,
    @ColumnInfo(name = "seen_at_epoch_milliseconds")
    val seenAtEpochMilliseconds: Long?,
    @ColumnInfo(name = "read_at_epoch_milliseconds")
    val readAtEpochMilliseconds: Long?,
    @ColumnInfo(name = "hidden_at_epoch_milliseconds")
    val hiddenAtEpochMilliseconds: Long?,
    @ColumnInfo(name = "created_at_epoch_milliseconds")
    val createdAtEpochMilliseconds: Long,
)

@Entity(
    tableName = "notification_sync_operations",
    indices = [
        Index(value = ["account_id", "logical_key"], unique = true),
        Index(
            value = [
                "account_id",
                "terminal_error_code",
                "next_attempt_at_epoch_milliseconds",
                "enqueued_at_epoch_milliseconds",
            ],
        ),
    ],
)
internal data class NotificationSyncOperationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "operation_id")
    val operationId: Long = 0,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "logical_key")
    val logicalKey: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "notification_id")
    val notificationId: String?,
    @ColumnInfo(name = "through_sequence")
    val throughSequence: Long?,
    @ColumnInfo(name = "family")
    val family: String?,
    @ColumnInfo(name = "desired_enabled")
    val desiredEnabledRaw: Long?,
    @ColumnInfo(name = "enqueued_at_epoch_milliseconds")
    val enqueuedAtEpochMilliseconds: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Long,
    @ColumnInfo(name = "next_attempt_at_epoch_milliseconds")
    val nextAttemptAtEpochMilliseconds: Long,
    @ColumnInfo(name = "terminal_error_code")
    val terminalErrorCode: String?,
)

@Entity(
    tableName = "notification_preferences_cache",
    primaryKeys = ["account_id", "family"],
)
internal data class NotificationPreferenceEntity(
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "family")
    val family: String,
    @ColumnInfo(name = "enabled")
    val enabledRaw: Long,
    @ColumnInfo(name = "updated_at_epoch_milliseconds")
    val updatedAtEpochMilliseconds: Long?,
    @ColumnInfo(name = "cached_at_epoch_milliseconds")
    val cachedAtEpochMilliseconds: Long,
)
