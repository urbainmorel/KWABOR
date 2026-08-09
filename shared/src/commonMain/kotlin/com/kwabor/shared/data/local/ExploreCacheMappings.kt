package com.kwabor.shared.data.local

import com.kwabor.shared.data.explore.isValidExploreCanonicalTextValue
import com.kwabor.shared.data.explore.isValidExploreHttpsUrlValue
import com.kwabor.shared.data.explore.isValidExploreUuidValue
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
        coverImageAlt = coverImageAlt,
        priceFromXof = priceFromXof?.amount,
        ratingAverage = ratingAverage,
        likesCount = likesCount,
        viewsCount = viewsCount,
        verified = verified,
        sponsoredUntilEpochMilliseconds = sponsoredUntilEpochMilliseconds,
        eventStartAtEpochMilliseconds = eventStartAtEpochMilliseconds,
        eventEndAtEpochMilliseconds = eventEndAtEpochMilliseconds,
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
    isEventEnded = isEventEnded,
)

internal fun ExploreCachedListingRecord.toDomain(): ListingSummary = listing.toDomain(
    isSponsoredPlacement = isSponsoredPlacement,
    isEventEnded = isEventEnded,
)

internal fun ExploreCachedListingEntity.toDomain(
    isSponsoredPlacement: Boolean? = null,
    isEventEnded: Boolean? = null,
): ListingSummary = run {
    val summary = ListingSummary(
        id = listingId,
        type = listingType.toCachedListingType(),
        listingClass = listingClass.toCachedListingClass(),
        status = status.toCachedListingStatus(),
        name = name,
        cityId = cityId,
        categoryId = categoryId,
        coverImageUrl = coverImageUrl,
        coverImageAlt = coverImageAlt,
        priceFromXof = priceFromXof?.toCachedMoney(),
        ratingAverage = ratingAverage,
        likesCount = likesCount.requireNonNegative("likes_count"),
        viewsCount = viewsCount.requireNonNegative("views_count"),
        verified = verified,
        sponsoredUntilEpochMilliseconds = sponsoredUntilEpochMilliseconds,
        eventStartAtEpochMilliseconds = eventStartAtEpochMilliseconds,
        eventEndAtEpochMilliseconds = eventEndAtEpochMilliseconds,
        isEventEnded = isEventEnded,
        isSponsoredPlacement = isSponsoredPlacement,
    )
    val invalidField = summary.invalidExploreCacheFieldOrNull()
    if (invalidField != null) {
        invalidCacheValue(invalidField)
    }
    summary
}

internal fun ListingSummary.invalidExploreCacheFieldOrNull(): String? = invalidRequiredCacheFieldOrNull
    ?: invalidMediaCacheFieldOrNull
    ?: invalidMetricCacheFieldOrNull
    ?: invalidEventMetadataFieldOrNull

private val ListingSummary.invalidRequiredCacheFieldOrNull: String?
    get() = when {
        id.isInvalidRequiredCacheValue(MAX_EXPLORE_CACHE_ID_LENGTH) -> "listing_id"
        !name.isValidExploreCanonicalTextValue(
            MIN_EXPLORE_CACHE_NAME_LENGTH..MAX_EXPLORE_CACHE_NAME_LENGTH,
        ) -> "name"
        cityId.isInvalidRequiredCacheValue(MAX_EXPLORE_CACHE_ID_LENGTH) -> "city_id"
        categoryId.isInvalidRequiredCacheValue(MAX_EXPLORE_CACHE_ID_LENGTH) -> "category_id"
        else -> null
    }

private val ListingSummary.invalidMediaCacheFieldOrNull: String?
    get() = when {
        coverImageUrl.isInvalidOptionalCacheUrl -> "cover_image_url"
        coverImageAlt.isInvalidOptionalCacheText -> "cover_image_alt"
        coverImageAlt != null && coverImageUrl == null -> "cover_image_url"
        else -> null
    }

private val ListingSummary.invalidMetricCacheFieldOrNull: String?
    get() = when {
        ratingAverage.isInvalidExploreCacheRating -> "rating_average"
        likesCount < 0 -> "likes_count"
        viewsCount != null && viewsCount < 0 -> "views_count"
        sponsoredUntilEpochMilliseconds != null && sponsoredUntilEpochMilliseconds < 0 ->
            "sponsored_until_epoch_milliseconds"
        else -> null
    }

