package com.kwabor.shared.data.notification

import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.data.local.NotificationInboxItemEntity
import com.kwabor.shared.data.local.NotificationPreferenceEntity
import com.kwabor.shared.data.local.NotificationSyncOperationEntity
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.notification.NotificationContent
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

internal fun NotificationInboxItem.toNotificationEntity(accountId: String): NotificationInboxItemEntity {
    val contentCityName = (content as? NotificationContent.NewListing)?.cityName
    val contentEventStart = (content as? NotificationContent.EventAlert)?.eventStartAtEpochMilliseconds
    return NotificationInboxItemEntity(
        accountId = accountId,
        notificationId = id,
        sequenceNumber = sequence,
        family = kind.toWireValue(),
        titleKey = content.titleKey,
        bodyKey = content.bodyKey,
        contentListingName = content.listingName,
        contentCityName = contentCityName,
        contentEventStartAtEpochMilliseconds = contentEventStart,
        targetAvailableRaw = (target != null).toStoredBoolean(),
        targetListingId = target?.listingId,
        targetListingType = target?.listingType?.toNotificationListingTypeValue(),
        targetListingName = target?.listingName,
        targetCityId = target?.cityId,
        targetCityName = target?.cityName,
        targetCoverImageUrl = target?.coverImage?.url,
        targetCoverImageAlt = target?.coverImage?.alt,
        targetEventStartAtEpochMilliseconds = target?.eventStartAtEpochMilliseconds,
        sponsoredRaw = (content is NotificationContent.Sponsored).toStoredBoolean(),
        seenAtEpochMilliseconds = seenAtEpochMilliseconds,
        readAtEpochMilliseconds = readAtEpochMilliseconds,
        hiddenAtEpochMilliseconds = hiddenAtEpochMilliseconds,
        createdAtEpochMilliseconds = createdAtEpochMilliseconds,
    )
}

