package com.kwabor.shared.ui.screens.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.shared.design.KwaborColors
import com.kwabor.shared.design.KwaborRadius
import com.kwabor.shared.design.KwaborSizing
import com.kwabor.shared.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.explore.ExploreChip
import com.kwabor.shared.presentation.explore.ExploreListingItem
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.label
import com.kwabor.shared.ui.components.KwaborSkeletonCard
import com.kwabor.shared.ui.components.KwaborStateMessage
import com.kwabor.shared.ui.components.ListingCard
import com.kwabor.shared.ui.components.ListingCardState
import com.kwabor.shared.ui.components.OfflineBanner

@Composable
fun ExploreScreen(
    state: ExploreUiState,
    strings: KwaborStrings,
    modifier: Modifier = Modifier,
    onTabSelected: (ExploreTab) -> Unit = {},
    onChipSelected: (ExploreChip) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onFilterClick: () -> Unit = {},
    onListingClick: (String) -> Unit = {},
    onLikeClick: (String) -> Unit = {},
    onFavoriteClick: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onAssistantClick: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.isOffline) {
                OfflineBanner(strings = strings)
            }
            ExploreContent(
                state = state,
                strings = strings,
                onTabSelected = onTabSelected,
                onChipSelected = onChipSelected,
                onSearchClick = onSearchClick,
                onFilterClick = onFilterClick,
                onListingClick = onListingClick,
                onLikeClick = onLikeClick,
                onFavoriteClick = onFavoriteClick,
                onRetry = onRetry,
            )
        }

        FloatingActionButton(
            onClick = onAssistantClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = KwaborSpacing.Lg, bottom = KwaborSpacing.Xxl)
                .size(KwaborSizing.FloatingActionButton),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = strings.aiAssistant,
            )
        }
    }
}

@Composable
private fun ExploreContent(
    state: ExploreUiState,
    strings: KwaborStrings,
    onTabSelected: (ExploreTab) -> Unit,
    onChipSelected: (ExploreChip) -> Unit,
    onSearchClick: () -> Unit,
    onFilterClick: () -> Unit,
    onListingClick: (String) -> Unit,
    onLikeClick: (String) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(count = 2),
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
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            ExploreHeader(
                state = state,
                strings = strings,
                onTabSelected = onTabSelected,
                onChipSelected = onChipSelected,
                onSearchClick = onSearchClick,
                onFilterClick = onFilterClick,
            )
        }

        when {
            state.isLoading -> items(count = 4) {
                KwaborSkeletonCard()
            }
            state.hasError -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                KwaborStateMessage(
                    title = strings.errorStateTitle,
                    supportingText = state.errorMessage,
                    actionLabel = strings.retry,
                    onAction = onRetry,
                )
            }
            state.isEmpty -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                KwaborStateMessage(
                    title = strings.emptyStateTitle,
                    supportingText = strings.exploreEmptyMessage,
                    actionLabel = strings.retry,
                    onAction = onRetry,
                )
            }
            else -> items(items = state.listings, key = { item -> item.id }) { listing ->
                ListingCard(
                    state = listing.toCardState(),
                    strings = strings,
                    currency = state.currency,
                    onClick = { onListingClick(listing.id) },
                    onLikeClick = { onLikeClick(listing.id) },
                    onFavoriteClick = { onFavoriteClick(listing.id) },
                )
            }
        }
    }
}

@Composable
private fun ExploreHeader(
    state: ExploreUiState,
    strings: KwaborStrings,
    onTabSelected: (ExploreTab) -> Unit,
    onChipSelected: (ExploreChip) -> Unit,
    onSearchClick: () -> Unit,
    onFilterClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOn,
                contentDescription = strings.location,
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = state.cityLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }

        Text(
            text = strings.homeTitle,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        ExploreTabs(
            selectedTab = state.selectedTab,
            strings = strings,
            onTabSelected = onTabSelected,
        )

        ExploreSearchRow(
            strings = strings,
            onSearchClick = onSearchClick,
            onFilterClick = onFilterClick,
        )

        ExploreChips(
            chips = state.chips,
            selectedChipId = state.selectedChipId,
            onChipSelected = onChipSelected,
        )
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
        Surface(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSearchClick),
            color = KwaborColors.Ink100,
            shape = RoundedCornerShape(KwaborRadius.Control),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = KwaborSpacing.Lg, vertical = KwaborSpacing.Md),
                horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                Text(
                    text = strings.searchPlaceholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KwaborColors.Ink700,
                )
            }
        }

        IconButton(
            onClick = onFilterClick,
            modifier = Modifier
                .size(KwaborSizing.TouchTarget)
                .background(
                    color = KwaborColors.Ink950,
                    shape = RoundedCornerShape(KwaborRadius.Control),
                ),
        ) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = strings.filter,
                tint = KwaborColors.Surface0,
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
