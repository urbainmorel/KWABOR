package com.kwabor.shared.data.catalog

import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.ListingLikeMutation
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
    @SerialName("latitude")
    val latitude: Double? = null,
    @SerialName("longitude")
    val longitude: Double? = null,
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

@Serializable
internal data class ListingLikeMutationDto(
    @SerialName("listing_id")
    val listingId: String,
    @SerialName("liked")
    val liked: Boolean,
    @SerialName("likes_count")
    val likesCount: Int?,
    @SerialName("mutated_at")
    val mutatedAt: String,
)

@Serializable
internal data class SetListingLikeRpcDto(
    @SerialName("p_expected_account_id")
    val expectedAccountId: String,
    @SerialName("p_listing_id")
    val listingId: String,
    @SerialName("p_liked")
    val liked: Boolean,
)

internal fun CityDto.toDomain(): City = City(
    id = id,
    name = name,
    countryCode = countryCode,
    latitude = latitude,
    longitude = longitude,
)

internal fun CategoryDto.toDomain(): Category = Category(
    id = id,
    nameKey = nameKey,
    listingType = listingType.toListingType(),
    defaultListingClass = defaultListingClass.toListingClass(),
)

internal fun ListingViewerInteractionDto.toDomain(): ListingViewerInteraction = ListingViewerInteraction(
    listingId = listingId,
    likedByViewer = likedByCurrentUser,
    favoritedByViewer = favoritedByCurrentUser,
    likesCount = likesCount.toNonNegativeCount("listings.likes_count"),
)

internal fun ListingLikeMutationDto.toDomain(expectedListingId: String, expectedLiked: Boolean): ListingLikeMutation {
    val mappedListingId = listingId.requireCatalogMutationUuid("listing_id")
    if (mappedListingId != expectedListingId || liked != expectedLiked) {
        invalidCatalogMutationValue("target_state")
    }
    val mappedMutationTime = mutatedAt.toEpochMilliseconds()
    if (mappedMutationTime < 0L) {
        invalidCatalogMutationValue("mutated_at")
    }
    return ListingLikeMutation(
        listingId = mappedListingId,
        liked = liked,
        likesCount = likesCount?.toNonNegativeCount("listings.likes_count"),
        mutatedAtEpochMilliseconds = mappedMutationTime,
    )
}

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

internal fun String.toListingType(): ListingType = when (this) {
    "lieu" -> ListingType.Place
    "etablissement" -> ListingType.Establishment
    "evenement" -> ListingType.Event
    else -> invalidDatabaseValue("listings.type", this)
}

internal fun String.toListingClass(): ListingClass = when (this) {
    "patrimonial" -> ListingClass.Heritage
    "commercial" -> ListingClass.Commercial
    "evenementiel" -> ListingClass.Event
    else -> invalidDatabaseValue("listings.listing_class", this)
}

internal fun String.toListingStatus(): ListingStatus = when (this) {
    "brouillon" -> ListingStatus.Draft
    "en_attente" -> ListingStatus.PendingReview
    "publie" -> ListingStatus.Published
    "rejete" -> ListingStatus.Rejected
    "archive" -> ListingStatus.Archived
    else -> invalidDatabaseValue("listings.status", this)
}

internal fun String.toEpochMilliseconds(): Long = try {
    Instant.parse(this).toEpochMilliseconds()
} catch (exception: IllegalArgumentException) {
    throw CatalogDataException.Unexpected(exception)
}

internal fun Long.toNonNegativeMoney(fieldName: String): MoneyXof = when (val result = MoneyXof.fromAmount(this)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> invalidDatabaseValue(fieldName, toString())
}

internal fun Int.toNonNegativeCount(fieldName: String): Int {
    if (this >= 0) {
        return this
    }

    invalidDatabaseValue(fieldName, toString())
}

private fun invalidDatabaseValue(fieldName: String, value: String): Nothing {
    throw CatalogDataException.Unexpected(
        IllegalStateException("Invalid database value for $fieldName: $value"),
    )
}

private fun String.requireCatalogMutationUuid(fieldName: String): String {
    if (!isValidUuid() || this != lowercase()) {
        invalidCatalogMutationValue(fieldName)
    }
    return this
}

private fun invalidCatalogMutationValue(fieldName: String): Nothing {
    throw CatalogDataException.Unexpected(
        IllegalStateException("Catalog like mutation returned an invalid $fieldName."),
    )
}
