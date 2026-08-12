package com.kwabor.android.ui.screens.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.android.ui.components.KwaborInlineBanner
import com.kwabor.android.ui.components.KwaborSkeletonCard
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.android.ui.components.ListingCard
import com.kwabor.android.ui.components.ListingCardActions
import com.kwabor.android.ui.components.OfflineBanner
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.favorites.FavoritesFilter
import com.kwabor.shared.presentation.favorites.FavoritesUiState
import com.kwabor.shared.presentation.favorites.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesScreen(
    state: FavoritesUiState,
    resources: FavoritesScreenResources,
    actions: FavoritesScreenActions,
    modifier: Modifier = Modifier,
    showClosedBetaDemoDisclosure: Boolean = false,
) {
    FavoritesScaffold(
        state = state,
        resources = resources,
        actions = actions,
        modifier = modifier,
        showClosedBetaDemoDisclosure = showClosedBetaDemoDisclosure,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesScaffold(
    state: FavoritesUiState,
    resources: FavoritesScreenResources,
    actions: FavoritesScreenActions,
    modifier: Modifier,
    showClosedBetaDemoDisclosure: Boolean,
) {
    val strings = resources.strings
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            FavoritesTopAppBar(strings = strings, onBack = actions.onBack)
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (showClosedBetaDemoDisclosure) {
                KwaborInlineBanner(text = strings.closedBetaDemoDisclosure)
            }
            if (state.isOffline) {
                OfflineBanner(strings = strings)
            }
            state.refreshMessage?.let { message -> KwaborInlineBanner(text = message) }
            state.mutationMessage?.let { message -> KwaborInlineBanner(text = message) }
            FavoritesContent(
                state = state,
                strings = strings,
                mediaUrlPolicy = resources.mediaUrlPolicy,
                actions = actions,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesTopAppBar(strings: KwaborStrings, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(strings.favorites.title) },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(KwaborSizing.MinimumAccessibleTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = strings.registrationBack,
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesContent(
    state: FavoritesUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: FavoritesScreenActions,
) {
    val gridState = rememberLazyGridState()
    val paginationGuard = remember(state.selectedFilter) { FavoritesPaginationGuard() }
    FavoritesPaginationEffect(state, gridState, paginationGuard, actions.onLoadNext)
    LaunchedEffect(state.selectedFilter) {
        gridState.scrollToItem(index = 0)
    }
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = actions.onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        FavoritesGrid(
            state = state,
            strings = strings,
            mediaUrlPolicy = mediaUrlPolicy,
            actions = actions,
            paging = FavoritesGridPaging(
                gridState = gridState,
                onAppendRetry = {
                    paginationGuard.requestedCursor = state.nextCursor
                    actions.onLoadNext()
                },
            ),
        )
    }
}

private class FavoritesPaginationGuard(var requestedCursor: String? = null)

private class FavoritesGridPaging(
    val gridState: LazyGridState,
    val onAppendRetry: () -> Unit,
)

@Composable
private fun FavoritesPaginationEffect(
    state: FavoritesUiState,
    gridState: LazyGridState,
    guard: FavoritesPaginationGuard,
    onLoadNext: () -> Unit,
) {
    val reachedPaginationThreshold by remember(gridState) {
        derivedStateOf { gridState.hasReachedFavoritesPaginationThreshold() }
    }
    LaunchedEffect(
        reachedPaginationThreshold,
        state.nextCursor,
        state.canLoadMore,
        state.appendErrorMessage,
    ) {
        val cursor = state.nextCursor
        if (shouldRequestNextFavoritesPage(reachedPaginationThreshold, cursor, guard.requestedCursor, state)) {
            guard.requestedCursor = cursor
            onLoadNext()
        }
    }
    LaunchedEffect(state.isRefreshing) {
        if (state.isRefreshing) guard.requestedCursor = null
    }
}

@Composable
private fun FavoritesGrid(
    state: FavoritesUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: FavoritesScreenActions,
    paging: FavoritesGridPaging,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .favoritesLiveRegion(favoritesLiveRegionStatus(state, strings)),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(favoritesColumnCount(maxWidth, fontScale)),
            state = paging.gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(KwaborSpacing.Lg),
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FavoritesFilters(state = state, strings = strings, actions = actions)
            }
            favoritesItems(state, strings, mediaUrlPolicy, actions)
            favoritesAppendFooter(state, strings, paging.onAppendRetry)
        }
    }
}

@Composable
private fun FavoritesFilters(state: FavoritesUiState, strings: KwaborStrings, actions: FavoritesScreenActions) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
    ) {
        items(items = FavoritesFilter.entries, key = FavoritesFilter::name) { filter ->
            FilterChip(
                selected = filter == state.selectedFilter,
                onClick = { actions.onFilterSelected(filter) },
                label = { Text(filter.label(strings.favorites)) },
                modifier = Modifier.heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget),
            )
        }
    }
}

private fun LazyGridScope.favoritesItems(
    state: FavoritesUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: FavoritesScreenActions,
) {
    when {
        !state.isAccountReady || state.isLoading -> items(count = LOADING_CARD_COUNT) {
            KwaborSkeletonCard(modifier = Modifier.clearAndSetSemantics {})
        }
        state.errorMessage != null -> stateMessageItem(
            title = strings.errorStateTitle,
            supportingText = state.errorMessage,
            actionLabel = strings.retry,
            onAction = actions.onRetry,
        )
        state.isEmpty -> stateMessageItem(
            title = strings.favorites.emptyTitle,
            supportingText = strings.favorites.emptyMessage,
            actionLabel = null,
            onAction = null,
        )
        else -> items(items = state.items, key = { item -> item.id }) { item ->
            val isRemoving = item.id in state.removingListingIds
            ListingCard(
                state = item.toFavoriteCardState(),
                strings = strings,
                mediaUrlPolicy = mediaUrlPolicy,
                actions = ListingCardActions(
                    onClick = { actions.onOpenListing(item.id) },
                    onFavoriteClick = { actions.onRemoveFavorite(item.id) },
                    favoriteLabel = strings.favorites.removeFavorite,
                    favoriteEnabled = !isRemoving,
                    favoriteInProgress = isRemoving,
                    openAccessibilityDescription = item.favoriteCardAccessibilityDescription(strings),
                ),
            )
        }
    }
}

private fun LazyGridScope.favoritesAppendFooter(state: FavoritesUiState, strings: KwaborStrings, onRetry: () -> Unit) {
    when {
        state.isAppending -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(KwaborSpacing.Lg),
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
            actionLabel = strings.retry,
            onAction = onRetry,
        )
    }
}

private fun LazyGridScope.stateMessageItem(
    title: String,
    supportingText: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        KwaborStateMessage(
            title = title,
            supportingText = supportingText,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}

private const val LOADING_CARD_COUNT = 4
