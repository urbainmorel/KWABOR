package com.kwabor.android.ui.screens.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.android.ui.components.KwaborInlineBanner
import com.kwabor.android.ui.components.KwaborSkeletonCard
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.android.ui.components.OfflineBanner
import com.kwabor.android.ui.components.PriceTag
import com.kwabor.android.ui.components.PriceTagMode
import com.kwabor.android.ui.components.PriceTagOptions
import com.kwabor.shared.i18n.GuideDiscoveryStrings
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.guide.GuideDiscoveryUiState
import com.kwabor.shared.presentation.guide.GuideFilterOptionUiModel
import com.kwabor.shared.presentation.guide.GuideSummaryUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuideDiscoveryScreen(
    state: GuideDiscoveryUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: GuideDiscoveryScreenActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(strings.guideDiscovery.title) },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onBack,
                        modifier = Modifier.size(KwaborSizing.MinimumAccessibleTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.registrationBack,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.isOffline) {
                OfflineBanner(strings = strings)
            }
            state.refreshMessage?.let { message -> KwaborInlineBanner(text = message) }
            GuideDiscoveryContent(
                state = state,
                strings = strings,
                mediaUrlPolicy = mediaUrlPolicy,
                actions = actions,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideDiscoveryContent(
    state: GuideDiscoveryUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: GuideDiscoveryScreenActions,
) {
    val gridState = rememberLazyGridState()
    val paginationGuard = remember(state.filters) { GuidePaginationGuard() }
    GuidePaginationEffect(
        state = state,
        gridState = gridState,
        guard = paginationGuard,
        onLoadNext = actions.onLoadNext,
    )
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = actions.onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        GuideDiscoveryGrid(
            content = GuideDiscoveryGridContent(state, strings, mediaUrlPolicy),
            gridState = gridState,
            actions = actions,
            onAppendRetry = {
                paginationGuard.requestedCursor = state.nextCursor
                actions.onLoadNext()
            },
        )
    }
}

private class GuidePaginationGuard(var requestedCursor: String? = null)

private data class GuideDiscoveryGridContent(
    val state: GuideDiscoveryUiState,
    val strings: KwaborStrings,
    val mediaUrlPolicy: ListingMediaUrlPolicy,
)

@Composable
private fun GuidePaginationEffect(
    state: GuideDiscoveryUiState,
    gridState: LazyGridState,
    guard: GuidePaginationGuard,
    onLoadNext: () -> Unit,
) {
    val reachedPaginationThreshold by remember(gridState) {
        derivedStateOf { gridState.hasReachedGuidePaginationThreshold() }
    }
    LaunchedEffect(
        reachedPaginationThreshold,
        state.nextCursor,
        state.canLoadMore,
        state.appendErrorMessage,
    ) {
        val cursor = state.nextCursor
        if (shouldRequestNextPage(reachedPaginationThreshold, cursor, guard.requestedCursor, state)) {
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

private fun shouldRequestNextPage(
    reachedThreshold: Boolean,
    cursor: String?,
    requestedCursor: String?,
    state: GuideDiscoveryUiState,
): Boolean {
    if (!reachedThreshold || cursor == null || cursor == requestedCursor) return false
    return state.canLoadMore && state.appendErrorMessage == null
}

@Composable
private fun GuideDiscoveryGrid(
    content: GuideDiscoveryGridContent,
    gridState: LazyGridState,
    actions: GuideDiscoveryScreenActions,
    onAppendRetry: () -> Unit,
) {
    val liveRegionStatus = guideDiscoveryLiveRegionStatus(content.state, content.strings)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .guideDiscoveryLiveRegion(liveRegionStatus),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(guideDiscoveryColumnCount(maxWidth)),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(KwaborSpacing.Lg),
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Lg),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GuideDiscoveryFilters(
                    state = content.state,
                    strings = content.strings.guideDiscovery,
                    actions = actions,
                )
            }
            guideItems(
                state = content.state,
                strings = content.strings,
                mediaUrlPolicy = content.mediaUrlPolicy,
                actions = actions,
            )
            guideAppendFooter(
                state = content.state,
                strings = content.strings,
                onRetry = onAppendRetry,
            )
        }
    }
}

@Composable
private fun GuideDiscoveryFilters(
    state: GuideDiscoveryUiState,
    strings: GuideDiscoveryStrings,
    actions: GuideDiscoveryScreenActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        GuideFiltersHeader(
            strings = strings,
            hasActiveFilters = state.hasActiveFilters,
            onReset = actions.onResetFilters,
        )
        GuideFilterSelections(state = state, strings = strings, actions = actions)
        GuideResultCount(state.resultCountLabel)
    }
}

@Composable
private fun GuideFiltersHeader(strings: GuideDiscoveryStrings, hasActiveFilters: Boolean, onReset: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = strings.filtersTitle,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (hasActiveFilters) {
            TextButton(onClick = onReset) { Text(strings.resetFilters) }
        }
    }
}

