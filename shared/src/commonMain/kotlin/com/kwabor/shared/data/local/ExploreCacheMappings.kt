package com.kwabor.shared.data.local

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.MoneyXof

internal fun ListingSummary.toExploreCachedListingEntity(cachedAtEpochMilliseconds: Long): ExploreCachedListingEntity =
    ExploreCachedListingEntity(
        listingId = id,
        listingType = type.toCacheValue(),
        listingClass = listingClass.toCacheValue(),
        status = status.toCacheValue(),
        name = name,
        cityId = cityId,
        categoryId = categoryId,
        coverImageUrl = coverImageUrl,
        priceFromXof = priceFromXof?.amount,
        ratingAverage = ratingAverage,
        likesCount = likesCount,
        verified = verified,
        sponsoredUntilEpochMilliseconds = sponsoredUntilEpochMilliseconds,
        contentCachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    )

internal fun ListingSummary.toExploreCacheSnapshotItemEntity(
    snapshotKey: String,
    position: Int,
): ExploreCacheSnapshotItemEntity = ExploreCacheSnapshotItemEntity(
    snapshotKey = snapshotKey,
    listingId = id,
    position = position,
    isSponsoredPlacement = isSponsoredPlacement,
)

internal fun ExploreCachedListingRecord.toDomain(): ListingSummary = listing.run {
    val summary = ListingSummary(
        id = listingId,
        type = listingType.toCachedListingType(),
        listingClass = listingClass.toCachedListingClass(),
        status = status.toCachedListingStatus(),
        name = name,
        cityId = cityId,
        categoryId = categoryId,
        coverImageUrl = coverImageUrl,
        priceFromXof = priceFromXof?.toCachedMoney(),
        ratingAverage = ratingAverage,
        likesCount = likesCount.requireNonNegative("likes_count"),
        verified = verified,
        sponsoredUntilEpochMilliseconds = sponsoredUntilEpochMilliseconds,
        isSponsoredPlacement = this@toDomain.isSponsoredPlacement,
    )
    val invalidField = summary.invalidExploreCacheFieldOrNull()
    if (invalidField != null) {
        invalidCacheValue(invalidField)
    }
    summary
}

internal fun ListingSummary.invalidExploreCacheFieldOrNull(): String? =
    invalidRequiredTextFieldOrNull() ?: invalidOptionalAndMetricFieldOrNull()

private fun ListingSummary.invalidRequiredTextFieldOrNull(): String? = when {
    id.isInvalidRequiredCacheValue(MAX_EXPLORE_CACHE_ID_LENGTH) -> "listing_id"
    name.isInvalidRequiredCacheValue(
        maximumLength = MAX_EXPLORE_CACHE_NAME_LENGTH,
        minimumLength = MIN_EXPLORE_CACHE_NAME_LENGTH,
    ) -> "name"
    cityId.isInvalidRequiredCacheValue(MAX_EXPLORE_CACHE_ID_LENGTH) -> "city_id"
    categoryId.isInvalidRequiredCacheValue(MAX_EXPLORE_CACHE_ID_LENGTH) -> "category_id"
    else -> null
}

private fun ListingSummary.invalidOptionalAndMetricFieldOrNull(): String? = when {
    coverImageUrl.isInvalidOptionalCacheUrl() -> "cover_image_url"
    ratingAverage.isInvalidExploreCacheRating() -> "rating_average"
    likesCount < 0 -> "likes_count"
    sponsoredUntilEpochMilliseconds != null && sponsoredUntilEpochMilliseconds < 0 ->
        "sponsored_until_epoch_milliseconds"
    else -> null
}

private fun String.isInvalidRequiredCacheValue(maximumLength: Int, minimumLength: Int = 1): Boolean =
    isBlank() || length !in minimumLength..maximumLength

private fun String?.isInvalidOptionalCacheUrl(): Boolean {
    if (this == null) {
        return false
    }
    return isBlank() || length > MAX_EXPLORE_CACHE_URL_LENGTH
}

private fun Double?.isInvalidExploreCacheRating(): Boolean {
    if (this == null) {
        return false
    }
    return !isFinite() || this !in MIN_EXPLORE_CACHE_RATING..MAX_EXPLORE_CACHE_RATING
}

private fun ListingType.toCacheValue(): String = when (this) {
    ListingType.Place -> "place"
    ListingType.Establishment -> "establishment"
    ListingType.Event -> "event"
}

private fun ListingClass.toCacheValue(): String = when (this) {
    ListingClass.Heritage -> "heritage"
    ListingClass.Commercial -> "commercial"
    ListingClass.Event -> "event"
}

private fun ListingStatus.toCacheValue(): String = when (this) {
    ListingStatus.Draft -> "draft"
    ListingStatus.PendingReview -> "pending_review"
    ListingStatus.Published -> "published"
    ListingStatus.Rejected -> "rejected"
    ListingStatus.Archived -> "archived"
}

private fun String.toCachedListingType(): ListingType = when (this) {
    "place" -> ListingType.Place
    "establishment" -> ListingType.Establishment
    "event" -> ListingType.Event
    else -> invalidCacheValue("listing_type")
}

private fun String.toCachedListingClass(): ListingClass = when (this) {
    "heritage" -> ListingClass.Heritage
    "commercial" -> ListingClass.Commercial
    "event" -> ListingClass.Event
    else -> invalidCacheValue("listing_class")
}

private fun String.toCachedListingStatus(): ListingStatus = when (this) {
    "draft" -> ListingStatus.Draft
    "pending_review" -> ListingStatus.PendingReview
    "published" -> ListingStatus.Published
    "rejected" -> ListingStatus.Rejected
    "archived" -> ListingStatus.Archived
    else -> invalidCacheValue("status")
}

private fun Long.toCachedMoney(): MoneyXof = when (val money = MoneyXof.fromAmount(this)) {
    is DomainResult.Success -> money.value
    is DomainResult.Failure -> invalidCacheValue("price_from_xof")
}

private fun Int.requireNonNegative(fieldName: String): Int {
    if (this >= 0) {
        return this
    }
    invalidCacheValue(fieldName)
}

private fun invalidCacheValue(fieldName: String): Nothing = throw CorruptExploreCacheException(fieldName)

internal class CorruptExploreCacheException(fieldName: String) :
    IllegalStateException("Invalid persisted Explore cache field: $fieldName")
