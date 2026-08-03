package com.kwabor.android.app

import com.kwabor.android.presentation.detail.CatalogDetailViewModel
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.ui.screens.detail.CatalogDetailSheetActions
import com.kwabor.android.ui.screens.explore.ExploreScreenActions
import com.kwabor.shared.presentation.detail.CatalogDetailIntent

internal fun ExploreViewModel.detailEnabledScreenActions(onListingClick: (String) -> Unit): ExploreScreenActions =
    ExploreScreenActions(
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
    )

internal val CatalogDetailViewModel.sheetActions: CatalogDetailSheetActions
    get() = CatalogDetailSheetActions(
        onDismiss = { onIntent(CatalogDetailIntent.Close) },
        onRetry = { onIntent(CatalogDetailIntent.Retry) },
        onMediaSelected = { index -> onIntent(CatalogDetailIntent.SelectMedia(index)) },
        onDescriptionToggle = { onIntent(CatalogDetailIntent.ToggleDescription) },
    )