@Composable
private fun GuideFilterSelections(
    state: GuideDiscoveryUiState,
    strings: GuideDiscoveryStrings,
    actions: GuideDiscoveryScreenActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md)) {
        GuideFilterRow(
            label = strings.cityFilter,
            allLabel = strings.allCities,
            selectedId = state.filters.cityId,
            options = state.cityOptions,
            onSelected = actions.onCitySelected,
        )
        GuideFilterRow(
            label = strings.languageFilter,
            allLabel = strings.allLanguages,
            selectedId = state.filters.languageId,
            options = state.languageOptions,
            onSelected = actions.onLanguageSelected,
        )
        GuideFilterRow(
            label = strings.specialtyFilter,
            allLabel = strings.allSpecialties,
            selectedId = state.filters.specialtyId,
            options = state.specialtyOptions,
            onSelected = actions.onSpecialtySelected,
        )
    }
}

@Composable
private fun GuideResultCount(label: String) {
    if (label.isBlank()) return
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun GuideFilterRow(
    label: String,
    allLabel: String,
    selectedId: String?,
    options: List<GuideFilterOptionUiModel>,
    onSelected: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm)) {
            item {
                FilterChip(
                    selected = selectedId == null,
                    onClick = { onSelected(null) },
                    label = { Text(allLabel) },
                )
            }
            items(items = options, key = GuideFilterOptionUiModel::id) { option ->
                FilterChip(
                    selected = selectedId == option.id,
                    onClick = { onSelected(option.id) },
                    label = { Text(option.label) },
                )
            }
        }
    }
}

private fun LazyGridScope.guideItems(
    state: GuideDiscoveryUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: GuideDiscoveryScreenActions,
) {
    when {
        state.isLoading -> items(count = GUIDE_LOADING_CARD_COUNT) {
            KwaborSkeletonCard(modifier = Modifier.clearAndSetSemantics {})
        }
        state.errorMessage != null -> guideStateMessage(
            title = strings.errorStateTitle,
            supportingText = state.errorMessage,
            actionLabel = strings.retry,
            onAction = actions.onRetry,
        )
        state.isEmpty -> guideStateMessage(
            title = strings.guideDiscovery.emptyTitle,
            supportingText = strings.guideDiscovery.emptyMessage,
            actionLabel = if (state.hasActiveFilters) strings.guideDiscovery.resetFilters else strings.retry,
            onAction = if (state.hasActiveFilters) actions.onResetFilters else actions.onRetry,
        )
        else -> items(items = state.guides, key = GuideSummaryUiModel::id) { guide ->
            GuideCard(
                model = guide,
                strings = strings,
                mediaUrlPolicy = mediaUrlPolicy,
                onClick = { actions.onGuideClick(guide.id) },
            )
        }
    }
}

private fun LazyGridScope.guideAppendFooter(
    state: GuideDiscoveryUiState,
    strings: KwaborStrings,
    onRetry: () -> Unit,
) {
    when {
        state.isAppending -> item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(KwaborSpacing.Lg),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.clearAndSetSemantics { contentDescription = strings.loading },
                )
            }
        }
        state.appendErrorMessage != null -> guideStateMessage(
            title = strings.errorStateTitle,
            supportingText = state.appendErrorMessage,
            actionLabel = strings.retry,
            onAction = onRetry,
        )
    }
}

private fun LazyGridScope.guideStateMessage(
    title: String,
    supportingText: String?,
    actionLabel: String,
    onAction: () -> Unit,
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

@Composable
private fun GuideCard(
    model: GuideSummaryUiModel,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accessibilityDescription = remember(model, strings) {
        guideCardAccessibilityDescription(model, strings)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KwaborRadius.Card))
            .clickable(
                role = Role.Button,
                onClickLabel = strings.guideDiscovery.openGuideLabel,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = accessibilityDescription
                onClick(label = strings.guideDiscovery.openGuideLabel) {
                    onClick()
                    true
                }
            },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(KwaborRadius.Card),
    ) {
        Column {
            GuideCardHero(model = model, strings = strings, mediaUrlPolicy = mediaUrlPolicy)
            GuideCardDetails(model = model, strings = strings)
        }
    }
}

@Composable
private fun GuideCardDetails(model: GuideSummaryUiModel, strings: KwaborStrings) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(KwaborSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        model.ratingLabel?.let { rating -> GuideRating(model = model, rating = rating, strings = strings) }
        GuideAttribute(strings.guideDiscovery.languagesLabel, model.languages)
        GuideAttribute(strings.guideDiscovery.coveredCitiesLabel, model.coverageCities)
        GuideAttribute(strings.guideDiscovery.specialtiesLabel, model.specialties)
        Column(verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs)) {
            Text(
                text = strings.guideDiscovery.indicativePriceLabel,
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium,
            )
            PriceTag(
                price = model.indicativePrice,
                strings = strings,
                options = PriceTagOptions(mode = PriceTagMode.Full),
            )
        }
    }
}

@Composable
private fun GuideRating(model: GuideSummaryUiModel, rating: String, strings: KwaborStrings) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = strings.detail.rating,
            modifier = Modifier.size(KwaborSpacing.Xl),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(rating, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(strings.detail.ratingOutOfFive, style = MaterialTheme.typography.labelMedium)
        Text(model.ratingCount.toString(), style = MaterialTheme.typography.labelMedium)
        Text(
            text = if (model.ratingCount == 1) strings.detail.review else strings.detail.reviews,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun GuideAttribute(label: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.labelMedium,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm)) {
            items(values) { value ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(KwaborRadius.Pill),
                ) {
                    Text(
                        text = value,
                        modifier = Modifier.padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Xs),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private const val GUIDE_LOADING_CARD_COUNT = 3
