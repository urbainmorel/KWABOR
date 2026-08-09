package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExploreFeedCacheKeyTest {
    @Test
    fun keyIsVersionedAndCoversEveryCurrentQueryField() {
        val query = ExploreFeedQuery(
            filters = ListingFilters(
                cityId = "ouidah",
                categoryId = "art-nature",
                listingType = ListingType.Place,
                listingClass = ListingClass.Heritage,
            ),
            pageSize = 12,
        )

        assertEquals(
            "explore-feed:v2|city=v6:ouidah|category=v10:art-nature|type=place|" +
                "class=heritage|published=1|pageSize=12",
            query.toCacheKey(),
        )
        assertEquals(
            "explore-feed:v1|city=v6:ouidah|category=v10:art-nature|type=place|" +
                "class=heritage|published=1|pageSize=12",
            query.toLegacyCacheKey(),
        )
        assertTrue(query.toCacheKey().isExploreV2FeedCacheKey())
        assertFalse(query.toLegacyCacheKey().isExploreV2FeedCacheKey())
        assertFalse("explore-feed:v20|city=n".isExploreV2FeedCacheKey())
    }

    @Test
    fun queryRejectsUnsafeIdentifiersAndNonPersistablePageSizes() {
        assertFailsWith<IllegalArgumentException> {
            ExploreFeedQuery(filters = ListingFilters(cityId = " ", listingType = ListingType.Place))
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreFeedQuery(
                filters = ListingFilters(cityId = "a|category=v1:b", listingType = ListingType.Place),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreFeedQuery(
                filters = ListingFilters(cityId = "a".repeat(101), listingType = ListingType.Place),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreFeedQuery(pageSize = 21)
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreFeedQuery(filters = ListingFilters())
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreFeedQuery(filters = ListingFilters(listingType = ListingType.Place, onlyPublished = false))
        }
    }
}
