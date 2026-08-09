package com.kwabor.android.ui.screens.favorites

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.ui.components.ListingCardState
import com.kwabor.android.ui.components.PriceTagMode
import com.kwabor.android.ui.components.PriceTagOptions
import com.kwabor.android.ui.components.formatPriceTag
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.favorites.FavoriteListingItem
import com.kwabor.shared.presentation.favorites.FavoritesUiState

internal fun favoritesLiveRegionStatus(state: FavoritesUiState, strings: KwaborStrings): String? = when {
    state.errorMessage != null -> listOf(strings.errorStateTitle, state.errorMessage).joinToString(SENTENCE_SEPARATOR)
    state.appendErrorMessage != null ->
        listOf(strings.errorStateTitle, state.appendErrorMessage).joinToString(SENTENCE_SEPARATOR)
    state.refreshMessage != null ->
        listOf(strings.errorStateTitle, state.refreshMessage).joinToString(SENTENCE_SEPARATOR)
    state.mutationMessage != null ->
        listOf(strings.errorStateTitle, state.mutationMessage).joinToString(SENTENCE_SEPARATOR)
    state.isLoading || state.isRefreshing || state.isAppending -> strings.loading
    else -> null
}

internal fun Modifier.favoritesLiveRegion(status: String?): Modifier = if (status == null) {
    this
} else {
    semantics {
        liveRegion = LiveRegionMode.Polite
        stateDescription = status
    }
}

internal fun LazyGridState.hasReachedFavoritesPaginationThreshold(): Boolean {
    val totalItemsCount = layoutInfo.totalItemsCount
    val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return totalItemsCount > 0 &&
        lastVisibleItemIndex >= totalItemsCount - KwaborSizing.FAVORITES_LOAD_MORE_THRESHOLD
}

internal fun favoritesColumnCount(maxWidth: Dp, fontScale: Float): Int = when {
    fontScale >= KwaborSizing.FAVORITES_ACCESSIBILITY_FONT_SCALE -> ACCESSIBILITY_GRID_COLUMNS
    maxWidth < KwaborSizing.ExploreTabletBreakpoint -> KwaborSizing.EXPLORE_MOBILE_GRID_COLUMNS
    else -> KwaborSizing.EXPLORE_TABLET_GRID_COLUMNS
}

internal fun shouldRequestNextFavoritesPage(
    reachedThreshold: Boolean,
    cursor: String?,
    requestedCursor: String?,
    state: FavoritesUiState,
): Boolean {
    if (!reachedThreshold || cursor == null || cursor == requestedCursor) return false
    return state.canLoadMore && state.appendErrorMessage == null
}

internal fun FavoriteListingItem.toFavoriteCardState(): ListingCardState = ListingCardState(
    title = title,
    cityLabel = cityLabel,
    coverImageUrl = coverImageUrl,
    price = price,
    ratingLabel = ratingLabel,
    sponsored = false,
    liked = liked,
    favorited = true,
    eventEnded = isEventEnded,
)

internal fun FavoriteListingItem.favoriteCardAccessibilityDescription(strings: KwaborStrings): String = buildList {
    add(strings.favorites.openListing)
    add(title)
    add(cityLabel)
    coverImageAlt?.let(::add)
    if (verified) add(strings.detail.verified)
    ratingLabel?.let { rating -> add(listOf(strings.rating, rating).joinToString(WORD_SEPARATOR)) }
    add(
        listOf(
            strings.detail.price,
            formatPriceTag(price, strings, PriceTagOptions(mode = PriceTagMode.Compact)),
        ).joinToString(WORD_SEPARATOR),
    )
}.joinToString(SENTENCE_SEPARATOR)

private const val WORD_SEPARATOR = " "
private const val SENTENCE_SEPARATOR = ". "
private const val ACCESSIBILITY_GRID_COLUMNS = 1
