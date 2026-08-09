package com.kwabor.shared.data.favorites

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.favorites.FavoriteListing
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation

private const val MINIMUM_NAME_CODE_POINTS = 3
private const val MAXIMUM_NAME_CODE_POINTS = 80
private const val MAXIMUM_CURSOR_UTF8_BYTES = 4_096

internal fun FavoriteListingRowDto.toDomain(expectedType: ListingType? = null): FavoriteListing {
    val mapping = requireFavoriteListingMapping(expectedType)
    rowCursor.requireFavoriteCursor("row_cursor")
    return FavoriteListing(
        id = id.requireFavoriteUuid("id"),
        type = mapping.type,
        listingClass = mapping.listingClass,
        name = name.requireFavoriteText(
            fieldName = "name",
            allowedCodePointCount = MINIMUM_NAME_CODE_POINTS..MAXIMUM_NAME_CODE_POINTS,
        ),
        cityId = cityId.requireFavoriteIdentifier("city_id"),
        cityName = cityName.requireFavoriteText("city_name"),
        categoryId = categoryId.requireFavoriteIdentifier("category_id"),
        coverImageUrl = mapping.cover.first,
        coverImageAlt = mapping.cover.second,
        priceFromXof = priceFromXof?.requireFavoriteMoney("price_from_xof"),
        ratingAverage = ratingAverage.requireFavoriteRating("rating_avg"),
        likesCount = likesCount.requireNonNegative("likes_count"),
        verified = verified,
        likedByViewer = likedByCurrentUser,
        favoritedAtEpochMilliseconds = favoritedAt.requireFavoriteTimestamp("favorited_at"),
        eventStartAtEpochMilliseconds = mapping.eventStartAtEpochMilliseconds,
        eventEndAtEpochMilliseconds = mapping.eventEndAtEpochMilliseconds,
        isEventEnded = isEventEnded,
    )
}

private fun FavoriteListingRowDto.requireFavoriteListingMapping(expectedType: ListingType?): FavoriteListingMapping {
    val mappedType = type.toListingType()
    if (expectedType != null && mappedType != expectedType) {
        invalidFavoriteValue("type", type)
    }
    if (status != "publie") {
        invalidFavoriteValue("status", status)
    }
    if (!favoritedByCurrentUser) {
        invalidFavoriteValue("favorited_by_current_user", favoritedByCurrentUser.toString())
    }
    if (isSponsoredPlacement) {
        invalidFavoriteValue("is_sponsored_placement", isSponsoredPlacement.toString())
    }

    val mappedCover = requireCoverPair()
    val mappedListingClass = listingClass.toListingClass()
    val mappedEventStart = eventStartAt?.requireFavoriteTimestamp("event_start_at")
    val mappedEventEnd = eventEndAt?.requireFavoriteTimestamp("event_end_at")
    mappedType.requireListingClass(mappedListingClass)
    mappedType.requireEventContract(mappedEventStart, mappedEventEnd, isEventEnded)
    return FavoriteListingMapping(
        type = mappedType,
        listingClass = mappedListingClass,
        cover = mappedCover,
        eventStartAtEpochMilliseconds = mappedEventStart,
        eventEndAtEpochMilliseconds = mappedEventEnd,
    )
}

internal fun FavoriteListingPageDto.toDomain(expectedType: ListingType? = null): FavoriteListingPage {
    val mappedItems = items.map { item -> item.toDomain(expectedType) }
    if (mappedItems.distinctBy(FavoriteListing::id).size != mappedItems.size) {
        invalidFavoriteValue("items", "duplicate listing IDs")
    }
    val mappedNextCursor = nextCursor?.requireFavoriteCursor("next_cursor")
    if (mappedNextCursor != null && items.lastOrNull()?.rowCursor != mappedNextCursor) {
        invalidFavoriteValue("next_cursor", "does not belong to the last retained row")
    }
    return FavoriteListingPage(
        items = mappedItems,
        nextCursor = mappedNextCursor,
    )
}

