package com.kwabor.shared.data.notification

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class NotificationInboxRowDto(
    @SerialName("notification_id")
    val notificationId: String,
    @SerialName("sequence_number")
    val sequenceNumber: Long,
    @SerialName("snapshot_sequence")
    val snapshotSequence: Long,
    @SerialName("family")
    val family: String,
    @SerialName("title_key")
    val titleKey: String,
    @SerialName("title_args")
    val titleArgs: JsonObject,
    @SerialName("body_key")
    val bodyKey: String,
    @SerialName("body_args")
    val bodyArgs: JsonObject,
    @SerialName("target_available")
    val targetAvailable: Boolean,
    @SerialName("target_listing_id")
    val targetListingId: String?,
    @SerialName("target_listing_type")
    val targetListingType: String?,
    @SerialName("target_listing_name")
    val targetListingName: String?,
    @SerialName("target_city_id")
    val targetCityId: String?,
    @SerialName("target_city_name")
    val targetCityName: String?,
    @SerialName("target_cover_image_url")
    val targetCoverImageUrl: String?,
    @SerialName("target_cover_image_alt")
    val targetCoverImageAlt: String?,
    @SerialName("target_event_start_at")
    val targetEventStartAt: String?,
    @SerialName("sponsored")
    val sponsored: Boolean,
    @SerialName("seen_at")
    val seenAt: String?,
    @SerialName("read_at")
    val readAt: String?,
    @SerialName("hidden_at")
    val hiddenAt: String?,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("row_cursor")
    val rowCursor: String,
)

internal data class NotificationInboxPageDto(
    val items: List<NotificationInboxRowDto>,
    val snapshotSequence: Long?,
    val nextCursor: String?,
)

@Serializable
internal data class NotificationInboxStatusDto(
    @SerialName("latest_sequence")
    val latestSequence: Long,
    @SerialName("seen_through_sequence")
    val seenThroughSequence: Long,
    @SerialName("unseen_count")
    val unseenCount: Int,
    @SerialName("unread_count")
    val unreadCount: Int,
)

@Serializable
internal data class NotificationMarkAllReadResultDto(
    @SerialName("latest_sequence")
    val latestSequence: Long,
    @SerialName("seen_through_sequence")
    val seenThroughSequence: Long,
    @SerialName("unseen_count")
    val unseenCount: Int,
    @SerialName("unread_count")
    val unreadCount: Int,
    @SerialName("mutation_at")
    val mutationAt: String,
)

@Serializable
internal data class NotificationItemMutationDto(
    @SerialName("notification_id")
    val notificationId: String,
    @SerialName("sequence_number")
    val sequenceNumber: Long,
    @SerialName("seen_at")
    val seenAt: String?,
    @SerialName("read_at")
    val readAt: String?,
    @SerialName("hidden_at")
    val hiddenAt: String?,
)

@Serializable
internal data class NotificationPreferenceRowDto(
    @SerialName("family")
    val family: String,
    @SerialName("enabled")
    val enabled: Boolean,
    @SerialName("updated_at")
    val updatedAt: String?,
)

@Serializable
internal data class ListNotificationInboxParametersDto(
    @SerialName("p_expected_account_id")
    val expectedAccountId: String,
    @SerialName("p_cursor")
    val cursor: String?,
    @SerialName("p_limit")
    val limit: Int,
)

@Serializable
internal data class NotificationAccountParametersDto(
    @SerialName("p_expected_account_id")
    val expectedAccountId: String,
)

@Serializable
internal data class NotificationThroughSequenceParametersDto(
    @SerialName("p_expected_account_id")
    val expectedAccountId: String,
    @SerialName("p_through_sequence")
    val throughSequence: Long,
)

@Serializable
internal data class MarkNotificationSeenParametersDto(
    @SerialName("p_expected_account_id")
    val expectedAccountId: String,
    @SerialName("p_seen_through_sequence")
    val seenThroughSequence: Long,
)

@Serializable
internal data class NotificationItemParametersDto(
    @SerialName("p_expected_account_id")
    val expectedAccountId: String,
    @SerialName("p_notification_id")
    val notificationId: String,
)

@Serializable
internal data class SetNotificationPreferenceParametersDto(
    @SerialName("p_expected_account_id")
    val expectedAccountId: String,
    @SerialName("p_family")
    val family: String,
    @SerialName("p_enabled")
    val enabled: Boolean,
)
