package com.kwabor.shared.data.local

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.MoneyXof
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExploreCacheMappingsTest {
    @Test
    fun listingMapping_roundTripsEveryDomainEnumAndNullablePlacement() {
        val baseline = listingSummary()
        ListingType.entries.forEach { type ->
            assertRoundTrip(baseline.copy(type = type))
        }
        ListingClass.entries.forEach { listingClass ->
            assertRoundTrip(baseline.copy(listingClass = listingClass))
        }
        ListingStatus.entries.forEach { status ->
            assertRoundTrip(baseline.copy(status = status))
        }
        assertRoundTrip(
            baseline.copy(
                priceFromXof = null,
                ratingAverage = null,
                sponsoredUntilEpochMilliseconds = null,
                isSponsoredPlacement = null,
            ),
        )
    }

    @Test
    fun listingMapping_rejectsCorruptPersistedValues() {
        val validRecord = listingSummary()
            .toCachedRecord()

        assertFailsWith<CorruptExploreCacheException> {
            validRecord.copy(listing = validRecord.listing.copy(listingType = "unknown")).toDomain()
        }
        assertFailsWith<CorruptExploreCacheException> {
            validRecord.copy(listing = validRecord.listing.copy(priceFromXof = -1)).toDomain()
        }
        assertFailsWith<CorruptExploreCacheException> {
            validRecord.copy(listing = validRecord.listing.copy(likesCount = -1)).toDomain()
        }
    }

    private fun assertRoundTrip(summary: ListingSummary) {
        assertEquals(summary, summary.toCachedRecord().toDomain())
    }
}

private fun ListingSummary.toCachedRecord(): ExploreCachedListingRecord {
    val item = toExploreCacheSnapshotItemEntity(
        snapshotKey = SNAPSHOT_KEY,
        position = 0,
    )
    return ExploreCachedListingRecord(
        listing = toExploreCachedListingEntity(cachedAtEpochMilliseconds = CACHED_AT),
        position = item.position,
        isSponsoredPlacement = item.isSponsoredPlacement,
    )
}

internal fun listingSummary(
    id: String = "listing-1",
    name: String = "Restaurant Kwabor",
    isSponsoredPlacement: Boolean? = true,
): ListingSummary = ListingSummary(
    id = id,
    type = ListingType.Establishment,
    listingClass = ListingClass.Commercial,
    status = ListingStatus.Published,
    name = name,
    cityId = "cotonou",
    categoryId = "restaurants",
    coverImageUrl = "https://cdn.kwabor.test/$id.jpg",
    priceFromXof = moneyXof(15_000),
    ratingAverage = 4.5,
    likesCount = 12,
    verified = true,
    sponsoredUntilEpochMilliseconds = 1_783_073_730_000,
    isSponsoredPlacement = isSponsoredPlacement,
)

private fun moneyXof(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("Invalid test money fixture.")
}

private const val SNAPSHOT_KEY = "explore:cotonou:establishments"
private const val CACHED_AT = 1_783_073_730_000
