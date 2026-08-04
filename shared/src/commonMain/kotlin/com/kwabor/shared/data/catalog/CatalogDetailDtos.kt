package com.kwabor.shared.data.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class CatalogDetailRpcParametersDto(
    @SerialName("p_listing_id")
    val listingId: String,
)

@Serializable
internal data class CatalogDetailRpcRowDto(
    @SerialName("payload")
    val payload: JsonObject,
)

@Serializable
internal data class CatalogDetailPayloadDto(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("id")
    val id: String,
    @SerialName("type")
    val type: String,
    @SerialName("subtype")
    val subtype: String,
    @SerialName("listing_class")
    val listingClass: String,
    @SerialName("name")
    val name: String,
    @SerialName("slug")
    val slug: String,
    @SerialName("description")
    val description: String,
    @SerialName("content_lang")
    val contentLang: String,
    @SerialName("city")
    val city: CatalogDetailCityDto,
    @SerialName("category")
    val category: CatalogDetailCategoryDto,
    @SerialName("location")
    val location: CatalogDetailLocationDto,
    @SerialName("price")
    val price: CatalogDetailPriceDto,
    @SerialName("opening_hours")
    val openingHours: JsonObject,
    @SerialName("contact")
    val contact: CatalogDetailContactDto,
    @SerialName("socials")
    val socials: JsonObject,
    @SerialName("tags")
    val tags: List<String>,
    @SerialName("verified")
    val verified: Boolean,
    @SerialName("is_claimable")
    val isClaimable: Boolean,
    @SerialName("metrics")
    val metrics: CatalogDetailMetricsDto,
    @SerialName("published_at")
    val publishedAt: String,
    @SerialName("media")
    val media: List<CatalogDetailMediaDto>,
    @SerialName("amenities")
    val amenities: List<CatalogDetailAmenityDto>,
    @SerialName("detail")
    val detail: JsonObject,
)

@Serializable
internal data class CatalogDetailCityDto(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
)

@Serializable
internal data class CatalogDetailCategoryDto(
    @SerialName("id")
    val id: String,
    @SerialName("label_key")
    val labelKey: String,
)

@Serializable
internal data class CatalogDetailLocationDto(
    @SerialName("district")
    val district: String? = null,
    @SerialName("address")
    val address: String? = null,
    @SerialName("latitude")
    val latitude: Double? = null,
    @SerialName("longitude")
    val longitude: Double? = null,
)

@Serializable
internal data class CatalogDetailPriceDto(
    @SerialName("from_xof")
    val fromXof: Long? = null,
    @SerialName("unit")
    val unit: String,
    @SerialName("tier")
    val tier: Int? = null,
)

@Serializable
internal data class CatalogDetailContactDto(
    @SerialName("phone")
    val phone: String? = null,
    @SerialName("whatsapp")
    val whatsapp: String? = null,
    @SerialName("external_url")
    val externalUrl: String? = null,
    @SerialName("email")
    val email: String? = null,
)

@Serializable
internal data class CatalogDetailMetricsDto(
    @SerialName("rating_average")
    val ratingAverage: Double? = null,
    @SerialName("rating_count")
    val ratingCount: Int,
    @SerialName("views_count")
    val viewsCount: Int,
    @SerialName("likes_count")
    val likesCount: Int,
)

@Serializable
internal data class CatalogDetailMediaDto(
    @SerialName("kind")
    val kind: String,
    @SerialName("url")
    val url: String,
    @SerialName("alt")
    val alt: String,
    @SerialName("display_order")
    val displayOrder: Int,
    @SerialName("is_cover")
    val isCover: Boolean,
)

@Serializable
internal data class CatalogDetailAmenityDto(
    @SerialName("id")
    val id: String,
    @SerialName("label_key")
    val labelKey: String,
    @SerialName("display_order")
    val displayOrder: Int,
)

@Serializable
internal data class CatalogDetailOpeningDayDto(
    @SerialName("status")
    val status: String,
    @SerialName("periods")
    val periods: List<CatalogDetailOpeningPeriodDto>,
)

