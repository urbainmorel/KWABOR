package com.kwabor.android.ui.screens.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.ui.components.KwaborInlineBanner
import com.kwabor.android.ui.components.KwaborSkeletonCard
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.android.ui.components.ListingCard
import com.kwabor.android.ui.components.ListingCardActions
import com.kwabor.android.ui.components.ListingCardState
import com.kwabor.android.ui.components.OfflineBanner
import com.kwabor.android.ui.components.PriceTagMode
import com.kwabor.android.ui.components.PriceTagOptions
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.explore.ExploreChip
import com.kwabor.shared.presentation.explore.ExploreListingItem
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.label

@Composable
fun ExploreScreen(
    state: ExploreUiState,
    strings: KwaborStrings,
    isGuestSession: Boolean = true,
    modifier: Modifier = Modifier,
    actions: ExploreScreenActions = ExploreScreenActions(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.isOffline) {
                OfflineBanner(strings = strings)
            }
            state.interactionMessage?.let { message ->
                KwaborInlineBanner(text = message)
            }
            state.refreshMessage?.let { message ->
                KwaborInlineBanner(text = message)
            }
            state.locationMessage
                ?.takeUnless { state.isCitySelectorOpen }
                ?.let { message -> KwaborInlineBanner(text = message) }
            ExploreContent(
                state = state,
                strings = strings,
                isGuestSession = isGuestSession,
                actions = actions,
            )
        }
        ExploreAssistantButton(
            strings = strings,
            onClick = actions.onAssistantClick,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
    if (state.isCitySelectorOpen) {
        ExploreCitySelectorSheet(
            state = state,
            strings = strings,
            actions = actions,
        )
    }
}

@Composable
private fun ExploreAssistantButton(strings: KwaborStrings, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.padding(end = KwaborSpacing.Lg, bottom = KwaborSpacing.Xxl)
            .size(KwaborSizing.FloatingActionButton),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
    ) {
        Icon(imageVector = Icons.Filled.AutoAwesome, contentDescription = strings.aiAssistant)
    }
}

@Composable
private fun ExploreContent(
    state: ExploreUiState,
    strings: KwaborStrings,
    isGuestSession: Boolean,
    actions: ExploreScreenActions,
) {
    val gridState = rememberLazyGridState()
    val gridContent = ExploreGridContent(
        state = state,
        strings = strings,
        isGuestSession = isGuestSession,
    )
    val paginationGuard = remember(state.selectedTab, state.selectedChipId, state.selectedCityId) {
        ExplorePaginationGuard()
    }
    ExplorePaginationEffect(
        state = state,
        gridState = gridState,
        guard = paginationGuard,
        onLoadNext = actions.onLoadNext,
    )
    ExploreRefreshableGrid(
        content = gridContent,
        gridState = gridState,
        onAppendRetry = {
            paginationGuard.requestedCursor = state.nextCursor
            actions.onLoadNext()
        },
        actions = actions,
    )
}

private data class ExploreGridContent(
    val state: ExploreUiState,
    val strings: KwaborStrings,
    val isGuestSession: Boolean,
)

private class ExplorePaginationGuard(var requestedCursor: String? = null)

@Composable
private fun ExplorePaginationEffect(
    state: ExploreUiState,
    gridState: LazyGridState,
    guard: ExplorePaginationGuard,
    onLoadNext: () -> Unit,
) {
    val reachedPaginationThreshold by remember(gridState) {
        derivedStateOf { gridState.hasReachedPaginationThreshold() }
    }

    LaunchedEffect(
        reachedPaginationThreshold,
        state.nextCursor,
        state.canLoadMore,
        state.appendErrorMessage,
    ) {
        val cursor = state.nextCursor
        val isNewCursor = cursor != null && guard.requestedCursor != cursor
        val paginationAvailable = state.canLoadMore && state.appendErrorMessage == null
        if (
            reachedPaginationThreshold &&
            isNewCursor &&
            paginationAvailable
        ) {
            guard.requestedCursor = cursor
            onLoadNext()
        }
    }
    LaunchedEffect(state.isRefreshing) {
        if (state.isRefreshing) {
            guard.requestedCursor = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreRefreshableGrid(
    content: ExploreGridContent,
    gridState: LazyGridState,
    onAppendRetry: () -> Unit,
    actions: ExploreScreenActions,
) {
    PullToRefreshBox(
        isRefreshing = content.state.isRefreshing,
        onRefresh = actions.onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        ExploreListingsGrid(
            content = content,
            gridState = gridState,
            onAppendRetry = onAppendRetry,
            actions = actions,
        )
    }
}

@Composable
private fun ExploreListingsGrid(
    content: ExploreGridContent,
    gridState: LazyGridState,
    onAppendRetry: () -> Unit,
    actions: ExploreScreenActions,
) {
    val state = content.state
    val strings = content.strings
    val liveRegionStatus = exploreFeedLiveRegionStatus(state, strings)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .exploreLiveRegion(liveRegionStatus),
    ) {
        val columnCount = if (maxWidth < KwaborSizing.ExploreTabletBreakpoint) {
            KwaborSizing.EXPLORE_MOBILE_GRID_COLUMNS
        } else {
            KwaborSizing.EXPLORE_TABLET_GRID_COLUMNS
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(count = columnCount),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = KwaborSpacing.Lg,
                top = KwaborSpacing.Lg,
                end = KwaborSpacing.Lg,
                bottom = KwaborSpacing.Xxxl + KwaborSizing.BottomNavigationHeight,
            ),
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ExploreHeader(state, strings, content.isGuestSession, actions)
            }
            exploreGridItems(state = state, strings = strings, actions = actions)
            exploreAppendFooter(state = state, strings = strings, onRetry = onAppendRetry)
        }
    }
}