internal fun NotificationInboxItemEntity.toNotificationDomainOrNull(snapshotSequence: Long): NotificationInboxItem? {
    val targetAvailable = targetAvailableRaw.toStoredBooleanOrNull() ?: return null
    val sponsored = sponsoredRaw.toStoredBooleanOrNull() ?: return null
    val row = toNotificationRowDto(snapshotSequence, targetAvailable, sponsored)
    return try {
        row.toDomain(allowHidden = true)
    } catch (_: NotificationDataException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun NotificationInboxItemEntity.toBodyArguments() = when (family) {
        "suggestion", "sponsored" -> buildJsonObject {
            put("listing_name", JsonPrimitive(contentListingName))
        }
        "new_listing" -> buildJsonObject {
            put("listing_name", JsonPrimitive(contentListingName))
            contentCityName?.let { cityName -> put("city_name", JsonPrimitive(cityName)) }
        }
        "event_alert" -> buildJsonObject {
            put("listing_name", JsonPrimitive(contentListingName))
            contentEventStartAtEpochMilliseconds?.let { eventStart ->
                put("event_start_at", JsonPrimitive(eventStart.toNotificationInstantString()))
            }
        }
        else -> buildJsonObject {}
    }

private fun NotificationInboxItemEntity.toNotificationRowDto(
    snapshotSequence: Long,
    targetAvailable: Boolean,
    sponsored: Boolean,
): NotificationInboxRowDto = NotificationInboxRowDto(
    notificationId = notificationId,
    sequenceNumber = sequenceNumber,
    snapshotSequence = snapshotSequence,
    family = family,
    titleKey = titleKey,
    titleArgs = buildJsonObject {},
    bodyKey = bodyKey,
    bodyArgs = toBodyArguments(),
    targetAvailable = targetAvailable,
    targetListingId = targetListingId,
    targetListingType = targetListingType,
    targetListingName = targetListingName,
    targetCityId = targetCityId,
    targetCityName = targetCityName,
    targetCoverImageUrl = targetCoverImageUrl,
    targetCoverImageAlt = targetCoverImageAlt,
    targetEventStartAt = targetEventStartAtEpochMilliseconds?.toNotificationInstantString(),
    sponsored = sponsored,
    seenAt = seenAtEpochMilliseconds?.toNotificationInstantString(),
    readAt = readAtEpochMilliseconds?.toNotificationInstantString(),
    hiddenAt = hiddenAtEpochMilliseconds?.toNotificationInstantString(),
    createdAt = createdAtEpochMilliseconds.toNotificationInstantString(),
    rowCursor = "local-cache",
)

internal fun NotificationPreferences.toNotificationPreferenceEntities(
    accountId: String,
    cachedAtEpochMilliseconds: Long,
): List<NotificationPreferenceEntity> = entries.map { preference ->
    NotificationPreferenceEntity(
        accountId = accountId,
        family = preference.family.toWireValue(),
        enabledRaw = preference.enabled.toStoredBoolean(),
        updatedAtEpochMilliseconds = preference.updatedAtEpochMilliseconds,
        cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    )
}

internal fun List<NotificationPreferenceEntity>.toCachedNotificationPreferences(
    accountId: String,
): CachedNotificationPreferences {
    val validEntities = filter { entity -> entity.toNotificationPreferenceDtoOrNull() != null }
    val preferences = validEntities.mapNotNull { entity -> entity.toNotificationPreferenceDtoOrNull() }
        .toDomainPreferences()
    return CachedNotificationPreferences(
        accountId = accountId,
        preferences = preferences,
        cachedAtEpochMilliseconds = validEntities.maxOfOrNull(NotificationPreferenceEntity::cachedAtEpochMilliseconds),
    )
}

internal fun NotificationSyncOperationEntity.toNotificationOperationOrNull(): NotificationSyncOperation? {
    val mappedKind = NotificationSyncOperationKind.fromStoredValue(kind) ?: return null
    if (!hasValidOperationEnvelope()) return null
    val mappedFamily = family?.toNotificationPreferenceFamilyOrNull()
    val mappedDesiredEnabled = desiredEnabledRaw?.toStoredBooleanOrNull()
    val payloadIsValid = hasValidOperationPayload(mappedKind, mappedFamily, mappedDesiredEnabled)
    if (!payloadIsValid || logicalKey != mappedKind.logicalKey(notificationId, mappedFamily)) return null
    return NotificationSyncOperation(
        operationId = operationId,
        accountId = accountId,
        kind = mappedKind,
        notificationId = notificationId,
        throughSequence = throughSequence,
        family = mappedFamily,
        desiredEnabled = mappedDesiredEnabled,
        enqueuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
        attemptCount = attemptCount.toInt(),
        nextAttemptAtEpochMilliseconds = nextAttemptAtEpochMilliseconds,
        terminalErrorCode = terminalErrorCode,
    )
}

private fun NotificationSyncOperationEntity.hasValidOperationEnvelope(): Boolean = listOf(
    accountId.isCanonicalNotificationUuid(),
    operationId > 0L,
    attemptCount in 0L..Int.MAX_VALUE.toLong(),
    enqueuedAtEpochMilliseconds >= 0L,
    nextAttemptAtEpochMilliseconds >= 0L,
    terminalErrorCode == null || terminalErrorCode.isValidNotificationTerminalErrorCode(),
).all { condition -> condition }

private fun NotificationSyncOperationEntity.hasValidOperationPayload(
    mappedKind: NotificationSyncOperationKind,
    mappedFamily: NotificationPreferenceFamily?,
    mappedDesiredEnabled: Boolean?,
): Boolean = when (mappedKind) {
    NotificationSyncOperationKind.AdvanceSeenThrough,
    NotificationSyncOperationKind.MarkAllReadThrough,
    -> listOf(
        notificationId == null,
        throughSequence != null && throughSequence > 0L,
        family == null,
        desiredEnabledRaw == null,
    ).all { condition -> condition }
    NotificationSyncOperationKind.MarkRead,
    NotificationSyncOperationKind.Hide,
    -> listOf(
        notificationId?.isCanonicalNotificationUuid() == true,
        throughSequence == null,
        family == null,
        desiredEnabledRaw == null,
    ).all { condition -> condition }
    NotificationSyncOperationKind.SetFamilyEnabled -> listOf(
        notificationId == null,
        throughSequence == null,
        mappedFamily != null,
        mappedDesiredEnabled != null,
    ).all { condition -> condition }
}

internal fun NotificationSyncOperationKind.logicalKey(
    notificationId: String? = null,
    family: NotificationPreferenceFamily? = null,
): String = when (this) {
    NotificationSyncOperationKind.AdvanceSeenThrough -> "advance_seen_through"
    NotificationSyncOperationKind.MarkAllReadThrough -> "mark_all_read_through"
    NotificationSyncOperationKind.MarkRead -> "mark_read:${requireNotNull(notificationId)}"
    NotificationSyncOperationKind.Hide -> "hide:${requireNotNull(notificationId)}"
    NotificationSyncOperationKind.SetFamilyEnabled -> "set_family_enabled:${requireNotNull(family).toWireValue()}"
}

internal fun String.isCanonicalNotificationUuid(): Boolean = isValidUuid() && this == lowercase()

internal fun String.toNotificationPreferenceFamilyOrNull(): NotificationPreferenceFamily? = when (this) {
    "suggestion" -> NotificationPreferenceFamily.Suggestion
    "sponsored" -> NotificationPreferenceFamily.Sponsored
    "new_listing" -> NotificationPreferenceFamily.NewListing
    "event_alert" -> NotificationPreferenceFamily.EventAlert
    else -> null
}

internal fun Boolean.toStoredBoolean(): Long = if (this) 1L else 0L

internal fun Long.toStoredBooleanOrNull(): Boolean? = when (this) {
    0L -> false
    1L -> true
    else -> null
}

internal fun String.isValidNotificationTerminalErrorCode(): Boolean =
    length in 1..MAXIMUM_NOTIFICATION_TERMINAL_ERROR_CODE_LENGTH &&
        NOTIFICATION_TERMINAL_ERROR_CODE_PATTERN.matches(this)

internal fun NotificationPreferenceEntity.toNotificationPreferenceDtoOrNull(): NotificationPreferenceRowDto? {
    if (!accountId.isCanonicalNotificationUuid()) return null
    val mappedEnabled = enabledRaw.toStoredBooleanOrNull() ?: return null
    if (cachedAtEpochMilliseconds < 0L || (updatedAtEpochMilliseconds != null && updatedAtEpochMilliseconds < 0L)) {
        return null
    }
    if (family.toNotificationPreferenceFamilyOrNull() == null) return null
    return NotificationPreferenceRowDto(
        family = family,
        enabled = mappedEnabled,
        updatedAt = updatedAtEpochMilliseconds?.toNotificationInstantString(),
    )
}

private fun ListingType.toNotificationListingTypeValue(): String = when (this) {
    ListingType.Place -> "lieu"
    ListingType.Establishment -> "etablissement"
    ListingType.Event -> "evenement"
}

private fun Long.toNotificationInstantString(): String = Instant.fromEpochMilliseconds(this).toString()

private const val MAXIMUM_NOTIFICATION_TERMINAL_ERROR_CODE_LENGTH = 64
private val NOTIFICATION_TERMINAL_ERROR_CODE_PATTERN = Regex("^[a-z][a-z0-9_]*$")
