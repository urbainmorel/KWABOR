package com.kwabor.android.ui.screens.favorites

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.favorites.FavoriteListingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritesScreenPresentationTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun cardMapping_isPrivateUnsponsoredFavoriteAndPreservesEndedState() {
        val item = endedEvent()

        val card = item.toFavoriteCardState()

        assertTrue(card.favorited)
        assertTrue(card.eventEnded)
        assertFalse(card.sponsored)
        assertEquals(item.liked, card.liked)
    }

    @Test
    fun accessibilityDescription_announcesOpenActionAndContentWithoutDuplicatingEndedRibbon() {
        val item = endedEvent()

        val description = item.favoriteCardAccessibilityDescription(strings)

        listOf(
            strings.favorites.openListing,
            item.title,
            item.cityLabel,
            requireNotNull(item.coverImageAlt),
            strings.detail.price,
        ).forEach { expected -> assertTrue(expected in description) }
        assertFalse(strings.favorites.eventEndedAccessibility in description)
    }
}

private fun endedEvent(): FavoriteListingItem = FavoriteListingItem(
    id = "00000000-0000-4000-8000-000000000001",
    type = ListingType.Event,
    listingClass = ListingClass.Event,
    title = "Festival des masques",
    cityLabel = "Porto-Novo",
    coverImageUrl = "https://cdn.kwabor.example/events/masques.jpg",
    coverImageAlt = "Danseurs masqués à Porto-Novo",
    price = null,
    ratingLabel = "4,8",
    likesCount = 12,
    verified = true,
    liked = false,
    favoritedAtEpochMilliseconds = 1_000L,
    eventStartAtEpochMilliseconds = 500L,
    eventEndAtEpochMilliseconds = 900L,
    isEventEnded = true,
)
