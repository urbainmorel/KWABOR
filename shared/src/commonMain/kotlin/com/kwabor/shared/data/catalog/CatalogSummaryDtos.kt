package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ListingSummaryDto(
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
    val coverImageUrl: String? = null,
    @SerialName("price_from_xof")
    val priceFromXof: Long? = null,
    @SerialName("rating_avg")
    val ratingAverage: Double? = null,
    @SerialName("likes_count")
    val likesCount: Int = 0,
    @SerialName("verified")
    val verified: Boolean = false,
    @SerialName("sponsored_until")
    val sponsoredUntil: String? = null,
    @SerialName("is_sponsored_placement")
    val isSponsoredPlacement: Boolean,
    @SerialName("row_cursor")
    val rowCursor: String,
)

internal data class ListingSummaryPageDto(
    val items: List<ListingSummaryDto>,
    val nextCursor: String?,
)

@Serializable
internal data class ListingSummaryPageRpcDto(
    @SerialName("p_city_id")
    val cityId: String?,
    @SerialName("p_category_id")
    val categoryId: String?,
    @SerialName("p_listing_type")
    val listingType: String?,
    @SerialName("p_listing_class")
    val listingClass: String?,
    @SerialName("p_search_query")
    val searchQuery: String?,
    @SerialName("p_cursor")
    val cursor: String?,
    @SerialName("p_limit")
    val limit: Int,
)

internal fun ListingSummaryDto.toDomain(): ListingSummary = ListingSummary(
    id = id,
    type = type.toListingType(),
    listingClass = listingClass.toListingClass(),
    status = status.toListingStatus(),
    name = name,
    cityId = cityId,
    categoryId = categoryId,
    coverImageUrl = coverImageUrl,
    priceFromXof = priceFromXof?.toNonNegativeMoney("listings.price_from_xof"),
    ratingAverage = ratingAverage,
    likesCount = likesCount.toNonNegativeCount("listings.likes_count"),
    verified = verified,
    sponsoredUntilEpochMilliseconds = sponsoredUntil?.toEpochMilliseconds(),
    isSponsoredPlacement = isSponsoredPlacement,
)

internal fun ListingSummaryPageDto.toDomain(): ListingSummaryPage = ListingSummaryPage(
    items = items.map { item -> item.toDomain() },
    nextCursor = nextCursor,
)
