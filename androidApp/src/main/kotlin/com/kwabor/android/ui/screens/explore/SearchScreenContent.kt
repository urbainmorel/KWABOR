package com.kwabor.android.ui.screens.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.android.ui.components.KwaborSkeletonCard
import com.kwabor.android.ui.components.KwaborStateMessage
import com.kwabor.android.ui.components.ListingCard
import com.kwabor.android.ui.components.ListingCardActions
import com.kwabor.android.ui.components.PriceTagMode
import com.kwabor.android.ui.components.PriceTagOptions
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.SearchStrings
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.search.SearchScope
import com.kwabor.shared.presentation.search.SearchUiState

internal const val SEARCH_FIELD_TEST_TAG = "explore-search-field"

@Composable
internal fun ExploreSearchControl(state: SearchUiState, strings: KwaborStrings, actions: ExploreScreenActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
    ) {
        SearchInputField(state = state, strings = strings, actions = actions)
        state.queryErrorMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = KwaborSpacing.Md),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.isActive) {
            SearchScopeSelector(
                selectedScope = state.scope,
                strings = strings,
                onSelected = actions.onSearchScopeSelected,
            )
        }
    }
}

@Composable
private fun SearchInputField(state: SearchUiState, strings: KwaborStrings, actions: ExploreScreenActions) {
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val placeholder = state.searchPlaceholder(strings.search)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    BasicTextField(
        value = state.queryText,
        onValueChange = actions.onSearchQueryChanged,
        modifier = Modifier.fillMaxWidth().searchInputSemantics(
            state = state,
            placeholder = placeholder,
            onActivate = actions.onSearchActivate,
        ),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        interactionSource = interactions,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                focusManager.clearFocus()
                actions.onSearchSubmit()
            },
        ),
        decorationBox = { input ->
            SearchInputDecoration(state, placeholder, focused, input) {
                SearchTrailingAction(state, strings, actions)
            }
        },
    )
}

@Composable
private fun SearchInputDecoration(
    state: SearchUiState,
    placeholder: String,
    focused: Boolean,
    input: @Composable () -> Unit,
    trailingAction: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = when {
        state.queryErrorMessage != null -> colors.error
        focused -> colors.primary
        else -> colors.outlineVariant
    }
    val showsTrailingAction = state.isActive || state.queryText.isNotEmpty()
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surface)
            .border(KwaborSizing.Hairline, borderColor, CircleShape)
            .padding(start = KwaborSpacing.Md, end = if (showsTrailingAction) KwaborSpacing.Xs else KwaborSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(KwaborSpacing.Xl),
            tint = colors.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(1f).padding(vertical = KwaborSpacing.Xs)) {
            if (state.queryText.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            input()
        }
        if (showsTrailingAction) trailingAction()
    }
}

internal fun SearchUiState.searchPlaceholder(strings: SearchStrings): String {
    if (isActive && scope == SearchScope.All) return strings.placeholder
    return when (context.selectedTab) {
        ExploreTab.Places -> strings.placePlaceholder
        ExploreTab.Events -> strings.eventPlaceholder
        ExploreTab.HotelsRestaurants -> when (context.selectedChipId) {
            "commercial-hotel" -> strings.hotelPlaceholder
            "commercial-restaurant" -> strings.restaurantPlaceholder
            "guide-touristique" -> strings.guidePlaceholder
            else -> strings.establishmentPlaceholder
        }
    }
}

private fun Modifier.searchInputSemantics(
    state: SearchUiState,
    placeholder: String,
    onActivate: () -> Unit,
): Modifier = this
    .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
    .onFocusChanged { focusState ->
        if (focusState.isFocused && !state.isActive) {
            onActivate()
        }
    }
    .semantics {
        contentDescription = placeholder
        state.queryErrorMessage?.let { error(it) }
    }
    .testTag(SEARCH_FIELD_TEST_TAG)

@Composable
private fun SearchTrailingAction(state: SearchUiState, strings: KwaborStrings, actions: ExploreScreenActions) {
    val focusManager = LocalFocusManager.current
    val hasQuery = state.queryText.isNotEmpty()
    IconButton(
        onClick = {
            if (hasQuery) {
                actions.onSearchClear()
            } else {
                focusManager.clearFocus()
                actions.onSearchClose()
            }
        },
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = if (hasQuery) strings.search.clear else strings.search.close,
        )
    }
}

@Composable
private fun SearchScopeSelector(
    selectedScope: SearchScope,
    strings: KwaborStrings,
    onSelected: (SearchScope) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm)) {
        items(SearchScope.entries) { scope ->
            FilterChip(
                selected = selectedScope == scope,
                onClick = { onSelected(scope) },
                shape = CircleShape,
                label = {
                    Text(
                        when (scope) {
                            SearchScope.ActiveTab -> strings.search.activeTabScope
                            SearchScope.All -> strings.search.allScope
                        },
                    )
                },
            )
        }
    }
}

