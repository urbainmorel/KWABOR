package com.kwabor.android.ui.screens.explore

import androidx.compose.ui.unit.dp
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.ui.components.PriceTagMode
import com.kwabor.android.ui.components.PriceTagOptions
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExploreListingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExploreCardPresentationTest {
    @Test
    fun v2ListingMapping_preservesNativeCardContentAndServerDecorations() {
        val listing = ExploreListingItem(
            id = "00000000-0000-4000-8000-000000000001",
            title = "Festival des masques",
            cityLabel = "Porto-Novo",
            coverImageUrl = "https://cdn.kwabor.example/events/masks.jpg",
            coverImageAlt = "Danseurs masqués sur la place",
            price = testMoney(),
            ratingLabel = "4,8",
            eventDateLabel = "20 juin ›",
            isEventEnded = true,
            sponsored = true,
            liked = true,
            favorited = true,
        )
        val priceOptions = PriceTagOptions(mode = PriceTagMode.Compact)

        val card = listing.toCardState(priceOptions)

        assertEquals(listing.title, card.title)
        assertEquals(listing.cityLabel, card.cityLabel)
        assertEquals(listing.coverImageUrl, card.coverImageUrl)
        assertEquals(listing.coverImageAlt, card.coverImageAlt)
        assertEquals(listing.price, card.price)
        assertEquals(priceOptions, card.priceOptions)
        assertEquals(listing.ratingLabel, card.ratingLabel)
        assertEquals(listing.eventDateLabel, card.eventDateLabel)
        assertTrue(card.eventEnded)
        assertTrue(card.sponsored)
        assertTrue(card.liked)
        assertTrue(card.favorited)
    }

    @Test
    fun gridPolicy_collapsesAtStrongFontScaleAndKeepsRegularBreakpoints() {
        assertEquals(
            KwaborSizing.EXPLORE_MOBILE_GRID_COLUMNS,
            exploreColumnCount(TEST_MOBILE_WIDTH_DP.dp, fontScale = REGULAR_FONT_SCALE),
        )
        assertEquals(
            KwaborSizing.EXPLORE_TABLET_GRID_COLUMNS,
            exploreColumnCount(TEST_TABLET_WIDTH_DP.dp, fontScale = REGULAR_FONT_SCALE),
        )
        assertEquals(
            1,
            exploreColumnCount(TEST_PHONE_WIDTH_DP.dp, fontScale = ACCESSIBILITY_TEST_FONT_SCALE),
        )
        assertEquals(1, exploreColumnCount(TEST_WIDE_WIDTH_DP.dp, fontScale = Float.NaN))
    }

    @Test
    fun accessibilitySummary_usesAltAndLetsSponsorReplaceRatingWithoutDuplicateTitle() {
        val strings = stringsFor(AppLocale.French)
        val sponsored = ExploreListingItem(
            id = "00000000-0000-4000-8000-000000000001",
            title = "Festival des masques",
            cityLabel = "Porto-Novo",
            coverImageUrl = null,
            coverImageAlt = "Danseurs masqués sur la place",
            price = null,
            ratingLabel = "4,8",
            sponsored = true,
        )

        val description = sponsored.cardAccessibilityDescription(strings)

        assertTrue(description.contains(sponsored.coverImageAlt.orEmpty()))
        assertTrue(description.contains(strings.sponsored))
        assertTrue(!description.contains("${strings.rating} 4,8"))
        assertEquals(1, description.split(sponsored.title).size - 1)
    }
}

private fun testMoney(): MoneyXof = when (val result = MoneyXof.fromAmount(TEST_PRICE_XOF)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("The fixed non-negative test price must be valid.")
}

private const val TEST_PRICE_XOF = 15_000L
private const val TEST_MOBILE_WIDTH_DP = 599
private const val TEST_TABLET_WIDTH_DP = 600
private const val TEST_PHONE_WIDTH_DP = 390
private const val TEST_WIDE_WIDTH_DP = 700
private const val REGULAR_FONT_SCALE = 1f
private const val ACCESSIBILITY_TEST_FONT_SCALE = 1.3f
