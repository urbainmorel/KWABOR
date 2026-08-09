package com.kwabor.shared.i18n

import com.kwabor.shared.domain.i18n.AppLocale
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoritesStringsTest {
    @Test
    fun frenchCatalogExposesFiltersEmptyStateAndEndedAccessibilityCopy() {
        val strings = stringsFor(AppLocale.French).favorites

        assertEquals("Favoris", strings.title)
        assertEquals("Tous", strings.allFilter)
        assertEquals("Lieux", strings.placesFilter)
        assertEquals("Événements", strings.eventsFilter)
        assertEquals("Hôtels & Restaurants", strings.hotelsRestaurantsFilter)
        assertEquals("Aucun favori", strings.emptyTitle)
        assertEquals("Terminé", strings.eventEnded)
        assertEquals("Événement terminé", strings.eventEndedAccessibility)
    }
}
