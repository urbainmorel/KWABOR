package com.kwabor.shared.domain.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ListingSearchQueryTest {
    @Test
    fun acceptsCanonicalBoundaryLengths() {
        assertEquals("a", ListingSearchQuery("a").text)
        assertEquals(120, ListingSearchQuery("a".repeat(120)).text.length)
        assertEquals(
            100,
            ListingSearchQuery(
                text = "restaurant",
                filters = ListingFilters(cityId = "a".repeat(100)),
            ).filters.cityId?.length,
        )
    }

    @Test
    fun rejectsNonCanonicalOrInvalidText() {
        listOf(
            "",
            "   ",
            " restaurant",
            "restaurant ",
            "unsafe\nquery",
            "a".repeat(121),
        ).forEach { text ->
            assertFailsWith<IllegalArgumentException>(text) {
                ListingSearchQuery(text)
            }
        }
    }

    @Test
    fun rejectsFiltersOutsideThePublishedSearchContract() {
        val invalidFilters = listOf(
            ListingFilters(onlyPublished = false),
            ListingFilters(cityId = ""),
            ListingFilters(categoryId = "unsafe\tcategory"),
            ListingFilters(cityId = "a".repeat(101)),
        )

        invalidFilters.forEach { filters ->
            assertFailsWith<IllegalArgumentException> {
                ListingSearchQuery(text = "restaurant", filters = filters)
            }
        }
    }
}
