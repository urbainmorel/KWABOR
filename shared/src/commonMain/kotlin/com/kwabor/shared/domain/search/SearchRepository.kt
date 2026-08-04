package com.kwabor.shared.domain.search

import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult

@ConsistentCopyVisibility
data class SearchQuery private constructor(
    val text: String,
    val filters: ListingFilters,
) {
    init {
        require(text.isValidSearchText()) { "Search query text is invalid." }
        require(filters.isValidForSearch()) { "Search query filters are invalid." }
    }

    companion object {
        fun from(text: String, filters: ListingFilters = ListingFilters()): DomainResult<SearchQuery> {
            val canonicalText = text.trim()
            if (!canonicalText.isValidSearchText()) {
                return DomainResult.Failure(DomainError.Validation(SEARCH_QUERY_INVALID_ERROR_KEY))
            }
            if (!filters.isValidForSearch()) {
                return DomainResult.Failure(DomainError.Validation(SEARCH_FILTERS_INVALID_ERROR_KEY))
            }
            return DomainResult.Success(SearchQuery(text = canonicalText, filters = filters))
        }
    }
}

data class SearchPageRequest(
    val cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
    val excludedListingIds: Set<String> = emptySet(),
) {
    init {
        require(cursor.isValidSearchCursor()) { "Search page cursor is invalid." }
        require(limit in 1..MAX_LIMIT) { "Search page limit must be between 1 and $MAX_LIMIT." }
        require(excludedListingIds.size <= MAX_EXCLUDED_LISTING_IDS) {
            "Search page contains too many excluded listing ids."
        }
        require(excludedListingIds.all(String::isValidSearchId)) {
            "Search page contains an invalid excluded listing id."
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        const val MAX_EXCLUDED_LISTING_IDS = 3_200
    }
}

enum class SearchResultSource {
    Network,
    LocalCache,
}

data class SearchResult(
    val items: List<ListingSummary>,
    val nextCursor: String?,
    val source: SearchResultSource,
) {
    init {
        require(items.map(ListingSummary::id).distinct().size == items.size) {
            "Search result must not contain duplicate listing ids."
        }
        require(nextCursor.isValidSearchCursor()) { "Search result cursor is invalid." }
    }
}

interface SearchRepository {
    suspend fun search(query: SearchQuery, page: SearchPageRequest = SearchPageRequest()): DomainResult<SearchResult>
}

private fun String.isValidSearchText(): Boolean =
    length in MIN_SEARCH_QUERY_LENGTH..MAX_SEARCH_QUERY_LENGTH && none(Char::isISOControl)

private fun ListingFilters.isValidForSearch(): Boolean =
    onlyPublished && cityId.isValidOptionalSearchId() && categoryId.isValidOptionalSearchId()

private fun String?.isValidOptionalSearchId(): Boolean = this == null || isValidSearchId()

private fun String.isValidSearchId(): Boolean =
    isNotBlank() && length <= MAX_SEARCH_ID_LENGTH && none(Char::isISOControl)

private fun String?.isValidSearchCursor(): Boolean =
    this == null || (isNotBlank() && length <= MAX_SEARCH_CURSOR_LENGTH && none(Char::isWhitespace))

private const val MIN_SEARCH_QUERY_LENGTH = 1
private const val MAX_SEARCH_QUERY_LENGTH = 120
private const val MAX_SEARCH_ID_LENGTH = 100
private const val MAX_SEARCH_CURSOR_LENGTH = 4_096
private const val SEARCH_QUERY_INVALID_ERROR_KEY = "error.search.query_invalid"
private const val SEARCH_FILTERS_INVALID_ERROR_KEY = "error.search.filters_invalid"
