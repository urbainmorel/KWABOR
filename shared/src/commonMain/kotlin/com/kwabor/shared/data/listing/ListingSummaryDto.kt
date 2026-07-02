package com.kwabor.shared.data.listing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListingSummaryDto(
    @SerialName("id")
    val id: String,
    @SerialName("type")
    val type: String,
    @SerialName("listing_class")
    val listingClass: String,
    @SerialName("name")
    val name: String,
    @SerialName("city_id")
    val cityId: String,
    @SerialName("price_from_xof")
    val priceFromXof: Long? = null,
)
