package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.notification.NotificationContent
import com.kwabor.shared.domain.notification.NotificationFamilyPreference
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationListingTarget
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class NotificationPresenterTest {
    @Test
    fun portoNovoMidnightDefinesTodayInsteadOfA24HourWindow() {
        val presenter =
            NotificationPresenter(
                clockProvider = FixedNotificationClock(epochMilliseconds("2026-08-10T23:30:00Z")),
            )
        val result =
            presenter.present(
                listOf(
                    suggestionItem(
                        id = "today",
                        sequence = 2L,
                        createdAt = epochMilliseconds("2026-08-10T23:15:00Z"),
                    ),
                    suggestionItem(
                        id = "before-local-midnight",
                        sequence = 1L,
                        createdAt = epochMilliseconds("2026-08-10T22:45:00Z"),
                    ),
                ),
            ).requireContent()

        assertEquals(
            listOf(NotificationTemporalGroup.Today, NotificationTemporalGroup.ThisWeek),
            result.sections.map(NotificationSectionUiModel::group),
        )
        assertEquals(listOf("today"), result.sections[0].items.map(NotificationItemUiModel::id))
        assertEquals(listOf("before-local-midnight"), result.sections[1].items.map(NotificationItemUiModel::id))
    }

    @Test
    fun injectedZoneControlsTheCivilDayBoundary() {
        val clock = FixedNotificationClock(epochMilliseconds("2026-08-10T23:30:00Z"))
        val item =
            suggestionItem(
                id = "boundary",
                sequence = 1L,
                createdAt = epochMilliseconds("2026-08-10T22:45:00Z"),
            )

        val beninGroup = NotificationPresenter(clock).present(listOf(item)).requireContent().sections.single().group
        val utcGroup =
            NotificationPresenter(clock, TimeZone.of("UTC"))
                .present(listOf(item))
                .requireContent()
                .sections
                .single()
                .group

        assertEquals(NotificationTemporalGroup.ThisWeek, beninGroup)
        assertEquals(NotificationTemporalGroup.Today, utcGroup)
    }

    @Test
    fun weekStartsOnMondayInPortoNovo() {
        val presenter =
            NotificationPresenter(
                clockProvider = FixedNotificationClock(epochMilliseconds("2026-08-16T12:00:00Z")),
            )
        val result =
            presenter.present(
                listOf(
                    suggestionItem(
                        id = "monday",
                        sequence = 2L,
                        createdAt = epochMilliseconds("2026-08-10T12:00:00Z"),
                    ),
                    suggestionItem(
                        id = "previous-sunday",
                        sequence = 1L,
                        createdAt = epochMilliseconds("2026-08-09T12:00:00Z"),
                    ),
                ),
            ).requireContent()

        assertEquals(
            listOf(NotificationTemporalGroup.ThisWeek, NotificationTemporalGroup.Earlier),
            result.sections.map(NotificationSectionUiModel::group),
        )
        assertEquals("Cette semaine", result.sections[0].title)
        assertEquals("Plus tôt", result.sections[1].title)
    }

    @Test
    fun fourAllowedTemplatesRenderOnlyLocalFrenchCopy() {
        val now = epochMilliseconds("2026-08-11T12:00:00Z")
        val presenter = NotificationPresenter(FixedNotificationClock(now))
        val result =
            presenter.present(
                listOf(
                    notificationItem(
                        id = "suggestion",
                        sequence = 4L,
                        createdAt = now,
                        content = suggestionContent("Ganvié"),
                    ),
                    notificationItem(
                        id = "sponsored",
                        sequence = 3L,
                        createdAt = now,
                        content = sponsoredContent("Musée de Ouidah"),
                    ),
                    notificationItem(
                        id = "new-listing",
                        sequence = 2L,
                        createdAt = now,
                        content = newListingContent("Chez Maman Bénin", "Cotonou"),
                    ),
                    notificationItem(
                        id = "event",
                        sequence = 1L,
                        createdAt = now,
                        content = eventAlertContent("Festival des Masques", epochMilliseconds("2026-08-12T12:00:00Z")),
                    ),
                ),
            ).requireContent()
        val items = result.sections.single().items.associateBy(NotificationItemUiModel::id)

        val renderedCopies = items.mapValues { (_, item) -> item.text.title to item.text.excerpt }
        assertEquals(
            mapOf(
                "suggestion" to ("Pour vous" to "Découvrez Ganvié."),
                "sponsored" to ("À découvrir" to "Découvrez Musée de Ouidah."),
                "new-listing" to ("Nouveau près de Cotonou" to "Chez Maman Bénin vient d’être ajouté à Kwabor."),
                "event" to ("Événement à venir" to "Festival des Masques approche."),
            ),
            renderedCopies,
        )
        assertEquals("12 août", items.getValue("event").metadata.eventDateLabel)
    }

    @Test
    fun unknownTemplateFailsTheWholeProjectionClosed() {
        val now = epochMilliseconds("2026-08-11T12:00:00Z")
        val invalid =
            notificationItem(
                id = "invalid",
                sequence = 2L,
                createdAt = now,
                content =
                    NotificationContent.Suggestion(
                        titleKey = SPONSORED_TITLE_KEY,
                        bodyKey = SUGGESTION_BODY_KEY,
                        listingName = "Ganvié",
                    ),
            )
        val valid = suggestionItem(id = "valid", sequence = 1L, createdAt = now)

        val result = NotificationPresenter(FixedNotificationClock(now)).present(listOf(valid, invalid))

        assertIs<NotificationPresentationResult.InvalidPayload>(result)
    }

    @Test
    fun sponsoredRowsAlwaysCarryTheDedicatedYellowBadgeSemantic() {
        val now = epochMilliseconds("2026-08-11T12:00:00Z")
        val result =
            NotificationPresenter(FixedNotificationClock(now)).present(
                listOf(
                    notificationItem(
                        id = "sponsored",
                        sequence = 2L,
                        createdAt = now,
                        content = sponsoredContent("Ganvié"),
                    ),
                    suggestionItem(id = "suggestion", sequence = 1L, createdAt = now),
                ),
            ).requireContent()
        val items = result.sections.single().items.associateBy(NotificationItemUiModel::id)

        val sponsoredBadge = items.getValue("sponsored").metadata.sponsoredBadge
        assertEquals("Sponsorisé", sponsoredBadge?.label)
        assertEquals(NotificationSponsoredBadgeTone.SponsoredYellow, sponsoredBadge?.tone)
        assertNull(items.getValue("suggestion").metadata.sponsoredBadge)
    }

    @Test
    fun targetCityIdIsPreservedAsOpaqueAnalyticsContext() {
        val now = epochMilliseconds("2026-08-11T12:00:00Z")
        val item =
            notificationItem(
                id = "targeted",
                sequence = 1L,
                createdAt = now,
                content = suggestionContent("Ganvié"),
                target =
                    NotificationListingTarget(
                        listingId = "listing-a",
                        listingType = ListingType.Place,
                        listingName = "Ganvié",
                        cityId = "so-ava",
                        cityName = "Sô-Ava",
                        coverImage = null,
                        eventStartAtEpochMilliseconds = null,
                    ),
            )

        val presented =
            NotificationPresenter(FixedNotificationClock(now))
                .present(listOf(item))
                .requireContent()
                .sections
                .single()
                .items
                .single()

        assertEquals("so-ava", presented.target?.cityId)
    }

    @Test
    fun analyticsUnsafeTargetCityIdsAreDroppedInsteadOfReachingNativeTrackers() {
        val now = epochMilliseconds("2026-08-11T12:00:00Z")
        val items =
            listOf(
                notificationItem(
                    id = "too-long",
                    sequence = 2L,
                    createdAt = now,
                    content = suggestionContent("Ganvié"),
                    target = listingTarget(cityId = "a".repeat(65)),
                ),
                notificationItem(
                    id = "invalid-character",
                    sequence = 1L,
                    createdAt = now,
                    content = suggestionContent("Ganvié"),
                    target = listingTarget(cityId = "porto novo"),
                ),
            )

        val presented =
            NotificationPresenter(FixedNotificationClock(now))
                .present(items)
                .requireContent()
                .sections
                .single()
                .items

        assertTrue(presented.all { item -> item.target?.cityId == null })
    }

    @Test
    fun hiddenRowsDoNotReachPresentationGroups() {
        val now = epochMilliseconds("2026-08-11T12:00:00Z")
        val hidden =
            notificationItem(
                id = "hidden",
                sequence = 1L,
                createdAt = now,
                content = suggestionContent("Ganvié"),
            ).copy(
                seenAtEpochMilliseconds = now,
                hiddenAtEpochMilliseconds = now,
            )

        val result = NotificationPresenter(FixedNotificationClock(now)).present(listOf(hidden)).requireContent()

        assertTrue(result.sections.isEmpty())
    }

    @Test
    fun preferencesRenderAllFourFamiliesInStableFrenchOrder() {
        val preferences =
            NotificationPreferences(
                entries =
                    NotificationPreferenceFamily.entries.map { family ->
                        NotificationFamilyPreference(
                            family = family,
                            enabled = family == NotificationPreferenceFamily.EventAlert,
                            updatedAtEpochMilliseconds = null,
                        )
                    },
            )

        val items = NotificationPresenter(FixedNotificationClock(now = 0L)).presentPreferences(preferences)

        assertEquals(NotificationPreferenceFamily.entries, items.map(NotificationPreferenceUiModel::family))
        assertEquals(
            listOf(
                "Suggestions pour vous",
                "Contenus sponsorisés",
                "Nouveautés près de vous",
                "Alertes d’événements",
            ),
            items.map(NotificationPreferenceUiModel::title),
        )
        assertEquals(listOf(false, false, false, true), items.map(NotificationPreferenceUiModel::enabled))
    }
}