private fun LazyGridScope.exploreGridItems(
    state: ExploreUiState,
    strings: KwaborStrings,
    actions: ExploreScreenActions,
) {
    when {
        state.isLoading -> items(count = 4) {
            KwaborSkeletonCard(modifier = Modifier.clearAndSetSemantics {})
        }
        state.hasError -> stateMessageItem(strings.errorStateTitle, state.errorMessage, strings, actions.onRetry)
        state.isEmpty -> stateMessageItem(
            strings.emptyStateTitle,
            strings.exploreEmptyMessage,
            strings,
            actions.onRetry,
        )
        else -> items(items = state.listings, key = { item -> item.id }) { listing ->
            ListingCard(
                state = listing.toCardState(),
                strings = strings,
                priceOptions = PriceTagOptions(currency = state.currency, mode = PriceTagMode.Compact),
                actions = ListingCardActions(
                    onClick = { actions.onListingClick(listing.id) },
                    onLikeClick = { actions.onLikeClick(listing.id) },
                    onFavoriteClick = { actions.onFavoriteClick(listing.id) },
                ),
            )
        }
    }
}

private fun LazyGridScope.exploreAppendFooter(state: ExploreUiState, strings: KwaborStrings, onRetry: () -> Unit) {
    when {
        state.isAppending -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(KwaborSpacing.Lg),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = strings.loading },
                )
            }
        }
        state.appendErrorMessage != null -> stateMessageItem(
            title = strings.errorStateTitle,
            supportingText = state.appendErrorMessage,
            strings = strings,
            onRetry = onRetry,
        )
    }
}

private fun LazyGridScope.stateMessageItem(
    title: String,
    supportingText: String?,
    strings: KwaborStrings,
    onRetry: () -> Unit,
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        KwaborStateMessage(
            title = title,
            supportingText = supportingText,
            actionLabel = strings.retry,
            onAction = onRetry,
        )
    }
}

@Composable
private fun ExploreHeader(
    state: ExploreUiState,
    strings: KwaborStrings,
    isGuestSession: Boolean,
    actions: ExploreScreenActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        ExploreLocationRow(
            cityLabel = state.cityLabel,
            strings = strings,
            isGuestSession = isGuestSession,
            onClick = actions.onCityClick,
        )
        Text(text = strings.homeTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ExploreTabs(state.selectedTab, strings, actions.onTabSelected)
        ExploreSearchRow(strings, actions.onSearchClick, actions.onFilterClick)
        ExploreChips(state.chips, state.selectedChipId, actions.onChipSelected)
    }
}

@Composable
private fun ExploreLocationRow(
    cityLabel: String,
    strings: KwaborStrings,
    isGuestSession: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExploreCityControl(cityLabel = cityLabel, strings = strings, onClick = onClick, modifier = Modifier.weight(1f))
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(KwaborRadius.Control)) {
            Text(
                text = if (isGuestSession) strings.authGuestSession else strings.authConnectedSession,
                modifier = Modifier.padding(horizontal = KwaborSpacing.Sm, vertical = KwaborSpacing.Xs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExploreCityControl(cityLabel: String, strings: KwaborStrings, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = strings.location
                stateDescription = cityLabel
                role = Role.Button
            },
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Text(cityLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun ExploreTabs(selectedTab: ExploreTab, strings: KwaborStrings, onTabSelected: (ExploreTab) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm)) {
        items(ExploreTab.entries) { tab ->
            FilterChip(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                label = { Text(text = tab.label(strings)) },
            )
        }
    }
}

@Composable
private fun ExploreSearchRow(strings: KwaborStrings, onSearchClick: () -> Unit, onFilterClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExploreSearchSurface(strings, onSearchClick, Modifier.weight(1f))
        ExploreFilterButton(strings, onFilterClick)
    }
}

@Composable
private fun ExploreSearchSurface(strings: KwaborStrings, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = KwaborColors.Ink100,
        shape = RoundedCornerShape(KwaborRadius.Control),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = KwaborSpacing.Lg, vertical = KwaborSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Text(strings.searchPlaceholder, style = MaterialTheme.typography.bodyMedium, color = KwaborColors.Ink700)
        }
    }
}

@Composable
private fun ExploreFilterButton(strings: KwaborStrings, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(KwaborSizing.MinimumAccessibleTouchTarget)
            .background(color = KwaborColors.Ink950, shape = RoundedCornerShape(KwaborRadius.Control)),
    ) {
        Icon(Icons.Filled.Tune, contentDescription = strings.filter, tint = KwaborColors.Surface0)
    }
}

@Composable
private fun ExploreChips(chips: List<ExploreChip>, selectedChipId: String?, onChipSelected: (ExploreChip) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm)) {
        items(chips, key = { chip -> chip.id }) { chip ->
            FilterChip(
                selected = selectedChipId == chip.id,
                onClick = { onChipSelected(chip) },
                label = { Text(text = chip.label) },
            )
        }
    }
}

private fun ExploreListingItem.toCardState(): ListingCardState = ListingCardState(
    title = title,
    cityLabel = cityLabel,
    coverImageUrl = coverImageUrl,
    price = price,
    ratingLabel = ratingLabel,
    sponsored = sponsored,
    liked = liked,
    favorited = favorited,
)
