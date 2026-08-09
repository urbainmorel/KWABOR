package com.kwabor.shared.data.favorites

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class FavoriteListingRowDto(
    @SerialName("id")
    val id: String,
    @SerialName("type")
    val type: String,
    @SerialName("listing_class")
    val listingClass: String,
    @SerialName("status")
    val status: String,
    @SerialName("name")
    val name: String,
    @SerialName("city_id")
    val cityId: String,
    @SerialName("city_name")
    val cityName: String,
    @SerialName("category_id")
    val categoryId: String,
    @SerialName("cover_image_url")
    val coverImageUrl: String?,
    @SerialName("cover_image_alt")
    val coverImageAlt: String?,
    @SerialName("price_from_xof")
    val priceFromXof: Long?,
    @SerialName("rating_avg")
    val ratingAverage: Double?,
    @SerialName("likes_count")
    val likesCount: Int,
    @SerialName("verified")
    val verified: Boolean,
    @SerialName("liked_by_current_user")
    val likedByCurrentUser: Boolean,
    @SerialName("favorited_by_current_user")
    val favoritedByCurrentUser: Boolean,
    @SerialName("favorited_at")
    val favoritedAt: String,
    @SerialName("event_start_at")
    val eventStartAt: String?,
    @SerialName("event_end_at")
    val eventEndAt: String?,
    @SerialName("is_event_ended")
    val isEventEnded: Boolean,
    @SerialName("is_sponsored_placement")
    val isSponsoredPlacement: Boolean,
    @SerialName("row_cursor")
    val rowCursor: String,
)

internal data class FavoriteListingPageDto(
    val items: List<FavoriteListingRowDto>,
    val nextCursor: String?,
)

@Serializable
internal data class FavoriteMutationRowDto(
    @SerialName("listing_id")
    val listingId: String,
    @SerialName("favorited_by_current_user")
    val favoritedByCurrentUser: Boolean,
    @SerialName("favorited_at")
    val favoritedAt: String?,
)

@Serializable
internal data class ListFavoritesRpcParametersDto(
    @SerialName("p_listing_type")
    val listingType: String?,
    @SerialName("p_cursor")
    val cursor: String?,
    @SerialName("p_limit")
    val limit: Int,
)

@Serializable
internal data class SetFavoriteRpcParametersDto(
    @SerialName("p_listing_id")
    val listingId: String,
    @SerialName("p_favorited")
    val favorited: Boolean,
)
