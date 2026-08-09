package com.kwabor.shared.data.explore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ExploreCatalogRowDto(
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
    @SerialName("views_count")
    val viewsCount: Long,
    @SerialName("likes_count")
    val likesCount: Int,
    @SerialName("verified")
    val verified: Boolean,
    @SerialName("sponsored_until")
    val sponsoredUntil: String?,
    @SerialName("event_start_at")
    val eventStartAt: String?,
    @SerialName("event_end_at")
    val eventEndAt: String?,
    @SerialName("is_event_ended")
    val isEventEnded: Boolean,
    @SerialName("is_sponsored_placement")
    val isSponsoredPlacement: Boolean,
    @SerialName("snapshot_at")
    val snapshotAt: String,
    @SerialName("row_cursor")
    val rowCursor: String,
)

internal data class ExploreCatalogPageDto(
    val items: List<ExploreCatalogRowDto>,
    val nextCursor: String?,
    val snapshotAtEpochMicroseconds: Long?,
)

@Serializable
internal data class ExploreCatalogRpcParametersDto(
    @SerialName("p_listing_type")
    val listingType: String,
    @SerialName("p_city_id")
    val cityId: String?,
    @SerialName("p_category_id")
    val categoryId: String?,
    @SerialName("p_listing_class")
    val listingClass: String?,
    @SerialName("p_sort")
    val sort: String,
    @SerialName("p_price_min_xof")
    val priceMinXof: Long?,
    @SerialName("p_price_max_xof")
    val priceMaxXof: Long?,
    @SerialName("p_event_window_start")
    val eventWindowStart: String?,
    @SerialName("p_event_window_end")
    val eventWindowEndExclusive: String?,
    @SerialName("p_cursor")
    val cursor: String?,
    @SerialName("p_limit")
    val limit: Int,
)
