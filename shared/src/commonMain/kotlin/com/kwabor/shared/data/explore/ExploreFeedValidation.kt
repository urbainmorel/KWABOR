package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.isWithinBeninBounds
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.explore.ExploreFeedQuery

internal fun ListingSummaryPage.isValidFirstPage(
    query: ExploreFeedQuery,
    cities: List<City>,
    categories: List<Category>,
): Boolean = items.size <= query.pageSize &&
    items.all(ListingSummary::isValidForExploreCache) &&
    query.acceptsItemsWithKnownReferences(items, cities, categories) &&
    nextCursor.isValidCursor() &&
    (items.isNotEmpty() || nextCursor == null)

internal fun ListingSummaryPage.isProgressiveAfter(
    cursor: String,
    query: ExploreFeedQuery,
    cities: List<City>,
    categories: List<Category>,
    existingListingIds: Set<String>,
): Boolean = isValidFirstPage(query, cities, categories) &&
    nextCursor != cursor &&
    (nextCursor == null || items.any { listing -> listing.id !in existingListingIds })

internal fun List<City>.areCitiesValidForExploreCache(): Boolean = size <= MAX_EXPLORE_CITY_COUNT &&
    map(City::id).distinct().size == size &&
    all(City::isValidForExploreCache)

internal fun List<Category>.areCategoriesValidForExploreCache(): Boolean = size <= MAX_EXPLORE_CATEGORY_COUNT &&
    map(Category::id).distinct().size == size &&
    all(Category::isValidForExploreCache)

internal fun ExploreFeedQuery.invalidReferenceFilterOrNull(
    cities: List<City>,
    categories: List<Category>,
): DomainError? = when {
    filters.cityId != null && cities.none { city -> city.id == filters.cityId } ->
        DomainError.Validation(EXPLORE_CITY_UNAVAILABLE_ERROR_KEY)
    filters.categoryId != null && categories.none { category -> category.id == filters.categoryId } ->
        DomainError.Validation(EXPLORE_CATEGORY_UNAVAILABLE_ERROR_KEY)
    else -> null
}

internal fun ExploreFeedQuery.acceptsItemsWithKnownReferences(
    items: List<ListingSummary>,
    cities: List<City>,
    categories: List<Category>,
): Boolean {
    val cityIds = cities.mapTo(mutableSetOf(), City::id)
    val categoriesById = categories.associateBy(Category::id)
    return items.all { listing -> acceptsListing(listing, cityIds, categoriesById) }
}

private fun ExploreFeedQuery.acceptsListing(
    listing: ListingSummary,
    cityIds: Set<String>,
    categoriesById: Map<String, Category>,
): Boolean = listing.hasKnownReferences(cityIds, categoriesById) && acceptsFilters(listing)

private fun ListingSummary.hasKnownReferences(cityIds: Set<String>, categoriesById: Map<String, Category>): Boolean =
    cityId in cityIds && categoriesById[categoryId]?.listingType == type

private fun ExploreFeedQuery.acceptsFilters(listing: ListingSummary): Boolean =
    (filters.cityId == null || listing.cityId == filters.cityId) &&
        (filters.categoryId == null || listing.categoryId == filters.categoryId) &&
        (filters.listingType == null || listing.type == filters.listingType) &&
        (filters.listingClass == null || listing.listingClass == filters.listingClass) &&
        (!filters.onlyPublished || listing.status == ListingStatus.Published)

private fun String?.isValidCursor(): Boolean = this == null || (isNotBlank() && length <= MAX_EXPLORE_CURSOR_LENGTH)

private fun City.isValidForExploreCache(): Boolean = id.isValidRequiredText(MAX_EXPLORE_ID_LENGTH) &&
    name.isValidRequiredText(MAX_EXPLORE_CITY_NAME_LENGTH) &&
    countryCode == BENIN_COUNTRY_CODE &&
    coordinatesAreValid()

private fun City.coordinatesAreValid(): Boolean = when {
    (latitude == null) != (longitude == null) -> false
    latitude == null -> true
    longitude == null -> false
    else -> GeoPoint(latitude = latitude, longitude = longitude).isWithinBeninBounds
}

private fun Category.isValidForExploreCache(): Boolean = id.isValidRequiredText(MAX_EXPLORE_ID_LENGTH) &&
    nameKey.isValidRequiredText(MAX_EXPLORE_CATEGORY_NAME_KEY_LENGTH)

private fun ListingSummary.isValidForExploreCache(): Boolean = id.isValidRequiredText(MAX_EXPLORE_ID_LENGTH) &&
    name.isValidRequiredText(MAX_EXPLORE_LISTING_NAME_LENGTH, MIN_EXPLORE_LISTING_NAME_LENGTH) &&
    cityId.isValidRequiredText(MAX_EXPLORE_ID_LENGTH) &&
    categoryId.isValidRequiredText(MAX_EXPLORE_ID_LENGTH) &&
    coverImageUrl.isValidOptionalUrl() &&
    ratingAverage.isValidOptionalRating() &&
    likesCount >= 0 &&
    (sponsoredUntilEpochMilliseconds == null || sponsoredUntilEpochMilliseconds >= 0)

private fun String.isValidRequiredText(maximumLength: Int, minimumLength: Int = 1): Boolean =
    isNotBlank() && length in minimumLength..maximumLength

private fun String?.isValidOptionalUrl(): Boolean = this == null || (isNotBlank() && length <= MAX_EXPLORE_URL_LENGTH)

private fun Double?.isValidOptionalRating(): Boolean = this == null || (isFinite() && this in MIN_RATING..MAX_RATING)

private const val MAX_EXPLORE_CITY_COUNT = 256
private const val MAX_EXPLORE_CATEGORY_COUNT = 512
private const val MAX_EXPLORE_ID_LENGTH = 128
private const val MAX_EXPLORE_CITY_NAME_LENGTH = 120
private const val MAX_EXPLORE_CATEGORY_NAME_KEY_LENGTH = 160
private const val MIN_EXPLORE_LISTING_NAME_LENGTH = 3
private const val MAX_EXPLORE_LISTING_NAME_LENGTH = 120
private const val MAX_EXPLORE_URL_LENGTH = 2_048
private const val MAX_EXPLORE_CURSOR_LENGTH = 4_096
private const val MIN_RATING = 0.0
private const val MAX_RATING = 5.0
private const val BENIN_COUNTRY_CODE = "BJ"
private const val EXPLORE_CITY_UNAVAILABLE_ERROR_KEY = "error.explore.city_unavailable"
private const val EXPLORE_CATEGORY_UNAVAILABLE_ERROR_KEY = "error.explore.category_unavailable"