private fun NotificationPresentationResult.requireContent(): NotificationPresentationResult.Content = assertIs(this)

private fun suggestionItem(
    id: String,
    sequence: Long,
    createdAt: Long,
): NotificationInboxItem =
    notificationItem(
        id = id,
        sequence = sequence,
        createdAt = createdAt,
        content = suggestionContent("Ganvié"),
    )

private fun notificationItem(
    id: String,
    sequence: Long,
    createdAt: Long,
    content: NotificationContent,
    target: NotificationListingTarget? = null,
): NotificationInboxItem =
    NotificationInboxItem(
        id = id,
        sequence = sequence,
        kind =
            when (content) {
                is NotificationContent.Suggestion -> com.kwabor.shared.domain.notification.NotificationKind.Suggestion
                is NotificationContent.Sponsored -> com.kwabor.shared.domain.notification.NotificationKind.Sponsored
                is NotificationContent.NewListing -> com.kwabor.shared.domain.notification.NotificationKind.NewListing
                is NotificationContent.EventAlert -> com.kwabor.shared.domain.notification.NotificationKind.EventAlert
            },
        content = content,
        target = target,
        seenAtEpochMilliseconds = null,
        readAtEpochMilliseconds = null,
        hiddenAtEpochMilliseconds = null,
        createdAtEpochMilliseconds = createdAt,
    )

