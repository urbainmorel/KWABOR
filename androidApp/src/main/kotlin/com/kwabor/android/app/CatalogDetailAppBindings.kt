package com.kwabor.android.app

import com.kwabor.android.presentation.detail.CatalogDetailViewModel
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.search.SearchIntent
import com.kwabor.android.presentation.search.SearchViewModel
import com.kwabor.android.ui.screens.detail.CatalogDetailSheetActions
import com.kwabor.android.ui.screens.explore.ExploreScreenActions
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.explore.ExploreUiState

internal fun ExploreViewModel.detailEnabledScreenActions(
    onListingClick: (String) -> Unit,
    onGuideDiscoveryClick: () -> Unit,
): ExploreScreenActions = ExploreScreenActions(
    onTabSelected = { tab -> onIntent(ExploreIntent.SelectTab(tab)) },
    onChipSelected = { chip -> onIntent(ExploreIntent.SelectChip(chip)) },
    onListingClick = onListingClick,
    onRetry = { onIntent(ExploreIntent.Retry) },
    onRefresh = { onIntent(ExploreIntent.Refresh) },
    onLoadNext = { onIntent(ExploreIntent.LoadNext) },
    onCityClick = { onIntent(ExploreIntent.OpenCitySelector) },
    onCityDismiss = { onIntent(ExploreIntent.CloseCitySelector) },
    onCitySelected = { cityId -> onIntent(ExploreIntent.SelectCity(cityId)) },
    onUseLocation = { onIntent(ExploreIntent.RequestLocation) },
    onLikeClick = { listingId -> onIntent(ExploreIntent.ToggleLike(listingId)) },
    onFavoriteClick = { listingId -> onIntent(ExploreIntent.ToggleFavorite(listingId)) },
    onGuideDiscoveryClick = onGuideDiscoveryClick,
)

internal fun ExploreScreenActions.withSearch(
    searchViewModel: SearchViewModel,
    exploreState: ExploreUiState,
): ExploreScreenActions = copy(
    onSearchActivate = { searchViewModel.onIntent(SearchIntent.Activate(exploreState)) },
    onSearchQueryChanged = { text -> searchViewModel.onIntent(SearchIntent.QueryChanged(text)) },
    onSearchSubmit = { searchViewModel.onIntent(SearchIntent.Submit) },
    onSearchClear = { searchViewModel.onIntent(SearchIntent.Clear) },
    onSearchClose = { searchViewModel.onIntent(SearchIntent.Close) },
    onSearchScopeSelected = { scope -> searchViewModel.onIntent(SearchIntent.SelectScope(scope)) },
    onSearchRetry = { searchViewModel.onIntent(SearchIntent.Retry) },
    onSearchRefresh = { searchViewModel.onIntent(SearchIntent.Refresh) },
    onSearchLoadNext = { searchViewModel.onIntent(SearchIntent.LoadNext) },
    onSearchListingClick = { listingId -> searchViewModel.onIntent(SearchIntent.OpenListing(listingId)) },
)

internal val CatalogDetailViewModel.sheetActions: CatalogDetailSheetActions
    get() = CatalogDetailSheetActions(
        onDismiss = { onIntent(CatalogDetailIntent.Close) },
        onRetry = { onIntent(CatalogDetailIntent.Retry) },
        onMediaSelected = { index -> onIntent(CatalogDetailIntent.SelectMedia(index)) },
        onDescriptionToggle = { onIntent(CatalogDetailIntent.ToggleDescription) },
    )
