package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ExploreFeedCacheKeyTest {
    @Test
    fun keyIsVersionedAndCoversEveryCurrentQueryField() {
        val query = ExploreFeedQuery(
            filters = ListingFilters(
                cityId = "ouidah",
                categoryId = "art|nature",
                listingType = ListingType.Place,
                listingClass = ListingClass.Heritage,
                onlyPublished = false,
            ),
            pageSize = 12,
        )

        assertEquals(
            "explore-feed:v1|city=v6:ouidah|category=v10:art|nature|type=place|" +
                "class=heritage|published=0|pageSize=12",
            query.toCacheKey(),
        )
    }

    @Test
    fun lengthPrefixesPreventDelimiterCollisions() {
        val first = ExploreFeedQuery(filters = ListingFilters(cityId = "a|category=v1:b"))
        val second = ExploreFeedQuery(filters = ListingFilters(cityId = "a", categoryId = "b"))

        assertNotEquals(first.toCacheKey(), second.toCacheKey())
    }

    @Test
    fun queryRejectsUnsafeIdentifiersAndNonPersistablePageSizes() {
        assertFailsWith<IllegalArgumentException> {
            ExploreFeedQuery(filters = ListingFilters(cityId = " "))
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreFeedQuery(pageSize = 21)
        }
    }
}
