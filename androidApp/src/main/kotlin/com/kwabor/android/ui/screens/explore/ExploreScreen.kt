package com.kwabor.android.ui.screens.explore

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.media.ListingMediaUrlPolicy
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
import com.kwabor.shared.presentation.search.SearchScope
import com.kwabor.shared.presentation.search.SearchUiState

@Composable
fun ExploreScreen(
    model: ExploreScreenUiModel,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    modifier: Modifier = Modifier,
    actions: ExploreScreenActions,
) {
    val state = model.state
    val searchState = model.searchState
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = searchState.isActive) {
        focusManager.clearFocus()
        actions.onSearchClose()
    }
    Column(modifier = modifier.fillMaxSize()) {
        if (model.showClosedBetaDemoDisclosure) {
            KwaborInlineBanner(text = strings.closedBetaDemoDisclosure)
        }
        if (if (searchState.isActive) searchState.isOffline else state.isOffline) {
            OfflineBanner(strings = strings)
        }
        state.interactionMessage?.let { message ->
            KwaborInlineBanner(text = message)
        }
        (if (searchState.isActive) searchState.refreshMessage else state.refreshMessage)?.let { message ->
            KwaborInlineBanner(text = message)
        }
        state.locationMessage
            ?.takeUnless { state.isCitySelectorOpen }
            ?.let { message -> KwaborInlineBanner(text = message) }
        ExploreContent(
            model = model,
            strings = strings,
            mediaUrlPolicy = mediaUrlPolicy,
            actions = actions,
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

data class ExploreScreenUiModel(
    val state: ExploreUiState,
    val searchState: SearchUiState = SearchUiState(),
    val isGuestSession: Boolean = true,
    val showClosedBetaDemoDisclosure: Boolean = false,
    val showGuideDiscoveryEntry: Boolean = true,
)

@Composable
private fun ExploreContent(
    model: ExploreScreenUiModel,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: ExploreScreenActions,
) {
    val state = model.state
    val searchState = model.searchState
    val exploreGridState = rememberLazyGridState()
    val searchGridState = rememberLazyGridState()
    val gridState = if (searchState.isActive) searchGridState else exploreGridState
    val gridContent = ExploreGridContent(
        state = state,
        searchState = searchState,
        strings = strings,
        mediaUrlPolicy = mediaUrlPolicy,
        isGuestSession = model.isGuestSession,
        showGuideDiscoveryEntry = model.showGuideDiscoveryEntry,
        actions = actions,
    )
    val explorePaginationGuard = remember(state.selectedTab, state.selectedChipId, state.selectedCityId) {
        ExplorePaginationGuard()
    }
    val searchPaginationGuard = remember { SearchPaginationGuard() }
    ExploreSurfacePaginationEffect(
        content = gridContent,
        gridState = gridState,
        exploreGuard = explorePaginationGuard,
        searchGuard = searchPaginationGuard,
    )
    ExploreRefreshableGrid(
        content = gridContent,
        gridState = gridState,
        onAppendRetry = {
            retryExploreSurfaceAppend(gridContent, explorePaginationGuard, searchPaginationGuard)
        },
        isRefreshing = if (searchState.isActive) searchState.isRefreshing else state.isRefreshing,
        onRefresh = if (searchState.isActive) actions.onSearchRefresh else actions.onRefresh,
    )
}

private data class ExploreGridContent(
    val state: ExploreUiState,
    val searchState: SearchUiState,
    val strings: KwaborStrings,
    val mediaUrlPolicy: ListingMediaUrlPolicy,
    val isGuestSession: Boolean,
    val showGuideDiscoveryEntry: Boolean,
    val actions: ExploreScreenActions,
)

private class ExplorePaginationGuard(var requestedCursor: String? = null)

private fun retryExploreSurfaceAppend(
    content: ExploreGridContent,
    exploreGuard: ExplorePaginationGuard,
    searchGuard: SearchPaginationGuard,
) {
    if (content.searchState.isActive) {
        searchGuard.requestedCursor = content.searchState.nextCursor
        content.actions.onSearchLoadNext()
    } else {
        exploreGuard.requestedCursor = content.state.nextCursor
        content.actions.onLoadNext()
    }
}

@Composable
private fun ExploreSurfacePaginationEffect(
    content: ExploreGridContent,
    gridState: LazyGridState,
    exploreGuard: ExplorePaginationGuard,
    searchGuard: SearchPaginationGuard,
) {
    if (content.searchState.isActive) {
        SearchPaginationEffect(
            state = content.searchState,
            gridState = gridState,
            guard = searchGuard,
            onLoadNext = content.actions.onSearchLoadNext,
        )
    } else {
        ExplorePaginationEffect(
            state = content.state,
            gridState = gridState,
            guard = exploreGuard,
            onLoadNext = content.actions.onLoadNext,
        )
    }
}

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
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        ExploreListingsGrid(
            content = content,
            gridState = gridState,
            onAppendRetry = onAppendRetry,
        )
    }
}

