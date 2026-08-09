package com.kwabor.android.ui.screens.explore

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.kwabor.android.design.KwaborSizing
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.explore.ExploreListingItem
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.money.PriceLabelFormatter

internal fun exploreFeedLiveRegionStatus(state: ExploreUiState, strings: KwaborStrings): String? = when {
    state.hasError -> listOfNotNull(strings.errorStateTitle, state.errorMessage).joinToString(separator = ". ")
    state.appendErrorMessage != null -> listOfNotNull(strings.errorStateTitle, state.appendErrorMessage)
        .joinToString(separator = ". ")
    state.isLoading || state.isAppending -> strings.loading
    else -> null
}

internal fun Modifier.exploreLiveRegion(status: String?): Modifier = if (status == null) {
    this
} else {
    semantics {
        liveRegion = LiveRegionMode.Polite
        stateDescription = status
    }
}

internal fun LazyGridState.hasReachedPaginationThreshold(): Boolean {
    val totalItemsCount = layoutInfo.totalItemsCount
    val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return totalItemsCount > 0 &&
        lastVisibleItemIndex >= totalItemsCount - KwaborSizing.EXPLORE_LOAD_MORE_THRESHOLD
}

internal fun ExploreListingItem.cardAccessibilityDescription(strings: KwaborStrings): String = buildList {
    add(title)
    coverImageAlt
        ?.takeIf { alt -> alt.isNotBlank() && alt != title }
        ?.let(::add)
    add(cityLabel)
    if (sponsored) {
        add(strings.sponsored)
    } else {
        ratingLabel?.let { rating -> add("${strings.rating} $rating") }
    }
    eventDateLabel?.takeIf(String::isNotBlank)?.let(::add)
    add(PriceLabelFormatter.compactXof(price, strings.free))
}.joinToString(separator = ". ")