@Serializable
internal data class CatalogDetailOpeningPeriodDto(
    @SerialName("opens_minute")
    val opensMinute: Int,
    @SerialName("closes_minute")
    val closesMinute: Int,
    @SerialName("closes_next_day")
    val closesNextDay: Boolean,
)

@Serializable
internal data class CatalogPlaceDetailDto(
    @SerialName("variant")
    val variant: String,
    @SerialName("place_category")
    val placeCategory: String,
    @SerialName("is_free")
    val isFree: Boolean,
    @SerialName("entry_fee_xof")
    val entryFeeXof: Long? = null,
    @SerialName("fee_note")
    val feeNote: String? = null,
)

@Serializable
internal data class CatalogLodgingDetailDto(
    @SerialName("variant")
    val variant: String,
    @SerialName("star_rating")
    val starRating: Int? = null,
    @SerialName("room_count")
    val roomCount: Int? = null,
    @SerialName("checkin_time")
    val checkinTime: String? = null,
    @SerialName("checkout_time")
    val checkoutTime: String? = null,
    @SerialName("room_types")
    val roomTypes: List<CatalogRoomTypeDto>,
)

@Serializable
internal data class CatalogRoomTypeDto(
    @SerialName("name")
    val name: String,
    @SerialName("price_xof")
    val priceXof: Long,
    @SerialName("display_order")
    val displayOrder: Int,
)

@Serializable
internal data class CatalogFoodDetailDto(
    @SerialName("variant")
    val variant: String,
    @SerialName("cuisines")
    val cuisines: List<String>,
    @SerialName("meals")
    val meals: List<String>,
    @SerialName("reservation")
    val reservation: Boolean,
    @SerialName("menu_url")
    val menuUrl: String? = null,
)

@Serializable
internal data class CatalogNightlifeDetailDto(
    @SerialName("variant")
    val variant: String,
    @SerialName("venue_kind")
    val venueKind: String,
    @SerialName("min_age")
    val minimumAge: Int? = null,
)

@Serializable
internal data class CatalogGuideDetailDto(
    @SerialName("variant")
    val variant: String,
    @SerialName("languages")
    val languages: List<String>,
    @SerialName("zones")
    val zones: List<String>,
    @SerialName("specialties")
    val specialties: List<String>,
    @SerialName("indicative_price_xof")
    val indicativePriceXof: Long? = null,
    @SerialName("accreditation")
    val accreditation: String? = null,
    @SerialName("experience_years")
    val experienceYears: Int? = null,
)

@Serializable
internal data class CatalogEventDetailDto(
    @SerialName("variant")
    val variant: String,
    @SerialName("category")
    val category: String,
    @SerialName("start_at")
    val startAt: String,
    @SerialName("end_at")
    val endAt: String? = null,
    @SerialName("venue_listing")
    val venueListing: CatalogEventVenueDto? = null,
    @SerialName("organizer")
    val organizer: CatalogEventOrganizerDto,
    @SerialName("ticketing")
    val ticketing: CatalogEventTicketingDto,
    @SerialName("capacity")
    val capacity: Int? = null,
)

@Serializable
internal data class CatalogEventVenueDto(
    @SerialName("id")
    val id: String,
    @SerialName("type")
    val type: String,
    @SerialName("subtype")
    val subtype: String,
    @SerialName("name")
    val name: String,
    @SerialName("city")
    val city: CatalogDetailCityDto,
    @SerialName("address")
    val address: String? = null,
    @SerialName("latitude")
    val latitude: Double? = null,
    @SerialName("longitude")
    val longitude: Double? = null,
)

@Serializable
internal data class CatalogEventOrganizerDto(
    @SerialName("name")
    val name: String,
    @SerialName("contact")
    val contact: String,
)

@Serializable
internal data class CatalogEventTicketingDto(
    @SerialName("type")
    val type: String,
    @SerialName("url")
    val url: String? = null,
    @SerialName("tiers")
    val tiers: List<CatalogTicketTierDto>,
)

@Serializable
internal data class CatalogTicketTierDto(
    @SerialName("label")
    val label: String,
    @SerialName("price_xof")
    val priceXof: Long,
    @SerialName("display_order")
    val displayOrder: Int,
)
