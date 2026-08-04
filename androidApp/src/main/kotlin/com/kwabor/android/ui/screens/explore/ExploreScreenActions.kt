package com.kwabor.android.ui.screens.explore

import com.kwabor.shared.presentation.explore.ExploreChip
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.search.SearchScope

data class ExploreScreenActions(
    val onTabSelected: (ExploreTab) -> Unit,
    val onChipSelected: (ExploreChip) -> Unit,
    val onListingClick: (String) -> Unit,
    val onLikeClick: (String) -> Unit,
    val onFavoriteClick: (String) -> Unit,
    val onRetry: () -> Unit,
    val onRefresh: () -> Unit,
    val onLoadNext: () -> Unit,
    val onCityClick: () -> Unit,
    val onCityDismiss: () -> Unit,
    val onCitySelected: (String) -> Unit,
    val onUseLocation: () -> Unit,
    val onGuideDiscoveryClick: () -> Unit,
    val onSearchActivate: () -> Unit = {},
    val onSearchQueryChanged: (String) -> Unit = {},
    val onSearchSubmit: () -> Unit = {},
    val onSearchClear: () -> Unit = {},
    val onSearchClose: () -> Unit = {},
    val onSearchScopeSelected: (SearchScope) -> Unit = {},
    val onSearchRetry: () -> Unit = {},
    val onSearchRefresh: () -> Unit = {},
    val onSearchLoadNext: () -> Unit = {},
    val onSearchListingClick: (String) -> Unit = {},
    val onSearchAssistantClick: (() -> Unit)? = null,
)
