package com.kwabor.android.ui.screens.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
import com.kwabor.shared.presentation.search.SearchScope
import com.kwabor.shared.presentation.search.SearchUiState

internal const val SEARCH_FIELD_TEST_TAG = "explore-search-field"

@Composable
internal fun ExploreSearchControl(state: SearchUiState, strings: KwaborStrings, actions: ExploreScreenActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchInputField(
                state = state,
                strings = strings,
                actions = actions,
                modifier = Modifier.weight(1f),
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
private fun SearchInputField(
    state: SearchUiState,
    strings: KwaborStrings,
    actions: ExploreScreenActions,
    modifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = state.queryText,
        onValueChange = actions.onSearchQueryChanged,
        modifier = modifier.searchInputSemantics(
            state = state,
            strings = strings,
            onActivate = actions.onSearchActivate,
        ),
        placeholder = { Text(strings.search.placeholder) },
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            SearchTrailingActions(
                state = state,
                strings = strings,
                onClear = actions.onSearchClear,
                onClose = {
                    focusManager.clearFocus()
                    actions.onSearchClose()
                },
            )
        },
        supportingText = state.queryErrorMessage?.let { error ->
            { Text(text = error, color = MaterialTheme.colorScheme.error) }
        },
        isError = state.queryErrorMessage != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                focusManager.clearFocus()
                actions.onSearchSubmit()
            },
        ),
    )
}

private fun Modifier.searchInputSemantics(
    state: SearchUiState,
    strings: KwaborStrings,
    onActivate: () -> Unit,
): Modifier = this
    .heightIn(min = KwaborSizing.MinimumAccessibleTouchTarget)
    .onFocusChanged { focusState ->
        if (focusState.isFocused && !state.isActive) {
            onActivate()
        }
    }
    .semantics { contentDescription = strings.search.placeholder }
    .testTag(SEARCH_FIELD_TEST_TAG)

@Composable
private fun SearchTrailingActions(
    state: SearchUiState,
    strings: KwaborStrings,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.queryText.isNotEmpty()) {
            IconButton(
                onClick = onClear,
                modifier = Modifier.semantics { contentDescription = strings.search.clear },
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        } else if (state.isActive) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = strings.search.close },
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        }
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
