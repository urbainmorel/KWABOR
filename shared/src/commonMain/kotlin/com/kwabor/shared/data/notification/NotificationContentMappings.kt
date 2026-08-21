package com.kwabor.shared.data.notification

import com.kwabor.shared.data.core.isCanonicalPublicHttpsUrl
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.notification.NotificationContent
import com.kwabor.shared.domain.notification.NotificationImage
import com.kwabor.shared.domain.notification.NotificationKind
import com.kwabor.shared.domain.notification.NotificationListingTarget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun NotificationInboxRowDto.requireNotificationTarget(
    eventStartAtEpochMilliseconds: Long?,
): NotificationListingTarget? {
    if (!targetAvailable) {
        requireUnavailableNotificationTarget()
        return null
    }
    return requireAvailableNotificationTarget(eventStartAtEpochMilliseconds)
}

private fun NotificationInboxRowDto.requireUnavailableNotificationTarget() {
    val fields = listOf(
        targetListingId,
        targetListingType,
        targetListingName,
        targetCityId,
        targetCityName,
        targetCoverImageUrl,
        targetCoverImageAlt,
        targetEventStartAt,
    )
    if (fields.any { value -> value != null }) {
        invalidNotificationValue("target", "unavailable target exposes listing fields")
    }
}

private fun NotificationInboxRowDto.requireAvailableNotificationTarget(
    eventStartAtEpochMilliseconds: Long?,
): NotificationListingTarget {
    val listingId = targetListingId?.requireNotificationUuid("target_listing_id")
        ?: invalidNotificationValue("target_listing_id", "null")
    val listingType = targetListingType?.toNotificationListingType()
        ?: invalidNotificationValue("target_listing_type", "null")
    val listingName = targetListingName?.requireNotificationText(
        fieldName = "target_listing_name",
        maximumCodePoints = MAXIMUM_LISTING_NAME_CODE_POINTS,
    ) ?: invalidNotificationValue("target_listing_name", "null")
    val city = requireOptionalPair(targetCityId, targetCityName, "target_city")
    val cover = requireOptionalPair(targetCoverImageUrl, targetCoverImageAlt, "target_cover_image")
    requireNotificationEventTarget(listingType, eventStartAtEpochMilliseconds)
    return NotificationListingTarget(
        listingId = listingId,
        listingType = listingType,
        listingName = listingName,
        cityId = city?.first?.requireNotificationIdentifier("target_city_id"),
        cityName = city?.second?.requireNotificationText("target_city_name", MAXIMUM_CITY_NAME_CODE_POINTS),
        coverImage = cover?.toNotificationImage(),
        eventStartAtEpochMilliseconds = eventStartAtEpochMilliseconds,
    )
}

private fun requireNotificationEventTarget(listingType: ListingType, eventStartAtEpochMilliseconds: Long?) {
    if (listingType == ListingType.Event && eventStartAtEpochMilliseconds == null) {
        invalidNotificationValue("target_event_start_at", "missing for event listing")
    }
    if (listingType != ListingType.Event && eventStartAtEpochMilliseconds != null) {
        invalidNotificationValue("target_event_start_at", "present for non-event listing")
    }
}

private fun Pair<String, String>.toNotificationImage(): NotificationImage = NotificationImage(
    url = first.requireNotificationCoverUrl("target_cover_image_url"),
    alt = second.requireNotificationText("target_cover_image_alt", MAXIMUM_COVER_ALT_CODE_POINTS),
)

internal fun NotificationInboxRowDto.requireNotificationContent(kind: NotificationKind): NotificationContent {
    val mappedTitleKey = titleKey.requireNotificationTemplateKey("title_key")
    val mappedBodyKey = bodyKey.requireNotificationTemplateKey("body_key")
    kind.requireAllowedTemplateKeys(mappedTitleKey, mappedBodyKey)
    titleArgs.requireExactStringArguments(emptySet(), "title_args")
    return when (kind) {
        NotificationKind.Suggestion -> NotificationContent.Suggestion(
            mappedTitleKey,
            mappedBodyKey,
            bodyArgs.requireListingNameOnly(),
        )
        NotificationKind.Sponsored -> NotificationContent.Sponsored(
            mappedTitleKey,
            mappedBodyKey,
            bodyArgs.requireListingNameOnly(),
        )
        NotificationKind.NewListing -> requireNewListingContent(mappedTitleKey, mappedBodyKey)
        NotificationKind.EventAlert -> requireEventAlertContent(mappedTitleKey, mappedBodyKey)
    }
}

private fun NotificationInboxRowDto.requireNewListingContent(
    titleKey: String,
    bodyKey: String,
): NotificationContent.NewListing {
    val arguments = bodyArgs.requireExactStringArguments(
        expectedKeys = setOf("listing_name", "city_name"),
        fieldName = "body_args",
    )
    return NotificationContent.NewListing(
        titleKey = titleKey,
        bodyKey = bodyKey,
        listingName = arguments.getValue("listing_name")
            .requireNotificationText("body_args.listing_name", MAXIMUM_LISTING_NAME_CODE_POINTS),
        cityName = arguments.getValue("city_name")
            .requireNotificationText("body_args.city_name", MAXIMUM_CITY_NAME_CODE_POINTS),
    )
}

