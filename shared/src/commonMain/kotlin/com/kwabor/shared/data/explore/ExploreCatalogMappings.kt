package com.kwabor.shared.data.explore

import com.kwabor.shared.data.catalog.toDatabaseValue
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreCatalogPage
import com.kwabor.shared.domain.explore.ExploreCatalogRequest
import com.kwabor.shared.domain.explore.ExploreSort
import com.kwabor.shared.domain.money.MoneyXof
import kotlin.time.Instant

internal fun ExploreCatalogRequest.toRpcParametersDto(): ExploreCatalogRpcParametersDto =
    ExploreCatalogRpcParametersDto(
        listingType = listingType.toDatabaseValue(),
        cityId = cityId,
        categoryId = categoryId,
        listingClass = listingClass?.toDatabaseValue(),
        sort = sort.toDatabaseValue(),
        priceMinXof = priceMinXof,
        priceMaxXof = priceMaxXof,
        eventWindowStart = eventWindow?.startAtEpochMilliseconds?.toExploreInstantText(),
        eventWindowEndExclusive = eventWindow?.endExclusiveAtEpochMilliseconds?.toExploreInstantText(),
        cursor = cursor,
        limit = limit,
    )

internal fun ExploreCatalogPageDto.toDomain(): ExploreCatalogPage = ExploreCatalogPage(
    items = items.map(ExploreCatalogRowDto::toDomain),
    nextCursor = nextCursor,
    snapshotAtEpochMicroseconds = snapshotAtEpochMicroseconds,
)

internal fun ExploreCatalogRowDto.toDomain(): ListingSummary = ListingSummary(
    id = id,
    type = type.toExploreListingType(),
    listingClass = listingClass.toExploreListingClass(),
    status = status.toExploreListingStatus(),
    name = name,
    cityId = cityId,
    categoryId = categoryId,
    coverImageUrl = coverImageUrl,
    priceFromXof = priceFromXof?.toExploreMoney(),
    ratingAverage = ratingAverage,
    likesCount = likesCount,
    verified = verified,
    sponsoredUntilEpochMilliseconds = sponsoredUntil?.toExploreInstant("sponsored_until")?.toEpochMilliseconds(),
    isSponsoredPlacement = isSponsoredPlacement,
    coverImageAlt = coverImageAlt,
    viewsCount = viewsCount,
    eventStartAtEpochMilliseconds = eventStartAt?.toExploreInstant("event_start_at")?.toEpochMilliseconds(),
    eventEndAtEpochMilliseconds = eventEndAt?.toExploreInstant("event_end_at")?.toEpochMilliseconds(),
    isEventEnded = isEventEnded,
)

private fun Long.toExploreInstantText(): String = try {
    Instant.fromEpochMilliseconds(this).toString()
} catch (exception: IllegalArgumentException) {
    invalidExploreCatalogValue("event_window", toString(), exception)
}

private fun ExploreSort.toDatabaseValue(): String = when (this) {
    ExploreSort.Popularity -> "popularity"
    ExploreSort.TemporalProximity -> "temporal_proximity"
}

private fun String.toExploreListingType(): ListingType = when (this) {
    "lieu" -> ListingType.Place
    "etablissement" -> ListingType.Establishment
    "evenement" -> ListingType.Event
    else -> invalidExploreCatalogValue("type", this)
}

private fun String.toExploreListingClass(): ListingClass = when (this) {
    "patrimonial" -> ListingClass.Heritage
    "commercial" -> ListingClass.Commercial
    "evenementiel" -> ListingClass.Event
    else -> invalidExploreCatalogValue("listing_class", this)
}

private fun String.toExploreListingStatus(): ListingStatus = when (this) {
    "publie" -> ListingStatus.Published
    else -> invalidExploreCatalogValue("status", this)
}

private fun Long.toExploreMoney(): MoneyXof = when (val result = MoneyXof.fromAmount(this)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> invalidExploreCatalogValue("price_from_xof", toString())
}

internal fun invalidExploreCatalogValue(fieldName: String, value: String, cause: Throwable? = null): Nothing {
    throw ExploreCatalogDataException.Unexpected(
        IllegalStateException("Invalid Explore catalog value for $fieldName: $value", cause),
    )
}
