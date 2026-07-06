package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.i18n.KwaborStrings
import kotlin.math.roundToInt

class ExplorePresenter(
    private val catalogRepository: CatalogRepository,
    private val clockProvider: ClockProvider,
) {
    suspend fun load(request: ExploreLoadRequest, strings: KwaborStrings): ExploreUiState {
        val cities = when (val result = catalogRepository.listCities()) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return request.errorState(strings, result.error)
        }
        val categories = when (val result = catalogRepository.listCategories()) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return request.errorState(strings, result.error)
        }
        val listings = when (
            val result = catalogRepository.listListings(
                filters = request.toFilters(categories),
                page = PageRequest(limit = EXPLORE_PAGE_SIZE),
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return request.errorState(strings, result.error)
        }

        val nowEpochMilliseconds = clockProvider.nowEpochMilliseconds()
        val cityNamesById = cities.associate { city -> city.id to city.name }

        return initialExploreUiState(strings = strings, request = request).copy(
            cityLabel = cities.homeCityLabel(strings),
            listings = listings.items.map { listing ->
                listing.toExploreListingItem(
                    cityNamesById = cityNamesById,
                    nowEpochMilliseconds = nowEpochMilliseconds,
                )
            },
        )
    }

    private fun ExploreLoadRequest.toFilters(categories: List<Category>): ListingFilters {
        val categoryId = selectedChipId
            ?.let { chipId -> chipId.toKnownCategoryId(categories) }

        return ListingFilters(
            categoryId = categoryId,
            listingType = selectedTab.toListingType(),
            onlyPublished = true,
        )
    }

    private fun ListingSummary.toExploreListingItem(
        cityNamesById: Map<String, String>,
        nowEpochMilliseconds: Long,
    ): ExploreListingItem = ExploreListingItem(
        id = id,
        title = name,
        cityLabel = cityNamesById[cityId] ?: cityId,
        coverImageUrl = coverImageUrl,
        price = priceFromXof,
        ratingLabel = ratingAverage?.toRatingLabel(),
        sponsored = sponsoredUntilEpochMilliseconds?.let { sponsoredUntil ->
            sponsoredUntil > nowEpochMilliseconds
        } ?: false,
    )

    private fun ExploreLoadRequest.errorState(strings: KwaborStrings, error: DomainError): ExploreUiState =
        initialExploreUiState(strings = strings, request = this).copy(
            isOffline = error is DomainError.NetworkUnavailable,
            errorMessage = error.toExploreMessage(strings),
        )

    private fun List<City>.homeCityLabel(strings: KwaborStrings): String =
        firstOrNull { city -> city.name.equals(strings.currentCity, ignoreCase = true) }?.name
            ?: firstOrNull()?.name
            ?: strings.currentCity

    private fun String.toKnownCategoryId(categories: List<Category>): String? {
        val knownCategoryIds = categories.mapTo(mutableSetOf()) { category -> category.id }
        return CATEGORY_IDS_BY_CHIP[this]?.firstOrNull { categoryId -> categoryId in knownCategoryIds }
    }

    private fun DomainError.toExploreMessage(strings: KwaborStrings): String = when (this) {
        is DomainError.NetworkUnavailable -> strings.offlineBanner
        is DomainError.AuthenticationRequired,
        is DomainError.NotFound,
        is DomainError.PermissionDenied,
        is DomainError.Unexpected,
        is DomainError.Validation,
        -> strings.errorStateTitle
    }

    private fun Double.toRatingLabel(): String {
        val rounded = (this * 10).roundToInt() / 10.0
        return rounded.toString().replace(oldChar = '.', newChar = ',')
    }

    private companion object {
        const val EXPLORE_PAGE_SIZE = 20

        val CATEGORY_IDS_BY_CHIP = mapOf(
            "history" to listOf("heritage-historique"),
            "nature" to listOf("heritage-nature"),
            "markets" to listOf("commercial-marche"),
            "hotels" to listOf("commercial-hotel"),
            "restaurants" to listOf("commercial-restaurant"),
        )
    }
}
