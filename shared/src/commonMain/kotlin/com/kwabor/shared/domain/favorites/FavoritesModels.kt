package com.kwabor.shared.domain.favorites

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.money.MoneyXof

data class FavoriteListing(
    val id: String,
    val type: ListingType,
    val listingClass: ListingClass,
    val name: String,
    val cityId: String,
    val cityName: String,
    val categoryId: String,
    val coverImageUrl: String?,
    val coverImageAlt: String?,
    val priceFromXof: MoneyXof?,
    val ratingAverage: Double?,
    val likesCount: Int,
    val verified: Boolean,
    val likedByViewer: Boolean,
    val favoritedAtEpochMilliseconds: Long,
    val eventStartAtEpochMilliseconds: Long?,
    val eventEndAtEpochMilliseconds: Long?,
    val isEventEnded: Boolean,
)

data class FavoriteListingPage(
    val items: List<FavoriteListing>,
    val nextCursor: String?,
)

data class FavoriteMutation(
    val listingId: String,
    val favorited: Boolean,
    val favoritedAtEpochMilliseconds: Long?,
)
