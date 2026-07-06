package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingContact
import com.kwabor.shared.domain.catalog.ListingDetail
import com.kwabor.shared.domain.catalog.ListingMedia
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.catalog.PriceUnit
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
internal data class CityDto(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("country_code")
    val countryCode: String = "BJ",
)

@Serializable
internal data class CategoryDto(
    @SerialName("id")
    val id: String,
    @SerialName("listing_type")
    val listingType: String,
    @SerialName("name_key")
    val nameKey: String,
    @SerialName("default_listing_class")
    val defaultListingClass: String,
)

@Serializable
internal data class ListingDto(
    @SerialName("id")
    val id: String,
    @SerialName("type")
    val type: String,
    @SerialName("listing_class")
    val listingClass: String,
    @SerialName("category_id")
    val categoryId: String,
    @SerialName("owner_id")
    val ownerId: String? = null,
    @SerialName("steward_id")
    val stewardId: String? = null,
    @SerialName("status")
    val status: String,
    @SerialName("name")
    val name: String,
    @SerialName("slug")
    val slug: String,
    @SerialName("description")
    val description: String,
    @SerialName("content_lang")
    val contentLang: String,
    @SerialName("city_id")
    val cityId: String,
    @SerialName("district")
    val district: String? = null,
    @SerialName("address")
    val address: String? = null,
    @SerialName("lat")
    val latitude: Double? = null,
    @SerialName("lng")
    val longitude: Double? = null,
    @SerialName("price_from_xof")
    val priceFromXof: Long? = null,
    @SerialName("price_unit")
    val priceUnit: String,
    @SerialName("contact_phone")
    val contactPhone: String? = null,
    @SerialName("contact_whatsapp")
    val contactWhatsapp: String? = null,
    @SerialName("external_url")
    val externalUrl: String? = null,
    @SerialName("email")
    val email: String? = null,
    @SerialName("tags")
    val tags: List<String> = emptyList(),
    @SerialName("verified")
    val verified: Boolean = false,
    @SerialName("sponsored_until")
    val sponsoredUntil: String? = null,
    @SerialName("rating_avg")
    val ratingAverage: Double? = null,
    @SerialName("likes_count")
    val likesCount: Int = 0,
    @SerialName("published_at")
    val publishedAt: String? = null,
)

@Serializable
internal data class ListingMediaDto(
    @SerialName("url")
    val url: String,
    @SerialName("alt")
    val alt: String,
    @SerialName("display_order")
    val displayOrder: Int,
    @SerialName("is_cover")
    val isCover: Boolean,
)

internal data class ListingSummaryDto(
    val listing: ListingDto,
    val coverImageUrl: String?,
)

internal data class ListingDetailDto(
    val listing: ListingDto,
    val media: List<ListingMediaDto>,
)

@Serializable
internal data class ListingViewerInteractionDto(
    @SerialName("listing_id")
    val listingId: String,
    @SerialName("liked_by_current_user")
    val likedByCurrentUser: Boolean,
    @SerialName("favorited_by_current_user")
    val favoritedByCurrentUser: Boolean,
    @SerialName("likes_count")
    val likesCount: Int,
)

@Serializable
internal data class ListingInteractionRpcDto(
    @SerialName("p_listing_id")
    val listingId: String,
)

@Serializable
internal data class ListingInteractionsRpcDto(
    @SerialName("p_listing_ids")
    val listingIds: List<String>,
)

internal fun CityDto.toDomain(): City = City(
    id = id,
    name = name,
    countryCode = countryCode,
)

internal fun CategoryDto.toDomain(): Category = Category(
    id = id,
    nameKey = nameKey,
    listingType = listingType.toListingType(),
    defaultListingClass = defaultListingClass.toListingClass(),
)

internal fun ListingSummaryDto.toDomain(): ListingSummary = listing.toSummaryDomain(
    coverImageUrl = coverImageUrl,
)

