package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.City
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreCityCoordinateValidationTest {
    @Test
    fun cityWithBeninCountryCodeAndCoordinatesOutsideBeninIsRejected() {
        val cities = listOf(
            City(
                id = "invalid-city",
                name = "Ville hors frontière",
                countryCode = "BJ",
                latitude = 48.8566,
                longitude = 2.3522,
            ),
        )

        assertFalse(cities.areCitiesValidForExploreCache())
    }

    @Test
    fun cityWithCoordinatesInsideBeninIsAccepted() {
        val cities = listOf(
            City(
                id = "cotonou",
                name = "Cotonou",
                countryCode = "BJ",
                latitude = 6.3703,
                longitude = 2.3912,
            ),
        )

        assertTrue(cities.areCitiesValidForExploreCache())
    }
}
