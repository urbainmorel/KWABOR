package com.kwabor.shared.domain.catalog

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof

sealed interface CatalogDetail {
    val common: CatalogDetailCommon

    data class Place(
        override val common: CatalogDetailCommon,
        val placeCategory: String,
        val isFree: Boolean,
        val entryFee: MoneyXof?,
        val feeNote: String?,
    ) : CatalogDetail

    sealed interface Establishment : CatalogDetail {
        data class Lodging(
            override val common: CatalogDetailCommon,
            val starRating: Int?,
            val roomCount: Int?,
            val checkInMinute: Int?,
            val checkOutMinute: Int?,
            val roomTypes: List<CatalogRoomType>,
        ) : Establishment

        data class Food(
            override val common: CatalogDetailCommon,
            val cuisines: List<String>,
            val meals: List<String>,
            val acceptsReservations: Boolean,
            val menuUrl: String?,
        ) : Establishment

        data class Nightlife(
            override val common: CatalogDetailCommon,
            val venueKind: String,
            val minimumAge: Int?,
        ) : Establishment

        data class Guide(
            override val common: CatalogDetailCommon,
            val languages: List<String>,
            val zones: List<String>,
            val specialties: List<String>,
            val indicativePrice: MoneyXof?,
            val accreditation: String?,
            val experienceYears: Int?,
        ) : Establishment
    }

    data class Event(
        override val common: CatalogDetailCommon,
        val category: String,
        val startsAtEpochMilliseconds: Long,
        val endsAtEpochMilliseconds: Long?,
        val venue: CatalogEventVenue?,
        val organizer: CatalogEventOrganizer,
        val ticketing: CatalogEventTicketing,
        val capacity: Int?,
    ) : CatalogDetail
}

data class CatalogDetailCommon(
    val id: String,
    val type: ListingType,
    val subtype: String,
    val listingClass: ListingClass,
    val name: String,
    val slug: String,
    val description: String,
    val contentLocale: AppLocale,
    val city: CatalogCityReference,
    val category: CatalogCategoryReference,
    val location: CatalogLocation,
    val price: CatalogPrice,
    val openingHours: CatalogOpeningHours,
    val contact: ListingContact,
    val socialLinks: List<CatalogSocialLink>,
    val tags: List<String>,
    val verified: Boolean,
    val isClaimable: Boolean,
    val metrics: CatalogMetrics,
    val publishedAtEpochMilliseconds: Long,
    val media: List<CatalogMedia>,
    val amenities: List<CatalogAmenity>,
)

data class CatalogCityReference(
    val id: String,
    val name: String,
)

data class CatalogCategoryReference(
    val id: String,
    val labelKey: String,
)

data class CatalogLocation(
    val district: String?,
    val address: String?,
    val geoPoint: GeoPoint?,
)

data class CatalogPrice(
    val from: MoneyXof?,
    val unit: PriceUnit,
    val tier: Int?,
)

data class CatalogMetrics(
    val ratingAverage: Double?,
    val ratingCount: Int,
    val viewsCount: Int,
    val likesCount: Int,
)

enum class CatalogMediaKind {
    Image,
    Video,
}

data class CatalogMedia(
    val kind: CatalogMediaKind,
    val url: String,
    val alt: String,
    val order: Int,
    val isCover: Boolean,
)

data class CatalogAmenity(
    val id: String,
    val labelKey: String,
    val order: Int,
)

enum class CatalogSocialPlatform {
    Instagram,
    Facebook,
    TikTok,
    YouTube,
    X,
    LinkedIn,
}

data class CatalogSocialLink(
    val platform: CatalogSocialPlatform,
    val url: String,
)

enum class Weekday {
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
    Sunday,
}

sealed interface CatalogOpeningHours {
    data object Unspecified : CatalogOpeningHours

    data class Weekly(
        val days: List<CatalogOpeningDay>,
    ) : CatalogOpeningHours
}

data class CatalogOpeningDay(
    val weekday: Weekday,
    val hours: CatalogDayHours,
)

sealed interface CatalogDayHours {
    data object Closed : CatalogDayHours

    data object Open24Hours : CatalogDayHours

    data class Periods(
        val periods: List<CatalogOpeningPeriod>,
    ) : CatalogDayHours
}

data class CatalogOpeningPeriod(
    val opensMinute: Int,
    val closesMinute: Int,
    val closesNextDay: Boolean,
)

data class CatalogRoomType(
    val name: String,
    val price: MoneyXof,
    val order: Int,
)

data class CatalogEventVenue(
    val id: String,
    val type: ListingType,
    val subtype: String,
    val name: String,
    val city: CatalogCityReference,
    val location: CatalogLocation,
)

data class CatalogEventOrganizer(
    val name: String,
    val contact: String,
)

sealed interface CatalogEventTicketing {
    val externalUrl: String?

    data class Free(
        override val externalUrl: String?,
    ) : CatalogEventTicketing

    data class Paid(
        override val externalUrl: String,
        val tiers: List<CatalogTicketTier>,
    ) : CatalogEventTicketing
}

data class CatalogTicketTier(
    val label: String,
    val price: MoneyXof,
    val order: Int,
)