@Composable
private fun ExploreListingsGrid(content: ExploreGridContent, gridState: LazyGridState, onAppendRetry: () -> Unit) {
    val state = content.state
    val searchState = content.searchState
    val strings = content.strings
    val liveRegionStatus = if (searchState.isActive) {
        searchLiveRegionStatus(searchState, strings)
    } else {
        exploreFeedLiveRegionStatus(state, strings)
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .exploreLiveRegion(liveRegionStatus),
    ) {
        val columnCount = exploreColumnCount(
            maxWidth = maxWidth,
            fontScale = LocalDensity.current.fontScale,
        )
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
            exploreSurfaceItems(content = content, onAppendRetry = onAppendRetry)
        }
    }
}

private fun LazyGridScope.exploreSurfaceItems(content: ExploreGridContent, onAppendRetry: () -> Unit) {
    val state = content.state
    val searchState = content.searchState
    val strings = content.strings
    val actions = content.actions
    item(span = { GridItemSpan(maxLineSpan) }) {
        ExploreHeader(state, searchState, strings, content.isGuestSession, actions)
    }
    if (!searchState.isActive && content.showGuideDiscoveryEntry) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ExploreGuideDiscoveryEntry(strings = strings, onClick = actions.onGuideDiscoveryClick)
        }
    }
    if (searchState.isActive) {
        searchGridItems(
            state = searchState,
            strings = strings,
            mediaUrlPolicy = content.mediaUrlPolicy,
            actions = actions,
        )
    } else {
        exploreGridItems(
            state = state,
            strings = strings,
            mediaUrlPolicy = content.mediaUrlPolicy,
            actions = actions,
        )
        exploreAppendFooter(state = state, strings = strings, onRetry = onAppendRetry)
    }
}

@Composable
private fun ExploreGuideDiscoveryEntry(strings: KwaborStrings, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(KwaborRadius.Control),
    ) {
        Row(
            modifier = Modifier.padding(KwaborSpacing.Lg),
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PersonSearch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
            ) {
                Text(
                    text = strings.guideDiscovery.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = strings.guideDiscovery.entrySubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun exploreColumnCount(maxWidth: Dp, fontScale: Float): Int {
    if (!fontScale.isFinite() || fontScale <= 0f || fontScale >= ACCESSIBILITY_FONT_SCALE_THRESHOLD) {
        return 1
    }
    return if (maxWidth < KwaborSizing.ExploreTabletBreakpoint) {
        KwaborSizing.EXPLORE_MOBILE_GRID_COLUMNS
    } else {
        KwaborSizing.EXPLORE_TABLET_GRID_COLUMNS
    }
}

private fun LazyGridScope.exploreGridItems(
    state: ExploreUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
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
                state = listing.toCardState(
                    priceOptions = PriceTagOptions(currency = state.currency, mode = PriceTagMode.Compact),
                ),
                strings = strings,
                mediaUrlPolicy = mediaUrlPolicy,
                actions = ListingCardActions(
                    onClick = { actions.onListingClick(listing.id) },
                    onLikeClick = { actions.onLikeClick(listing.id) },
                    onFavoriteClick = { actions.onFavoriteClick(listing.id) },
                    openAccessibilityDescription = listing.cardAccessibilityDescription(strings),
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
    searchState: SearchUiState,
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
        ExploreSearchControl(state = searchState, strings = strings, actions = actions)
        if (!searchState.isActive || searchState.scope == SearchScope.ActiveTab) {
            ExploreChips(state.chips, state.selectedChipId, actions.onChipSelected)
        }
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

internal fun ExploreListingItem.toCardState(priceOptions: PriceTagOptions): ListingCardState = ListingCardState(
    title = title,
    cityLabel = cityLabel,
    coverImageUrl = coverImageUrl,
    coverImageAlt = coverImageAlt,
    price = price,
    priceOptions = priceOptions,
    ratingLabel = ratingLabel,
    eventDateLabel = eventDateLabel,
    sponsored = sponsored,
    liked = liked,
    favorited = favorited,
    eventEnded = isEventEnded,
)

private const val ACCESSIBILITY_FONT_SCALE_THRESHOLD = 1.3f
