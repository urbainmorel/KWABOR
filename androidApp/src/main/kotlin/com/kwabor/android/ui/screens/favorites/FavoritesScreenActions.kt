package com.kwabor.android.ui.screens.favorites

import com.kwabor.shared.presentation.favorites.FavoritesFilter

internal data class FavoritesScreenActions(
    val onBack: () -> Unit,
    val onFilterSelected: (FavoritesFilter) -> Unit,
    val onRetry: () -> Unit,
    val onRefresh: () -> Unit,
    val onLoadNext: () -> Unit,
    val onOpenListing: (String) -> Unit,
    val onRemoveFavorite: (String) -> Unit,
)