internal fun ListingDetailDto.toDomain(): ListingDetail {
    val sortedMedia = media.sortedBy { item -> item.displayOrder }
    return ListingDetail(
        summary = listing.toSummaryDomain(
            coverImageUrl = sortedMedia.firstOrNull { item -> item.isCover }?.url ?: sortedMedia.firstOrNull()?.url,
        ),
        slug = listing.slug,
        description = listing.description,
        contentLocale = listing.contentLang.toAppLocale(),
        district = listing.district,
        address = listing.address,
        geoPoint = listing.toGeoPoint(),
        contact = ListingContact(
            phone = listing.contactPhone,
            whatsapp = listing.contactWhatsapp,
            externalUrl = listing.externalUrl,
            email = listing.email,
        ),
        media = sortedMedia.map { item -> item.toDomain() },
        tags = listing.tags,
        ownerId = listing.ownerId,
        stewardId = listing.stewardId,
        publishedAtEpochMilliseconds = listing.publishedAt?.toEpochMilliseconds(),
    )
}

internal fun ListingViewerInteractionDto.toDomain(): ListingViewerInteraction = ListingViewerInteraction(
    listingId = listingId,
    likedByViewer = likedByCurrentUser,
    favoritedByViewer = favoritedByCurrentUser,
    likesCount = likesCount.toNonNegativeCount("listings.likes_count"),
)

internal fun ListingType.toDatabaseValue(): String = when (this) {
    ListingType.Place -> "lieu"
    ListingType.Establishment -> "etablissement"
    ListingType.Event -> "evenement"
}

internal fun ListingClass.toDatabaseValue(): String = when (this) {
    ListingClass.Heritage -> "patrimonial"
    ListingClass.Commercial -> "commercial"
    ListingClass.Event -> "evenementiel"
}

private fun ListingDto.toSummaryDomain(coverImageUrl: String?): ListingSummary {
    priceUnit.toPriceUnit()
    return ListingSummary(
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
        likesCount = likesCount,
        verified = verified,
        sponsoredUntilEpochMilliseconds = sponsoredUntil?.toEpochMilliseconds(),
    )
}

private fun ListingDto.toGeoPoint(): GeoPoint? {
    val lat = latitude ?: return null
    val lng = longitude ?: return null
    return GeoPoint(latitude = lat, longitude = lng)
}

private fun ListingMediaDto.toDomain(): ListingMedia = ListingMedia(
    url = url,
    alt = alt,
    order = displayOrder,
    isCover = isCover,
)

private fun String.toListingType(): ListingType = when (this) {
    "lieu" -> ListingType.Place
    "etablissement" -> ListingType.Establishment
    "evenement" -> ListingType.Event
    else -> invalidDatabaseValue("listings.type", this)
}

private fun String.toListingClass(): ListingClass = when (this) {
    "patrimonial" -> ListingClass.Heritage
    "commercial" -> ListingClass.Commercial
    "evenementiel" -> ListingClass.Event
    else -> invalidDatabaseValue("listings.listing_class", this)
}

private fun String.toListingStatus(): ListingStatus = when (this) {
    "brouillon" -> ListingStatus.Draft
    "en_attente" -> ListingStatus.PendingReview
    "publie" -> ListingStatus.Published
    "rejete" -> ListingStatus.Rejected
    "archive" -> ListingStatus.Archived
    else -> invalidDatabaseValue("listings.status", this)
}

private fun String.toPriceUnit(): PriceUnit = when (this) {
    "par_nuit" -> PriceUnit.PerNight
    "par_personne" -> PriceUnit.PerPerson
    "consommation" -> PriceUnit.Consumption
    "par_entree" -> PriceUnit.PerEntry
    "aucune" -> PriceUnit.None
    else -> invalidDatabaseValue("listings.price_unit", this)
}

private fun String.toAppLocale(): AppLocale = AppLocale.entries.firstOrNull { locale -> locale.tag == this }
    ?: invalidDatabaseValue("listings.content_lang", this)

private fun String.toEpochMilliseconds(): Long = Instant.parse(this).toEpochMilliseconds()

private fun Long.toNonNegativeMoney(fieldName: String): MoneyXof = when (val result = MoneyXof.fromAmount(this)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> invalidDatabaseValue(fieldName, toString())
}

private fun Int.toNonNegativeCount(fieldName: String): Int {
    if (this >= 0) {
        return this
    }

    invalidDatabaseValue(fieldName, toString())
}

private fun invalidDatabaseValue(fieldName: String, value: String): Nothing {
    error("Invalid database value for $fieldName: $value")
}