internal fun FavoriteMutationRowDto.toDomain(expectedListingId: String, expectedFavorited: Boolean): FavoriteMutation {
    val mappedListingId = listingId.requireFavoriteUuid("listing_id")
    if (mappedListingId != expectedListingId) {
        invalidFavoriteValue("listing_id", mappedListingId)
    }
    if (favoritedByCurrentUser != expectedFavorited) {
        invalidFavoriteValue("favorited_by_current_user", favoritedByCurrentUser.toString())
    }
    if (favoritedByCurrentUser != (favoritedAt != null)) {
        invalidFavoriteValue("favorited_at", favoritedAt ?: "null")
    }
    return FavoriteMutation(
        listingId = mappedListingId,
        favorited = favoritedByCurrentUser,
        favoritedAtEpochMilliseconds = favoritedAt?.requireFavoriteTimestamp("favorited_at"),
    )
}

internal fun String.requireFavoriteCursor(fieldName: String): String {
    if (!isValidFavoriteCursor()) {
        invalidFavoriteValue(fieldName, "invalid cursor")
    }
    return this
}

internal fun String.isValidFavoriteCursor(): Boolean = isNotEmpty() &&
    encodeToByteArray().size <= MAXIMUM_CURSOR_UTF8_BYTES &&
    trim() == this &&
    none(Char::isWhitespace) &&
    none(Char::isISOControl)

internal fun ListingType.toFavoriteDatabaseValue(): String = when (this) {
    ListingType.Place -> "lieu"
    ListingType.Establishment -> "etablissement"
    ListingType.Event -> "evenement"
}

private fun String.toListingType(): ListingType = when (this) {
    "lieu" -> ListingType.Place
    "etablissement" -> ListingType.Establishment
    "evenement" -> ListingType.Event
    else -> invalidFavoriteValue("type", this)
}

private fun String.toListingClass(): ListingClass = when (this) {
    "patrimonial" -> ListingClass.Heritage
    "commercial" -> ListingClass.Commercial
    "evenementiel" -> ListingClass.Event
    else -> invalidFavoriteValue("listing_class", this)
}

private fun FavoriteListingRowDto.requireCoverPair(): Pair<String?, String?> = when {
    coverImageUrl == null && coverImageAlt == null -> null to null
    coverImageUrl != null && coverImageAlt != null ->
        coverImageUrl.requireFavoriteHttpsUrl("cover_image_url") to
            coverImageAlt.requireFavoriteText("cover_image_alt")
    else -> invalidFavoriteValue("cover_image", "url and alt must both be present or absent")
}

private fun ListingType.requireEventContract(start: Long?, end: Long?, ended: Boolean) {
    when (this) {
        ListingType.Event -> {
            if (start == null || (end != null && end < start)) {
                invalidFavoriteValue("event_dates", "$start/$end")
            }
        }
        ListingType.Place,
        ListingType.Establishment,
        -> if (start != null || end != null || ended) {
            invalidFavoriteValue("event_dates", "$start/$end/$ended")
        }
    }
}

private fun ListingType.requireListingClass(listingClass: ListingClass) {
    val isValid = when (this) {
        ListingType.Place -> listingClass != ListingClass.Event
        ListingType.Establishment -> listingClass == ListingClass.Commercial
        ListingType.Event -> listingClass == ListingClass.Event
    }
    if (!isValid) {
        invalidFavoriteValue("listing_class", listingClass.name)
    }
}

internal fun invalidFavoriteValue(fieldName: String, value: String, cause: Throwable? = null): Nothing =
    throw FavoritesDataException.Unexpected(
        IllegalStateException("Invalid favorites value for $fieldName: $value", cause),
    )

private data class FavoriteListingMapping(
    val type: ListingType,
    val listingClass: ListingClass,
    val cover: Pair<String?, String?>,
    val eventStartAtEpochMilliseconds: Long?,
    val eventEndAtEpochMilliseconds: Long?,
)
