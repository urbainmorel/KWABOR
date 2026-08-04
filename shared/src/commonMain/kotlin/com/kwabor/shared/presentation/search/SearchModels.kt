package com.kwabor.shared.presentation.search

import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.search.SearchResultSource
import com.kwabor.shared.presentation.explore.ExploreCityOption
import com.kwabor.shared.presentation.explore.ExploreListingItem
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.toListingType

enum class SearchScope {
    ActiveTab,
    All,
}

data class SearchContext(
    val selectedTab: ExploreTab = ExploreTab.Places,
    val selectedChipId: String? = null,
    val selectedCityId: String? = null,
    val availableCities: List<ExploreCityOption> = emptyList(),
    val currency: KwaborCurrency = KwaborCurrency.Xof,
)

data class SearchUiState(
    val context: SearchContext = SearchContext(),
    val scope: SearchScope = SearchScope.ActiveTab,
    val queryText: String = "",
    val submittedQueryText: String? = null,
    val listings: List<ExploreListingItem> = emptyList(),
    val nextCursor: String? = null,
    val resultSource: SearchResultSource? = null,
    val networkUnavailable: Boolean = false,
    val resultCountLabel: String = "",
    val isActive: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val queryErrorMessage: String? = null,
    val errorMessage: String? = null,
    val refreshMessage: String? = null,
    val appendErrorMessage: String? = null,
) {
    val isActiveTabScope: Boolean
        get() = scope == SearchScope.ActiveTab

    val isAllScope: Boolean
        get() = scope == SearchScope.All

    val hasSubmittedQuery: Boolean
        get() = submittedQueryText != null

    val isOffline: Boolean
        get() = resultSource == SearchResultSource.LocalCache || networkUnavailable

    val hasError: Boolean
        get() = errorMessage != null

    val isEmpty: Boolean
        get() = hasSubmittedQuery && !isLoading && !isRefreshing && !hasError && listings.isEmpty()

    val canLoadMore: Boolean
        get() = nextCursor != null && !isLoading && !isRefreshing && !isAppending
}

sealed interface SearchIntent {
    data class Activate(val context: SearchContext) : SearchIntent

    data class UpdateContext(val context: SearchContext) : SearchIntent

    data class QueryChanged(val text: String) : SearchIntent

    data class SelectScope(val scope: SearchScope) : SearchIntent

    data object Submit : SearchIntent

    data object Clear : SearchIntent

    data object Close : SearchIntent

    data object Retry : SearchIntent

    data object Refresh : SearchIntent

    data object LoadNext : SearchIntent

    data class OpenListing(val listingId: String) : SearchIntent

    data object OpenAssistant : SearchIntent
}

sealed interface SearchEffect {
    data class QuerySubmitted(val displayCurrency: KwaborCurrency) : SearchEffect

    data class OpenCatalogDetail(val listingId: String) : SearchEffect

    data object OpenAssistant : SearchEffect
}

internal fun SearchContext.filtersFor(scope: SearchScope): ListingFilters = when (scope) {
    SearchScope.ActiveTab -> ListingFilters(
        cityId = selectedCityId,
        categoryId = selectedChipId,
        listingType = selectedTab.toListingType(),
    )
    SearchScope.All -> ListingFilters(cityId = selectedCityId)
}

fun ExploreUiState.toSearchContext(): SearchContext = SearchContext(
    selectedTab = selectedTab,
    selectedChipId = selectedChipId,
    selectedCityId = selectedCityId,
    availableCities = availableCities,
    currency = currency,
)