private fun NotificationInboxRowDto.requireEventAlertContent(
    titleKey: String,
    bodyKey: String,
): NotificationContent.EventAlert {
    val arguments = bodyArgs.requireExactStringArguments(
        expectedKeys = setOf("listing_name", "event_start_at"),
        fieldName = "body_args",
    )
    return NotificationContent.EventAlert(
        titleKey = titleKey,
        bodyKey = bodyKey,
        listingName = arguments.getValue("listing_name")
            .requireNotificationText("body_args.listing_name", MAXIMUM_LISTING_NAME_CODE_POINTS),
        eventStartAtEpochMilliseconds = arguments.getValue("event_start_at")
            .requireNotificationTimestamp("body_args.event_start_at"),
    )
}

internal fun String.toNotificationKind(): NotificationKind = when (this) {
    "suggestion" -> NotificationKind.Suggestion
    "sponsored" -> NotificationKind.Sponsored
    "new_listing" -> NotificationKind.NewListing
    "event_alert" -> NotificationKind.EventAlert
    else -> invalidNotificationValue("family", this)
}

private fun JsonObject.requireListingNameOnly(): String =
    requireExactStringArguments(setOf("listing_name"), "body_args").getValue("listing_name")
        .requireNotificationText("body_args.listing_name", MAXIMUM_LISTING_NAME_CODE_POINTS)

private fun JsonObject.requireExactStringArguments(
    expectedKeys: Set<String>,
    fieldName: String,
): Map<String, String> {
    if (keys != expectedKeys) invalidNotificationValue(fieldName, "unexpected argument keys")
    return mapValues { (key, value) ->
        val primitive = value as? JsonPrimitive
            ?: invalidNotificationValue("$fieldName.$key", "value is not a string")
        if (!primitive.isString) invalidNotificationValue("$fieldName.$key", "value is not a string")
        primitive.content
    }
}

private fun String.toNotificationListingType(): ListingType = when (this) {
    "lieu" -> ListingType.Place
    "etablissement" -> ListingType.Establishment
    "evenement" -> ListingType.Event
    else -> invalidNotificationValue("target_listing_type", this)
}

private fun String.requireNotificationTemplateKey(fieldName: String): String {
    if (length !in 1..MAXIMUM_TEMPLATE_KEY_LENGTH || !TEMPLATE_KEY_PATTERN.matches(this)) {
        invalidNotificationValue(fieldName, this)
    }
    return this
}

private fun NotificationKind.requireAllowedTemplateKeys(titleKey: String, bodyKey: String) {
    val expected = when (this) {
        NotificationKind.Suggestion -> "notification.suggestion.title" to "notification.suggestion.body"
        NotificationKind.Sponsored -> "notification.sponsored.title" to "notification.sponsored.body"
        NotificationKind.NewListing -> "notification.new_listing.title" to "notification.new_listing.body"
        NotificationKind.EventAlert -> "notification.event_alert.title" to "notification.event_alert.body"
    }
    if (titleKey != expected.first || bodyKey != expected.second) {
        invalidNotificationValue("template_keys", "$titleKey/$bodyKey")
    }
}

private fun String.requireNotificationIdentifier(fieldName: String): String {
    if (length !in 1..MAXIMUM_CITY_ID_LENGTH || !IDENTIFIER_PATTERN.matches(this)) {
        invalidNotificationValue(fieldName, this)
    }
    return this
}

private fun String.requireNotificationText(fieldName: String, maximumCodePoints: Int): String {
    val codePointCount = validNotificationCodePointCount()
    val isInvalid = listOf(
        isBlank(),
        trim() != this,
        codePointCount == null,
        codePointCount !in 1..maximumCodePoints,
        any(Char::isISOControl),
    ).any { condition -> condition }
    if (isInvalid) invalidNotificationValue(fieldName, this)
    return this
}

private fun String.validNotificationCodePointCount(): Int? {
    var index = 0
    var count = 0
    while (index < length) {
        val character = this[index]
        val width = when {
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> 2
            character.isHighSurrogate() || character.isLowSurrogate() -> return null
            else -> 1
        }
        index += width
        count += 1
    }
    return count
}

private fun String.requireNotificationCoverUrl(fieldName: String): String {
    val isInvalid = listOf(
        encodeToByteArray().size !in 1..MAXIMUM_COVER_URL_UTF8_BYTES,
        trim() != this,
        !isCanonicalPublicHttpsUrl(),
    ).any { condition -> condition }
    if (isInvalid) invalidNotificationValue(fieldName, this)
    return this
}

private fun requireOptionalPair(first: String?, second: String?, fieldName: String): Pair<String, String>? = when {
    first == null && second == null -> null
    first != null && second != null -> first to second
    else -> invalidNotificationValue(fieldName, "both values must be present or absent")
}

private const val MAXIMUM_TEMPLATE_KEY_LENGTH = 128
private const val MAXIMUM_LISTING_NAME_CODE_POINTS = 120
private const val MAXIMUM_CITY_NAME_CODE_POINTS = 120
private const val MAXIMUM_CITY_ID_LENGTH = 100
private const val MAXIMUM_COVER_ALT_CODE_POINTS = 240
private const val MAXIMUM_COVER_URL_UTF8_BYTES = 2_048
private val TEMPLATE_KEY_PATTERN = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
private val IDENTIFIER_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
