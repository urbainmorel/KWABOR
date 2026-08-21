package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.notification.NotificationContent
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationKind
import com.kwabor.shared.domain.notification.NotificationMarkAllReadConfirmation
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationMappingsTest {
    @Test
    fun mapsFourTypedFamiliesAndKeepsSeenReadAndHiddenIndependent() {
        val suggestion = notificationRow(
            family = "suggestion",
            sequence = 40,
            overrides = NotificationRowOverrides(
                seenAt = STATE_TIMESTAMP,
                readAt = STATE_TIMESTAMP,
            ),
        ).toDomain()
        val sponsored = notificationRow(family = "sponsored", sequence = 30).toDomain()
        val newListing = notificationRow(family = "new_listing", sequence = 20).toDomain()
        val eventAlert = notificationRow(family = "event_alert", sequence = 10).toDomain()

        assertEquals(NotificationKind.Suggestion, suggestion.kind)
        assertIs<NotificationContent.Suggestion>(suggestion.content)
        assertEquals(STATE_TIMESTAMP_EPOCH_MILLISECONDS, suggestion.seenAtEpochMilliseconds)
        assertEquals(STATE_TIMESTAMP_EPOCH_MILLISECONDS, suggestion.readAtEpochMilliseconds)
        assertNull(suggestion.hiddenAtEpochMilliseconds)
        assertEquals(NotificationKind.Sponsored, sponsored.kind)
        assertIs<NotificationContent.Sponsored>(sponsored.content)
        assertEquals(NotificationKind.NewListing, newListing.kind)
        assertEquals("Cotonou", assertIs<NotificationContent.NewListing>(newListing.content).cityName)
        assertEquals(ListingType.Event, eventAlert.target?.listingType)
        assertEquals(
            EVENT_TIMESTAMP_EPOCH_MILLISECONDS,
            assertIs<NotificationContent.EventAlert>(eventAlert.content).eventStartAtEpochMilliseconds,
        )

        val hidden = NotificationItemMutationDto(
            notificationId = NOTIFICATION_ID,
            sequenceNumber = 40,
            seenAt = STATE_TIMESTAMP,
            readAt = null,
            hiddenAt = STATE_TIMESTAMP,
        ).toHiddenDomain(NOTIFICATION_ID)
        assertEquals(STATE_TIMESTAMP_EPOCH_MILLISECONDS, hidden.seenAtEpochMilliseconds)
        assertNull(hidden.readAtEpochMilliseconds)
        assertEquals(STATE_TIMESTAMP_EPOCH_MILLISECONDS, hidden.hiddenAtEpochMilliseconds)
    }

    @Test
    fun rejectsLegacyFamiliesAndAnyTemplateArgumentOutsideTheExactContract() {
        assertFailsWith<NotificationDataException.Unexpected> {
            notificationRow(family = "social").toDomain()
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            notificationRow(overrides = NotificationRowOverrides(readAt = STATE_TIMESTAMP)).toDomain()
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            NotificationItemMutationDto(
                notificationId = NOTIFICATION_ID,
                sequenceNumber = 1,
                seenAt = null,
                readAt = null,
                hiddenAt = STATE_TIMESTAMP,
            ).toHiddenDomain(NOTIFICATION_ID)
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            notificationRow(family = "system").toDomain()
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            notificationRow(
                family = "suggestion",
                overrides = NotificationRowOverrides(
                    titleArgs = buildJsonObject { put("unexpected", JsonPrimitive("value")) },
                ),
            ).toDomain()
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            notificationRow(
                family = "new_listing",
                overrides = NotificationRowOverrides(
                    bodyArgs = buildJsonObject { put("listing_name", JsonPrimitive(LISTING_NAME)) },
                ),
            ).toDomain()
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            notificationRow(
                family = "event_alert",
                overrides = NotificationRowOverrides(
                    bodyArgs = buildJsonObject {
                        put("listing_name", JsonPrimitive(LISTING_NAME))
                        put("event_start_at", JsonPrimitive(42))
                    },
                ),
            ).toDomain()
        }
    }

    @Test
    fun sentinelPaginationRequiresOneSnapshotStrictOrderAndUniqueRows() {
        val rows = listOf(
            notificationRow(sequence = 30, notificationId = NOTIFICATION_ID_3, rowCursor = "cursor-30"),
            notificationRow(sequence = 20, notificationId = NOTIFICATION_ID_2, rowCursor = "cursor-20"),
            notificationRow(sequence = 10, notificationId = NOTIFICATION_ID, rowCursor = "cursor-10"),
        )

        val page = rows.toNotificationInboxPageDto(limit = 2).toDomain()

        assertEquals(listOf(30L, 20L), page.items.map { item -> item.sequence })
        assertEquals(SNAPSHOT_SEQUENCE, page.snapshotSequence)
        assertEquals("cursor-20", page.nextCursor)
        assertFailsWith<NotificationDataException.Unexpected> {
            rows.reversed().toNotificationInboxPageDto(limit = 2)
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            listOf(rows.first(), rows.first()).toNotificationInboxPageDto(limit = 2)
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            listOf(rows.first(), rows[1].copy(snapshotSequence = SNAPSHOT_SEQUENCE + 1))
                .toNotificationInboxPageDto(limit = 2)
        }
    }

    @Test
    fun missingPreferenceRowsAreMaterializedAsDisabledAndDuplicatesAreRejected() {
        val preferences = listOf(
            NotificationPreferenceRowDto(
                family = "suggestion",
                enabled = true,
                updatedAt = STATE_TIMESTAMP,
            ),
        ).toDomainPreferences()

        assertTrue(preferences.preferenceFor(NotificationPreferenceFamily.Suggestion).enabled)
        assertFalse(preferences.preferenceFor(NotificationPreferenceFamily.Sponsored).enabled)
        assertFalse(preferences.preferenceFor(NotificationPreferenceFamily.NewListing).enabled)
        assertFalse(preferences.preferenceFor(NotificationPreferenceFamily.EventAlert).enabled)
        assertNull(preferences.preferenceFor(NotificationPreferenceFamily.Sponsored).updatedAtEpochMilliseconds)
        assertFailsWith<NotificationDataException.Unexpected> {
            listOf(
                NotificationPreferenceRowDto("suggestion", true, STATE_TIMESTAMP),
                NotificationPreferenceRowDto("suggestion", false, STATE_TIMESTAMP),
            ).toDomainPreferences()
        }
    }

    @Test
    fun unavailableTargetKeepsHistoricalTypedContentWithoutAStaleDeepLink() {
        val row = notificationRow(family = "event_alert").copy(
            targetAvailable = false,
            targetListingId = null,
            targetListingType = null,
            targetListingName = null,
            targetCityId = null,
            targetCityName = null,
            targetCoverImageUrl = null,
            targetCoverImageAlt = null,
            targetEventStartAt = null,
        )

        val item = row.toDomain()

        assertNull(item.target)
        assertEquals(LISTING_NAME, item.content.listingName)
        assertEquals(
            EVENT_TIMESTAMP_EPOCH_MILLISECONDS,
            assertIs<NotificationContent.EventAlert>(item.content).eventStartAtEpochMilliseconds,
        )
    }

    @Test
    fun statusRejectsCountersThatCannotExistInASequenceHistory() {
        assertFailsWith<IllegalArgumentException> {
            NotificationInboxStatus(
                latestSequence = 10,
                seenThroughSequence = 0,
                unseenCount = 2,
                unreadCount = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationInboxStatus(
                latestSequence = 1,
                seenThroughSequence = 0,
                unseenCount = 0,
                unreadCount = 2,
            )
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            NotificationInboxStatusDto(
                latestSequence = 1,
                seenThroughSequence = 0,
                unseenCount = 2,
                unreadCount = 1,
            ).toDomain()
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationMarkAllReadConfirmation(
                status = NotificationInboxStatus(
                    latestSequence = 10,
                    seenThroughSequence = 4,
                    unseenCount = 1,
                    unreadCount = 1,
                ),
                throughSequence = 5,
                mutationAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
            )
        }
        assertFailsWith<NotificationDataException.Unexpected> {
            NotificationMarkAllReadResultDto(
                latestSequence = 10,
                seenThroughSequence = 4,
                unseenCount = 1,
                unreadCount = 1,
                mutationAt = STATE_TIMESTAMP,
            ).toDomain(throughSequence = 5)
        }
    }
}

internal fun notificationRow(
    family: String = "suggestion",
    sequence: Long = 1,
    notificationId: String = NOTIFICATION_ID,
    rowCursor: String = "cursor-1",
    overrides: NotificationRowOverrides = NotificationRowOverrides(),
): NotificationInboxRowDto {
    val isEvent = family == "event_alert"
    val bodyArgs = overrides.bodyArgs ?: when (family) {
        "new_listing" -> buildJsonObject {
            put("listing_name", JsonPrimitive(LISTING_NAME))
            put("city_name", JsonPrimitive(CITY_NAME))
        }
        "event_alert" -> buildJsonObject {
            put("listing_name", JsonPrimitive(LISTING_NAME))
            put("event_start_at", JsonPrimitive(EVENT_TIMESTAMP))
        }
        else -> buildJsonObject { put("listing_name", JsonPrimitive(LISTING_NAME)) }
    }
    return NotificationInboxRowDto(
        notificationId = notificationId,
        sequenceNumber = sequence,
        snapshotSequence = SNAPSHOT_SEQUENCE,
        family = family,
        titleKey = "notification.$family.title",
        titleArgs = overrides.titleArgs,
        bodyKey = "notification.$family.body",
        bodyArgs = bodyArgs,
        targetAvailable = true,
        targetListingId = LISTING_ID,
        targetListingType = if (isEvent) "evenement" else "etablissement",
        targetListingName = LISTING_NAME,
        targetCityId = CITY_ID,
        targetCityName = CITY_NAME,
        targetCoverImageUrl = null,
        targetCoverImageAlt = null,
        targetEventStartAt = if (isEvent) EVENT_TIMESTAMP else null,
        sponsored = family == "sponsored",
        seenAt = overrides.seenAt,
        readAt = overrides.readAt,
        hiddenAt = null,
        createdAt = CREATED_TIMESTAMP,
        rowCursor = rowCursor,
    )
}

internal data class NotificationRowOverrides(
    val titleArgs: kotlinx.serialization.json.JsonObject = buildJsonObject {},
    val bodyArgs: kotlinx.serialization.json.JsonObject? = null,
    val seenAt: String? = null,
    val readAt: String? = null,
)

internal const val ACCOUNT_ID = "10000000-0000-4000-8000-000000000001"
internal const val NOTIFICATION_ID = "20000000-0000-4000-8000-000000000001"
private const val NOTIFICATION_ID_2 = "20000000-0000-4000-8000-000000000002"
private const val NOTIFICATION_ID_3 = "20000000-0000-4000-8000-000000000003"
internal const val LISTING_ID = "30000000-0000-4000-8000-000000000001"
internal const val LISTING_NAME = "Musée de Cotonou"
internal const val CITY_ID = "cotonou"
internal const val CITY_NAME = "Cotonou"
internal const val CREATED_TIMESTAMP = "2026-08-10T09:00:00Z"
internal const val STATE_TIMESTAMP = "2026-08-10T10:00:00Z"
internal const val EVENT_TIMESTAMP = "2026-08-11T18:30:00Z"
internal const val STATE_TIMESTAMP_EPOCH_MILLISECONDS = 1_786_356_000_000L
internal const val EVENT_TIMESTAMP_EPOCH_MILLISECONDS = 1_786_473_000_000L
internal const val SNAPSHOT_SEQUENCE = 100L
