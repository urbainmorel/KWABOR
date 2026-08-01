package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreModelsTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun defaultChips_returnsDistinctOptionsForEachTab() {
        assertEquals(
            listOf(strings.history, strings.nature, strings.markets),
            ExploreTab.Places.defaultChips(strings).map { chip -> chip.label },
        )
        assertEquals(
            listOf(strings.culture),
            ExploreTab.Events.defaultChips(strings).map { chip -> chip.label },
        )
        assertEquals(
            listOf(strings.restaurants, strings.hotels, strings.touristGuides),
            ExploreTab.HotelsRestaurants.defaultChips(strings).map { chip -> chip.label },
        )
    }

    @Test
    fun exploreUiState_reportsEmptyOnlyWhenNoLoadingErrorOrListings() {
        val state = ExploreUiState(
            cityLabel = strings.currentCity,
            selectedTab = ExploreTab.Places,
            selectedChipId = null,
            chips = ExploreTab.Places.defaultChips(strings),
            listings = emptyList(),
        )

        assertTrue(state.isEmpty)
        assertFalse(state.hasError)
        assertFalse(state.copy(isRefreshing = true).isEmpty)
    }

    @Test
    fun exploreUiState_prioritizesErrorOverEmpty() {
        val state = ExploreUiState(
            cityLabel = strings.currentCity,
            selectedTab = ExploreTab.Places,
            selectedChipId = null,
            chips = ExploreTab.Places.defaultChips(strings),
            listings = emptyList(),
            errorMessage = strings.errorStateTitle,
        )

        assertTrue(state.hasError)
        assertFalse(state.isEmpty)
    }

    @Test
    fun sampleExploreUiState_hasVisibleListingsAndSelectedChip() {
        val state = sampleExploreUiState(strings)

        assertEquals(strings.currentCity, state.cityLabel)
        assertEquals(ExploreTab.Places, state.selectedTab)
        assertEquals("heritage-historique", state.selectedChipId)
        assertTrue(state.listings.isNotEmpty())
    }

    @Test
    fun initialExploreUiState_usesRequestTabAndChipsWithoutSampleContent() {
        val state = initialExploreUiState(
            strings = strings,
            request = ExploreLoadRequest(
                selectedTab = ExploreTab.HotelsRestaurants,
                selectedChipId = "commercial-hotel",
            ),
        )

        assertEquals(ExploreTab.HotelsRestaurants, state.selectedTab)
        assertEquals("commercial-hotel", state.selectedChipId)
        assertTrue(state.chips.isEmpty())
        assertTrue(state.listings.isEmpty())
    }
}
