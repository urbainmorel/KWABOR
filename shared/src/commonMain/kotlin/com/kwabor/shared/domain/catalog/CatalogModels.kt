package com.kwabor.shared.domain.catalog

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof

enum class ListingType {
    Place,
    Establishment,
    Event,
}

enum class ListingClass {
    Heritage,
    Commercial,
    Event,
}

val ListingClass.canBeClaimed: Boolean
    get() = this == ListingClass.Commercial || this == ListingClass.Event

enum class ListingStatus {
    Draft,
    PendingReview,
    Published,
    Rejected,
    Archived,
}

enum class PriceUnit {
    PerNight,
    PerPerson,
    Consumption,
    PerEntry,
    None,
}

data class City(
    val id: String,
    val name: String,
    val countryCode: String = "BJ",
)

data class Category(
    val id: String,
    val nameKey: String,
    val listingType: ListingType,
    val defaultListingClass: ListingClass,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

data class ListingSummary(
    val id: String,
    val type: ListingType,
    val listingClass: ListingClass,
    val status: ListingStatus,
    val name: String,
    val cityId: String,
    val categoryId: String,
    val coverImageUrl: String?,
    val priceFromXof: MoneyXof?,
    val ratingAverage: Double?,
    val likesCount: Int,
    val verified: Boolean,
    val sponsoredUntilEpochMilliseconds: Long?,
)

data class ListingDetail(
    val summary: ListingSummary,
    val slug: String,
    val description: String,
    val contentLocale: AppLocale,
    val district: String?,
    val address: String?,
    val geoPoint: GeoPoint?,
    val contact: ListingContact,
    val media: List<ListingMedia>,
    val tags: List<String>,
    val ownerId: String?,
    val stewardId: String?,
    val publishedAtEpochMilliseconds: Long?,
)

data class ListingContact(
    val phone: String?,
    val whatsapp: String?,
    val externalUrl: String?,
    val email: String?,
)

data class ListingMedia(
    val url: String,
    val alt: String,
    val order: Int,
    val isCover: Boolean,
)

data class ListingFilters(
    val cityId: String? = null,
    val categoryId: String? = null,
    val listingType: ListingType? = null,
    val listingClass: ListingClass? = null,
    val onlyPublished: Boolean = true,
)

data class ListingSearchQuery(
    val text: String,
    val filters: ListingFilters = ListingFilters(),
)