@Composable
internal fun SearchPaginationEffect(
    state: SearchUiState,
    gridState: LazyGridState,
    guard: SearchPaginationGuard,
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
        if (
            guard.shouldRequest(
                cursor = state.nextCursor,
                canLoadMore = state.canLoadMore,
                reachedPaginationThreshold = reachedPaginationThreshold,
                hasAppendError = state.appendErrorMessage != null,
            )
        ) {
            onLoadNext()
        }
    }
    LaunchedEffect(state.submittedQueryText, state.scope, state.context) {
        guard.reset()
    }
    LaunchedEffect(state.isRefreshing) {
        if (state.isRefreshing) {
            guard.reset()
        }
    }
}

internal class SearchPaginationGuard(var requestedCursor: String? = null) {
    fun shouldRequest(
        cursor: String?,
        canLoadMore: Boolean,
        reachedPaginationThreshold: Boolean,
        hasAppendError: Boolean,
    ): Boolean {
        cursor ?: return false
        if (!canLoadMore) return false
        if (!reachedPaginationThreshold) return false
        if (hasAppendError) return false
        if (requestedCursor == cursor) return false
        requestedCursor = cursor
        return true
    }

    fun reset() {
        requestedCursor = null
    }
}

internal fun LazyGridScope.searchGridItems(
    state: SearchUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: ExploreScreenActions,
) {
    searchPrimaryItems(
        state = state,
        strings = strings,
        mediaUrlPolicy = mediaUrlPolicy,
        actions = actions,
    )
    searchAppendItem(state = state, strings = strings, actions = actions)
}

private fun LazyGridScope.searchPrimaryItems(
    state: SearchUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: ExploreScreenActions,
) {
    when {
        state.isLoading -> items(count = 4) {
            KwaborSkeletonCard(modifier = Modifier.clearAndSetSemantics {})
        }
        state.hasError -> searchStateMessage(
            title = strings.errorStateTitle,
            supportingText = state.errorMessage,
            actionLabel = strings.retry,
            onAction = actions.onSearchRetry,
        )
        state.suggestions.isNotEmpty() -> items(
            items = state.suggestions,
            key = { it.id },
            span = { GridItemSpan(maxLineSpan) },
        ) { suggestion ->
            val focusManager = LocalFocusManager.current
            ListItem(
                headlineContent = { Text(suggestion.title) },
                supportingContent = suggestion.cityName?.let { city -> @Composable { Text(city) } },
                modifier = Modifier.clickable(role = Role.Button) {
                    actions.onSearchListingClick(suggestion.id)
                    focusManager.clearFocus()
                },
            )
        }
        !state.hasSubmittedQuery -> searchStateMessage(
            title = strings.search.title,
            supportingText = strings.search.initialHint,
        )
        state.isEmpty -> searchStateMessage(
            title = strings.search.emptyTitle,
            supportingText = strings.search.emptyMessage,
            actionLabel = strings.search.tryAssistant.takeIf { actions.onSearchAssistantClick != null },
            onAction = actions.onSearchAssistantClick,
        )
        else -> searchResultItems(state, strings, mediaUrlPolicy, actions)
    }
}

private fun LazyGridScope.searchResultItems(
    state: SearchUiState,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    actions: ExploreScreenActions,
) {
    val priceOptions = PriceTagOptions(currency = state.context.currency, mode = PriceTagMode.Compact)
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text = state.resultCountLabel,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    items(items = state.listings, key = { listing -> listing.id }) { listing ->
        ListingCard(
            state = listing.toCardState(priceOptions = priceOptions),
            strings = strings,
            mediaUrlPolicy = mediaUrlPolicy,
            actions = ListingCardActions(
                onClick = { actions.onSearchListingClick(listing.id) },
                openAccessibilityDescription = listing.cardAccessibilityDescription(strings),
            ),
        )
    }
}

private fun LazyGridScope.searchAppendItem(
    state: SearchUiState,
    strings: KwaborStrings,
    actions: ExploreScreenActions,
) {
    when {
        state.isAppending -> item(span = { GridItemSpan(maxLineSpan) }) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(KwaborSpacing.Lg)
                    .semantics { contentDescription = strings.loading },
            )
        }
        state.appendErrorMessage != null -> searchStateMessage(
            title = strings.errorStateTitle,
            supportingText = state.appendErrorMessage,
            actionLabel = strings.retry,
            onAction = actions.onSearchLoadNext,
        )
    }
}

private fun LazyGridScope.searchStateMessage(
    title: String,
    supportingText: String?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
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

internal fun searchLiveRegionStatus(state: SearchUiState, strings: KwaborStrings): String? = when {
    state.isLoading || state.isAppending -> strings.loading
    state.queryErrorMessage != null -> state.queryErrorMessage
    state.errorMessage != null -> state.errorMessage
    state.appendErrorMessage != null -> state.appendErrorMessage
    state.refreshMessage != null -> state.refreshMessage
    state.isEmpty -> strings.search.emptyTitle
    state.hasSubmittedQuery && state.resultCountLabel.isNotBlank() -> state.resultCountLabel
    else -> null
}