private fun suggestionContent(listingName: String): NotificationContent.Suggestion =
    NotificationContent.Suggestion(
        titleKey = SUGGESTION_TITLE_KEY,
        bodyKey = SUGGESTION_BODY_KEY,
        listingName = listingName,
    )

private fun sponsoredContent(listingName: String): NotificationContent.Sponsored =
    NotificationContent.Sponsored(
        titleKey = SPONSORED_TITLE_KEY,
        bodyKey = SPONSORED_BODY_KEY,
        listingName = listingName,
    )

private fun newListingContent(
    listingName: String,
    cityName: String,
): NotificationContent.NewListing =
    NotificationContent.NewListing(
        titleKey = NEW_LISTING_TITLE_KEY,
        bodyKey = NEW_LISTING_BODY_KEY,
        listingName = listingName,
        cityName = cityName,
    )

private fun eventAlertContent(
    listingName: String,
    eventStartAt: Long,
): NotificationContent.EventAlert =
    NotificationContent.EventAlert(
        titleKey = EVENT_ALERT_TITLE_KEY,
        bodyKey = EVENT_ALERT_BODY_KEY,
        listingName = listingName,
        eventStartAtEpochMilliseconds = eventStartAt,
    )

private fun listingTarget(cityId: String?): NotificationListingTarget =
    NotificationListingTarget(
        listingId = "listing-a",
        listingType = ListingType.Place,
        listingName = "Ganvié",
        cityId = cityId,
        cityName = "Sô-Ava",
        coverImage = null,
        eventStartAtEpochMilliseconds = null,
    )

private fun epochMilliseconds(value: String): Long = Instant.parse(value).toEpochMilliseconds()

private class FixedNotificationClock(private val now: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = now
}

private const val SUGGESTION_TITLE_KEY = "notification.suggestion.title"
private const val SUGGESTION_BODY_KEY = "notification.suggestion.body"
private const val SPONSORED_TITLE_KEY = "notification.sponsored.title"
private const val SPONSORED_BODY_KEY = "notification.sponsored.body"
private const val NEW_LISTING_TITLE_KEY = "notification.new_listing.title"
private const val NEW_LISTING_BODY_KEY = "notification.new_listing.body"
private const val EVENT_ALERT_TITLE_KEY = "notification.event_alert.title"
private const val EVENT_ALERT_BODY_KEY = "notification.event_alert.body"
