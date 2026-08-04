package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.observability.AnalyticsEntityType
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.domain.observability.AnalyticsSessionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExploreAnalyticsTest {
    @Test
    fun `protected replay maps every explore tab to its real entity type`() {
        val expectedTypes = mapOf(
            ExploreTab.Places to AnalyticsEntityType.Place,
            ExploreTab.Events to AnalyticsEntityType.Event,
            ExploreTab.HotelsRestaurants to AnalyticsEntityType.Establishment,
        )

        expectedTypes.forEach { (tab, expectedType) ->
            val event = requireNotNull(exploreState(tab = tab).protectedActionReplayedAnalyticsEvent(LISTING_ID))
            assertEquals(expectedType, event.context.entityType)
        }
    }

    @Test
    fun `protected replay preserves listing city sponsored source and display currency`() {
        val event = requireNotNull(
            exploreState(
                tab = ExploreTab.Events,
                sponsored = true,
                currency = KwaborCurrency.Eur,
            ).protectedActionReplayedAnalyticsEvent(LISTING_ID),
        )

        assertEquals(AnalyticsEventName.ProtectedActionReplayed, event.name)
        assertEquals(LISTING_ID, event.context.entityId)
        assertEquals(CITY_ID, event.context.cityId)
        assertEquals(AnalyticsSessionSource.Sponsored, event.context.sessionSource)
        assertEquals(KwaborCurrency.Eur, event.context.displayCurrency)
    }

    @Test
    fun `protected replay drops analytics when an opaque identifier is unsafe`() {
        val state = exploreState(tab = ExploreTab.Places, listingId = "unsafe/listing")

        assertNull(state.protectedActionReplayedAnalyticsEvent("unsafe/listing"))
    }
}

private fun exploreState(
    tab: ExploreTab,
    listingId: String = LISTING_ID,
    sponsored: Boolean = false,
    currency: KwaborCurrency = KwaborCurrency.Xof,
): ExploreUiState = ExploreUiState(
    cityLabel = "Cotonou",
    selectedCityId = CITY_ID,
    selectedTab = tab,
    selectedChipId = null,
    chips = emptyList(),
    listings = listOf(
        ExploreListingItem(
            id = listingId,
            title = "Élément catalogue",
            cityLabel = "Cotonou",
            coverImageUrl = null,
            price = null,
            sponsored = sponsored,
            cityId = CITY_ID,
        ),
    ),
    currency = currency,
)

private const val LISTING_ID = "listing-1"
private const val CITY_ID = "cotonou"
