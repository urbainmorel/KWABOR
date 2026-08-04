package com.kwabor.shared.data.local

import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType

internal fun City.toEntity(position: Int): ExploreReferenceCityEntity = ExploreReferenceCityEntity(
    snapshotKey = EXPLORE_REFERENCE_SNAPSHOT_KEY,
    cityId = id,
    position = position,
    name = name,
    countryCode = countryCode,
    latitude = latitude,
    longitude = longitude,
)

internal fun ExploreReferenceCityEntity.toDomain(): City {
    val city = City(
        id = cityId,
        name = name,
        countryCode = countryCode,
        latitude = latitude,
        longitude = longitude,
    )
    city.invalidReferenceFieldOrNull()?.let(::invalidReferenceValue)
    return city
}

internal fun Category.toEntity(position: Int): ExploreReferenceCategoryEntity = ExploreReferenceCategoryEntity(
    snapshotKey = EXPLORE_REFERENCE_SNAPSHOT_KEY,
    categoryId = id,
    position = position,
    nameKey = nameKey,
    listingType = listingType.toReferenceValue(),
    defaultListingClass = defaultListingClass.toReferenceValue(),
)

internal fun ExploreReferenceCategoryEntity.toDomain(): Category {
    val category = Category(
        id = categoryId,
        nameKey = nameKey,
        listingType = listingType.toReferenceListingType(),
        defaultListingClass = defaultListingClass.toReferenceListingClass(),
    )
    category.invalidReferenceFieldOrNull()?.let(::invalidReferenceValue)
    return category
}

private fun ListingType.toReferenceValue(): String = when (this) {
    ListingType.Place -> "place"
    ListingType.Establishment -> "establishment"
    ListingType.Event -> "event"
}

private fun ListingClass.toReferenceValue(): String = when (this) {
    ListingClass.Heritage -> "heritage"
    ListingClass.Commercial -> "commercial"
    ListingClass.Event -> "event"
}

private fun String.toReferenceListingType(): ListingType = when (this) {
    "place" -> ListingType.Place
    "establishment" -> ListingType.Establishment
    "event" -> ListingType.Event
    else -> invalidReferenceValue("listing_type")
}

private fun String.toReferenceListingClass(): ListingClass = when (this) {
    "heritage" -> ListingClass.Heritage
    "commercial" -> ListingClass.Commercial
    "event" -> ListingClass.Event
    else -> invalidReferenceValue("default_listing_class")
}
