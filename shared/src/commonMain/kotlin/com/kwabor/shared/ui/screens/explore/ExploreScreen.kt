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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
import com.kwabor.shared.ui.components.KwaborInlineBanner
import com.kwabor.shared.ui.components.KwaborSkeletonCard
import com.kwabor.shared.ui.components.KwaborStateMessage
import com.kwabor.shared.ui.components.ListingCard
import com.kwabor.shared.ui.components.ListingCardActions
import com.kwabor.shared.ui.components.ListingCardState
import com.kwabor.shared.ui.components.OfflineBanner
import com.kwabor.shared.ui.components.PriceTagMode
import com.kwabor.shared.ui.components.PriceTagOptions

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
        item(span = { GridItemSpan(maxLineSpan) }) {
            ExploreHeader(
                state = state,
                strings = strings,
                isGuestSession = isGuestSession,
                actions = actions,
            )
        }
        exploreGridItems(state = state, strings = strings, actions = actions)
    }
}

private fun LazyGridScope.exploreGridItems(
    state: ExploreUiState,
    strings: KwaborStrings,
    actions: ExploreScreenActions,
) {
    when {
        state.isLoading -> items(count = 4) { KwaborSkeletonCard() }
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
        ExploreLocationRow(state.cityLabel, strings, isGuestSession)
        Text(text = strings.homeTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ExploreTabs(state.selectedTab, strings, actions.onTabSelected)
        ExploreSearchRow(strings, actions.onSearchClick, actions.onFilterClick)
        ExploreChips(state.chips, state.selectedChipId, actions.onChipSelected)
    }
}

@Composable
private fun ExploreLocationRow(cityLabel: String, strings: KwaborStrings, isGuestSession: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LocationOn, strings.location, tint = MaterialTheme.colorScheme.secondary)
        Text(cityLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
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
        modifier = Modifier.size(KwaborSizing.TouchTarget)
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
