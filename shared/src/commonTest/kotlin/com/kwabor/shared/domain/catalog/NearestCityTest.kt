package com.kwabor.shared.domain.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearestCityTest {
    private val cities = listOf(
        City(id = "cotonou", name = "Cotonou", latitude = 6.3703, longitude = 2.3912),
        City(id = "parakou", name = "Parakou", latitude = 9.3372, longitude = 2.6303),
    )

    @Test
    fun nearestCity_returnsClosestBeninCity() {
        val result = nearestCity(cities, GeoPoint(latitude = 6.40, longitude = 2.40))

        assertEquals("cotonou", result?.id)
    }

    @Test
    fun nearestCity_rejectsLocationOutsideBeninBounds() {
        val result = nearestCity(cities, GeoPoint(latitude = 48.8566, longitude = 2.3522))

        assertNull(result)
    }

    @Test
    fun beninBoundary_acceptsCitiesAndKnownBorderVertices() {
        val locations = listOf(
            GeoPoint(latitude = 6.3703, longitude = 2.3912),
            GeoPoint(latitude = 9.3372, longitude = 2.6303),
            GeoPoint(latitude = 10.3042, longitude = 1.3796),
            GeoPoint(latitude = 6.259573, longitude = 1.631896),
            GeoPoint(latitude = 6.466417, longitude = 2.704951),
        )

        locations.forEach { location -> assertTrue(location.isWithinBeninBounds) }
    }

    @Test
    fun beninBoundary_rejectsLomeLagosAndPointsBeyondBorder() {
        val locations = listOf(
            GeoPoint(latitude = 6.1319, longitude = 1.2228),
            GeoPoint(latitude = 6.5244, longitude = 3.3792),
            GeoPoint(latitude = 6.259573, longitude = 1.60),
            GeoPoint(latitude = 6.466417, longitude = 2.80),
        )

        locations.forEach { location -> assertFalse(location.isWithinBeninBounds) }
    }

    @Test
    fun nearestCity_ignoresNonBeninAndUnlocatedEntries() {
        val result = nearestCity(
            cities = listOf(
                City(id = "missing", name = "Sans position"),
                City(id = "foreign", name = "Étranger", countryCode = "TG", latitude = 6.2, longitude = 1.2),
            ),
            location = GeoPoint(latitude = 6.3, longitude = 1.5),
        )

        assertNull(result)
    }
}