private val ListingSummary.invalidEventMetadataFieldOrNull: String?
    get() {
        val eventStart = eventStartAtEpochMilliseconds
        val eventEnd = eventEndAtEpochMilliseconds
        return when (type) {
            ListingType.Event -> when {
                eventEnd != null && eventStart == null -> "event_start_at_epoch_milliseconds"
                eventEnd != null && eventStart != null && eventEnd < eventStart ->
                    "event_end_at_epoch_milliseconds"
                else -> null
            }
            ListingType.Place,
            ListingType.Establishment,
            -> when {
                eventStart != null -> "event_start_at_epoch_milliseconds"
                eventEnd != null -> "event_end_at_epoch_milliseconds"
                isEventEnded == true -> "is_event_ended"
                else -> null
            }
        }
    }

internal val ListingSummary.invalidExploreV2CacheFieldOrNull: String?
    get() = when {
        !id.isValidExploreUuidValue() -> "listing_id"
        !name.isValidExploreCanonicalTextValue(MIN_EXPLORE_V2_NAME_LENGTH..MAX_EXPLORE_V2_NAME_LENGTH) -> "name"
        coverImageUrl == null && coverImageAlt != null -> "cover_image_url"
        coverImageUrl != null && coverImageAlt == null -> "cover_image_alt"
        coverImageUrl != null && !coverImageUrl.isValidExploreHttpsUrlValue() -> "cover_image_url"
        viewsCount == null -> "views_count"
        isSponsoredPlacement == null -> "is_sponsored_placement"
        isEventEnded == null -> "is_event_ended"
        type == ListingType.Event && eventStartAtEpochMilliseconds == null ->
            "event_start_at_epoch_milliseconds"
        isSponsoredPlacement == true && (
            type != ListingType.Establishment ||
                listingClass != ListingClass.Commercial ||
                sponsoredUntilEpochMilliseconds == null
            ) -> "is_sponsored_placement"
        else -> null
    }

private fun String.isInvalidRequiredCacheValue(maximumLength: Int, minimumLength: Int = 1): Boolean =
    isBlank() || length !in minimumLength..maximumLength

private val String?.isInvalidOptionalCacheUrl: Boolean
    get() = this != null && (isBlank() || length > MAX_EXPLORE_CACHE_URL_LENGTH)

private val String?.isInvalidOptionalCacheText: Boolean
    get() {
        if (this == null) {
            return false
        }
        return isBlank() ||
            trim() != this ||
            any(Char::isISOControl) ||
            hasInvalidUnicodeSequence()
    }

private fun String.hasInvalidUnicodeSequence(): Boolean {
    var index = 0
    while (index < length) {
        val code = this[index].code
        when {
            code in HIGH_SURROGATE_START..HIGH_SURROGATE_END -> {
                val nextCode = getOrNull(index + 1)?.code
                if (nextCode == null || nextCode !in LOW_SURROGATE_START..LOW_SURROGATE_END) {
                    return true
                }
                index += 2
            }
            code in LOW_SURROGATE_START..LOW_SURROGATE_END -> return true
            else -> index += 1
        }
    }
    return false
}

private val Double?.isInvalidExploreCacheRating: Boolean
    get() = this != null && (!isFinite() || this !in MIN_EXPLORE_CACHE_RATING..MAX_EXPLORE_CACHE_RATING)

internal fun ListingType.toCacheValue(): String = when (this) {
    ListingType.Place -> "place"
    ListingType.Establishment -> "establishment"
    ListingType.Event -> "event"
}

internal fun ListingClass.toCacheValue(): String = when (this) {
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

private fun Long?.requireNonNegative(fieldName: String): Long? {
    if (this == null || this >= 0) {
        return this
    }
    invalidCacheValue(fieldName)
}

private fun invalidCacheValue(fieldName: String): Nothing = throw CorruptExploreCacheException(fieldName)

internal class CorruptExploreCacheException(fieldName: String) :
    IllegalStateException("Invalid persisted Explore cache field: $fieldName")

private const val HIGH_SURROGATE_START = 0xD800
private const val HIGH_SURROGATE_END = 0xDBFF
private const val LOW_SURROGATE_START = 0xDC00
private const val LOW_SURROGATE_END = 0xDFFF
private const val MIN_EXPLORE_V2_NAME_LENGTH = 3
private const val MAX_EXPLORE_V2_NAME_LENGTH = 80
