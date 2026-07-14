package com.kwabor.android.ui.screens.explore

import com.kwabor.shared.presentation.explore.ExploreChip
import com.kwabor.shared.presentation.explore.ExploreTab

data class ExploreScreenActions(
    val onTabSelected: (ExploreTab) -> Unit = {},
    val onChipSelected: (ExploreChip) -> Unit = {},
    val onSearchClick: () -> Unit = {},
    val onFilterClick: () -> Unit = {},
    val onListingClick: (String) -> Unit = {},
    val onLikeClick: (String) -> Unit = {},
    val onFavoriteClick: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAssistantClick: () -> Unit = {},
)
