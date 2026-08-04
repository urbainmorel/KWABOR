package com.kwabor.shared.presentation.search

import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.search.SearchPageRequest
import com.kwabor.shared.domain.search.SearchQuery
import com.kwabor.shared.domain.search.SearchRepository
import com.kwabor.shared.domain.search.SearchResult
import com.kwabor.shared.domain.search.SearchResultSource
import com.kwabor.shared.i18n.SearchStrings
import com.kwabor.shared.presentation.explore.ExploreListingItem
import kotlin.math.roundToInt

private const val SEARCH_PAGE_SIZE = 20
private const val RATING_DECIMAL_SCALE = 10
private const val RATING_DECIMAL_DIVISOR = 10.0

class SearchPresenter(
    private val repository: SearchRepository,
) {
    suspend fun submit(state: SearchUiState, strings: SearchStrings): SearchUiState {
        val query = when (
            val result = SearchQuery.from(
                text = state.queryText,
                filters = state.context.filtersFor(state.scope),
            )
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return state.invalidQuery(strings)
        }
        return loadFirstPage(state = state, query = query, strings = strings)
    }

    suspend fun refresh(state: SearchUiState, strings: SearchStrings): SearchUiState {
        val query = state.toSubmittedQueryOrNull() ?: return state.copy(isRefreshing = false)
        val refreshed = loadFirstPage(state = state, query = query, strings = strings)
        return if (refreshed.errorMessage == null) {
            refreshed.copy(isRefreshing = false)
        } else if (state.listings.isEmpty()) {
            refreshed.copy(isRefreshing = false)
        } else {
            state.copy(
                isLoading = false,
                isRefreshing = false,
                isAppending = false,
                networkUnavailable = refreshed.networkUnavailable,
                refreshMessage = strings.refreshFailed,
                appendErrorMessage = null,
            )
        }
    }

    suspend fun append(state: SearchUiState, strings: SearchStrings): SearchUiState {
        val query = state.toSubmittedQueryOrNull() ?: return state.copy(isAppending = false)
        val cursor = state.nextCursor ?: return state.copy(isAppending = false)
        val visibleIds = state.listings.mapTo(mutableSetOf(), ExploreListingItem::id)
        val request = SearchPageRequest(
            cursor = cursor,
            limit = SEARCH_PAGE_SIZE,
            excludedListingIds = visibleIds.takeIf {
                it.size <= SearchPageRequest.MAX_EXCLUDED_LISTING_IDS
            }.orEmpty(),
        )
        return when (val result = repository.search(query = query, page = request)) {
            is DomainResult.Success -> state.append(result.value, cursor, strings)
            is DomainResult.Failure -> state.appendFailure(result.error, strings)
        }
    }

    private suspend fun loadFirstPage(
        state: SearchUiState,
        query: SearchQuery,
        strings: SearchStrings,
    ): SearchUiState = when (
        val result = repository.search(
            query = query,
            page = SearchPageRequest(limit = SEARCH_PAGE_SIZE),
        )
    ) {
        is DomainResult.Success -> state.loaded(query.text, result.value, strings)
        is DomainResult.Failure -> state.loadFailure(query.text, result.error, strings)
    }
}

private fun SearchUiState.toSubmittedQueryOrNull(): SearchQuery? {
    val text = submittedQueryText ?: return null
    return when (val result = SearchQuery.from(text = text, filters = context.filtersFor(scope))) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> null
    }
}

private fun SearchUiState.invalidQuery(strings: SearchStrings): SearchUiState = copy(
    submittedQueryText = null,
    listings = emptyList(),
    nextCursor = null,
    resultSource = null,
    networkUnavailable = false,
    resultCountLabel = "",
    isLoading = false,
    isRefreshing = false,
    isAppending = false,
    queryErrorMessage = strings.invalidQuery,
    errorMessage = null,
    refreshMessage = null,
    appendErrorMessage = null,
)

private fun SearchUiState.loaded(canonicalQuery: String, result: SearchResult, strings: SearchStrings): SearchUiState {
    val mapped = result.items.map { listing -> listing.toUiModel(context) }
    return copy(
        queryText = canonicalQuery,
        submittedQueryText = canonicalQuery,
        listings = mapped,
        nextCursor = result.nextCursor,
        resultSource = result.source,
        networkUnavailable = false,
        resultCountLabel = mapped.size.toResultCountLabel(strings),
        isLoading = false,
        isRefreshing = false,
        isAppending = false,
        queryErrorMessage = null,
        errorMessage = null,
        refreshMessage = null,
        appendErrorMessage = null,
    )
}

private fun SearchUiState.loadFailure(
    canonicalQuery: String,
    error: DomainError,
    strings: SearchStrings,
): SearchUiState = copy(
    queryText = canonicalQuery,
    submittedQueryText = canonicalQuery,
    listings = emptyList(),
    nextCursor = null,
    resultSource = null,
    networkUnavailable = error is DomainError.NetworkUnavailable,
    resultCountLabel = "",
    isLoading = false,
    isRefreshing = false,
    isAppending = false,
    queryErrorMessage = null,
    errorMessage = strings.loadFailed,
    refreshMessage = null,
    appendErrorMessage = null,
)

private fun SearchUiState.append(
    result: SearchResult,
    requestedCursor: String,
    strings: SearchStrings,
): SearchUiState {
    val existingIds = listings.mapTo(mutableSetOf(), ExploreListingItem::id)
    val mapped = result.items.map { listing -> listing.toUiModel(context) }
    val invalidPage = result.nextCursor == requestedCursor || mapped.any { item -> item.id in existingIds }
    if (invalidPage) {
        return appendFailure(DomainError.Unexpected(), strings)
    }
    val merged = listings + mapped
    return copy(
        listings = merged,
        nextCursor = result.nextCursor.takeUnless {
            result.source == SearchResultSource.LocalCache &&
                merged.size >= SearchPageRequest.MAX_EXCLUDED_LISTING_IDS
        },
        resultSource = result.source,
        networkUnavailable = false,
        resultCountLabel = merged.size.toResultCountLabel(strings),
        isAppending = false,
        appendErrorMessage = null,
    )
}

private fun SearchUiState.appendFailure(error: DomainError, strings: SearchStrings): SearchUiState = copy(
    isAppending = false,
    networkUnavailable = networkUnavailable || error is DomainError.NetworkUnavailable,
    appendErrorMessage = strings.loadMoreFailed,
)

private fun ListingSummary.toUiModel(context: SearchContext): ExploreListingItem {
    val cityLabel = context.availableCities.firstOrNull { city -> city.id == cityId }?.label ?: cityId
    return ExploreListingItem(
        id = id,
        title = name,
        cityLabel = cityLabel,
        cityId = cityId,
        coverImageUrl = coverImageUrl,
        price = priceFromXof,
        ratingLabel = ratingAverage?.toRatingLabel(),
        likesCount = likesCount,
        sponsored = isSponsoredPlacement == true,
    )
}

private fun Int.toResultCountLabel(strings: SearchStrings): String = when (this) {
    1 -> strings.oneResult
    else -> strings.manyResults.replace("{count}", toString())
}

private fun Double.toRatingLabel(): String {
    val rounded = (this * RATING_DECIMAL_SCALE).roundToInt() / RATING_DECIMAL_DIVISOR
    return rounded.toString().replace(oldChar = '.', newChar = ',')
}
