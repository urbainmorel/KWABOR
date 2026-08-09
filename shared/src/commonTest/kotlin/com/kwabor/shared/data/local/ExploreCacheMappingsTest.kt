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
                coverImageAlt = null,
                priceFromXof = null,
                ratingAverage = null,
                viewsCount = null,
                sponsoredUntilEpochMilliseconds = null,
                isSponsoredPlacement = null,
                eventStartAtEpochMilliseconds = null,
                eventEndAtEpochMilliseconds = null,
                isEventEnded = null,
            ),
        )
        assertRoundTrip(baseline.copy(coverImageAlt = "A".repeat(LONG_ALT_LENGTH)))
        assertRoundTrip(
            baseline.copy(
                type = ListingType.Event,
                listingClass = ListingClass.Event,
                eventStartAtEpochMilliseconds = EVENT_START_AT,
                eventEndAtEpochMilliseconds = EVENT_END_AT,
                isEventEnded = false,
                isSponsoredPlacement = false,
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
        assertFailsWith<CorruptExploreCacheException> {
            validRecord.copy(listing = validRecord.listing.copy(viewsCount = -1)).toDomain()
        }
        assertFailsWith<CorruptExploreCacheException> {
            validRecord.copy(
                listing = validRecord.listing.copy(
                    eventStartAtEpochMilliseconds = EVENT_END_AT,
                    eventEndAtEpochMilliseconds = EVENT_START_AT,
                ),
            ).toDomain()
        }
        assertFailsWith<CorruptExploreCacheException> {
            validRecord.copy(listing = validRecord.listing.copy(coverImageAlt = " Alt non canonique")).toDomain()
        }
        assertFailsWith<CorruptExploreCacheException> {
            validRecord.copy(listing = validRecord.listing.copy(coverImageAlt = "\uD800")).toDomain()
        }
    }

    @Test
    fun canonicalEventMappingRemainsReadableWithoutSnapshotSpecificEndedState() {
        val event = listingSummary(
            isEventEnded = true,
            isSponsoredPlacement = false,
        ).copy(
            type = ListingType.Event,
            listingClass = ListingClass.Event,
            eventStartAtEpochMilliseconds = -1_000,
            eventEndAtEpochMilliseconds = 1_000,
        )
        val entity = event.toExploreCachedListingEntity(cachedAtEpochMilliseconds = CACHED_AT)

        val canonicalRead = entity.toDomain()

        assertEquals(-1_000L, canonicalRead.eventStartAtEpochMilliseconds)
        assertEquals(1_000L, canonicalRead.eventEndAtEpochMilliseconds)
        assertEquals(null, canonicalRead.isEventEnded)
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
        isEventEnded = item.isEventEnded,
    )
}

internal fun listingSummary(
    id: String = "listing-1",
    name: String = "Restaurant Kwabor",
    isSponsoredPlacement: Boolean? = true,
    coverImageAlt: String? = "Façade du restaurant Kwabor",
    isEventEnded: Boolean? = null,
): ListingSummary = ListingSummary(
    id = id,
    type = ListingType.Establishment,
    listingClass = ListingClass.Commercial,
    status = ListingStatus.Published,
    name = name,
    cityId = "cotonou",
    categoryId = "restaurants",
    coverImageUrl = "https://cdn.kwabor.test/$id.jpg",
    coverImageAlt = coverImageAlt,
    priceFromXof = moneyXof(15_000),
    ratingAverage = 4.5,
    likesCount = 12,
    viewsCount = 48,
    verified = true,
    sponsoredUntilEpochMilliseconds = 1_783_073_730_000,
    isSponsoredPlacement = isSponsoredPlacement,
    eventStartAtEpochMilliseconds = null,
    eventEndAtEpochMilliseconds = null,
    isEventEnded = isEventEnded,
)

private fun moneyXof(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("Invalid test money fixture.")
}

private const val SNAPSHOT_KEY = "explore:cotonou:establishments"
private const val CACHED_AT = 1_783_073_730_000
private const val EVENT_START_AT = 1_783_073_730_000
private const val EVENT_END_AT = 1_783_077_330_000
private const val LONG_ALT_LENGTH = 3_000
