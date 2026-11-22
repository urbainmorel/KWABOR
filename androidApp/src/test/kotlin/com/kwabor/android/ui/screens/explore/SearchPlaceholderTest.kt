package com.kwabor.android.ui.screens.explore

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.search.SearchContext
import com.kwabor.shared.presentation.search.SearchScope
import com.kwabor.shared.presentation.search.SearchUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchPlaceholderTest {
    @Test
    fun placeholderFollowsTabCategoryAndSearchScope() {
        val strings = stringsFor(AppLocale.French).search
        val places = SearchUiState()
        val events = places.copy(context = SearchContext(selectedTab = ExploreTab.Events))
        val businesses = SearchContext(selectedTab = ExploreTab.HotelsRestaurants)
        val hotels = places.copy(context = businesses.copy(selectedChipId = "commercial-hotel"))
        val restaurants = places.copy(context = businesses.copy(selectedChipId = "commercial-restaurant"))
        val guides = places.copy(context = businesses.copy(selectedChipId = "guide-touristique"))

        assertEquals(strings.placePlaceholder, places.searchPlaceholder(strings))
        assertEquals(strings.eventPlaceholder, events.searchPlaceholder(strings))
        assertEquals(strings.establishmentPlaceholder, places.copy(context = businesses).searchPlaceholder(strings))
        assertEquals(strings.hotelPlaceholder, hotels.searchPlaceholder(strings))
        assertEquals(strings.restaurantPlaceholder, restaurants.searchPlaceholder(strings))
        assertEquals(strings.guidePlaceholder, guides.searchPlaceholder(strings))
        assertEquals(
            strings.placeholder,
            hotels.copy(scope = SearchScope.All, isActive = true).searchPlaceholder(strings),
        )
    }
}
