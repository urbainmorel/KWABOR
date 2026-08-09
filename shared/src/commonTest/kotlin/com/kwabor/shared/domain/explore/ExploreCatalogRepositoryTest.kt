package com.kwabor.shared.domain.explore

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExploreCatalogRepositoryTest {
    @Test
    fun request_acceptsResolvedEstablishmentCriteria() {
        ExploreCatalogRequest(
            listingType = ListingType.Establishment,
            cityId = "porto-novo",
            categoryId = "commercial-restaurant",
            listingClass = ListingClass.Commercial,
            sort = ExploreSort.Popularity,
            priceMinXof = 0,
            priceMaxXof = 25_000,
            cursor = "opaque-cursor",
            limit = 20,
        )
    }

    @Test
    fun request_rejectsCriteriaUnsupportedByTheVersionedRpc() {
        assertFailsWith<IllegalArgumentException> {
            ExploreCatalogRequest(
                listingType = ListingType.Place,
                sort = ExploreSort.TemporalProximity,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreCatalogRequest(
                listingType = ListingType.Event,
                sort = ExploreSort.Popularity,
                priceMinXof = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreCatalogRequest(
                listingType = ListingType.Place,
                sort = ExploreSort.Popularity,
                eventWindow = ExploreEventWindow(
                    startAtEpochMilliseconds = 1_786_287_600_000,
                    endExclusiveAtEpochMilliseconds = 1_786_374_000_000,
                ),
            )
        }
    }

    @Test
    fun eventWindow_requiresAStrictHalfOpenMobileRange() {
        assertFailsWith<IllegalArgumentException> {
            ExploreEventWindow(
                startAtEpochMilliseconds = 1_786_287_600_000,
                endExclusiveAtEpochMilliseconds = 1_786_287_600_000,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreEventWindow(
                startAtEpochMilliseconds = 253_402_300_799_999,
                endExclusiveAtEpochMilliseconds = 253_402_300_800_000,
            )
        }
    }

    @Test
    fun page_requiresSnapshotForContentAndNullSnapshotForEmptyTerminalPage() {
        ExploreCatalogPage(
            items = emptyList(),
            nextCursor = null,
            snapshotAtEpochMicroseconds = null,
        )
        assertFailsWith<IllegalArgumentException> {
            ExploreCatalogPage(
                items = listOf(summary()),
                nextCursor = null,
                snapshotAtEpochMicroseconds = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExploreCatalogPage(
                items = emptyList(),
                nextCursor = "cursor",
                snapshotAtEpochMicroseconds = 1,
            )
        }
    }
}

private fun summary(): ListingSummary = ListingSummary(
    id = "10000000-0000-4000-8000-000000000001",
    type = ListingType.Place,
    listingClass = ListingClass.Heritage,
    status = ListingStatus.Published,
    name = "Place Kwabor",
    cityId = "cotonou",
    categoryId = "heritage-historique",
    coverImageUrl = null,
    priceFromXof = null,
    ratingAverage = null,
    likesCount = 0,
    verified = false,
    sponsoredUntilEpochMilliseconds = null,
)
