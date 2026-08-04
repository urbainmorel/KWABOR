package com.kwabor.shared.data.search

import androidx.sqlite.SQLiteException
import com.kwabor.shared.data.local.CorruptExploreCacheException
import com.kwabor.shared.data.local.SearchCacheCandidate
import com.kwabor.shared.data.local.SearchCacheLimitExceededException
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.search.SearchPageRequest
import com.kwabor.shared.domain.search.SearchQuery
import com.kwabor.shared.domain.search.SearchRepository
import com.kwabor.shared.domain.search.SearchResult
import com.kwabor.shared.domain.search.SearchResultSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class OfflineFirstSearchRepository(
    private val catalogRepository: CatalogRepository,
    private val localCache: SearchLocalCache?,
    private val localSearchDispatcher: CoroutineDispatcher,
) : SearchRepository {
    override suspend fun search(query: SearchQuery, page: SearchPageRequest): DomainResult<SearchResult> {
        val localSequence = page.cursor.toLocalCursorSequenceOrNull()
        if (localSequence != null) {
            if (page.excludedListingIds.isEmpty()) {
                return invalidPageFailure()
            }
            return searchLocal(query, page, localSequence, fallbackFailure = null)
        }
        if (page.cursor.isReservedLocalCursor()) {
            return invalidPageFailure()
        }
        return searchNetworkFirst(query, page)
    }

    private suspend fun searchNetworkFirst(query: SearchQuery, page: SearchPageRequest): DomainResult<SearchResult> =
        when (
            val result = catalogRepository.searchListings(
                query = ListingSearchQuery(text = query.text, filters = query.filters),
                page = ListingPageRequest(cursor = page.cursor, limit = page.limit),
            )
        ) {
            is DomainResult.Success -> result.value.toNetworkResult(query.filters, page)
            is DomainResult.Failure -> when (result.error) {
                is DomainError.NetworkUnavailable -> if (
                    page.cursor != null && page.excludedListingIds.isEmpty()
                ) {
                    result
                } else {
                    searchLocal(
                        query = query,
                        page = page,
                        localSequence = INITIAL_LOCAL_CURSOR_SEQUENCE,
                        fallbackFailure = result,
                    )
                }
                is DomainError.AuthenticationRequired,
                is DomainError.LocalStorageUnavailable,
                is DomainError.NotFound,
                is DomainError.PermissionDenied,
                is DomainError.Unexpected,
                is DomainError.Validation,
                -> result
            }
        }

    private suspend fun searchLocal(
        query: SearchQuery,
        page: SearchPageRequest,
        localSequence: Int,
        fallbackFailure: DomainResult.Failure?,
    ): DomainResult<SearchResult> = withContext(localSearchDispatcher) {
        val availableCache = localCache ?: return@withContext fallbackFailure ?: localStorageFailure()
        val candidates = availableCache.readCandidatesOrNull(query.filters)
            ?: return@withContext fallbackFailure ?: localStorageFailure()
        DomainResult.Success(candidates.toLocalResult(query, page, localSequence))
    }
}

private fun ListingSummaryPage.toNetworkResult(
    filters: ListingFilters,
    page: SearchPageRequest,
): DomainResult<SearchResult> {
    if (items.size > page.limit) {
        return invalidPayloadFailure()
    }
    if (nextCursor != null && nextCursor == page.cursor) {
        return invalidPayloadFailure()
    }
    if (!nextCursor.isValidRemoteCursor()) {
        return invalidPayloadFailure()
    }
    if (items.any { listing -> !listing.matches(filters) }) {
        return invalidPayloadFailure()
    }
    val safeItems = items
        .asSequence()
        .filterNot { listing -> listing.id in page.excludedListingIds }
        .distinctBy(ListingSummary::id)
        .toList()
    return DomainResult.Success(
        SearchResult(
            items = safeItems,
            nextCursor = nextCursor,
            source = SearchResultSource.Network,
        ),
    )
}

private fun ListingSummary.matches(filters: ListingFilters): Boolean = status == ListingStatus.Published &&
    (filters.cityId == null || cityId == filters.cityId) &&
    (filters.categoryId == null || categoryId == filters.categoryId) &&
    (filters.listingType == null || type == filters.listingType) &&
    (filters.listingClass == null || listingClass == filters.listingClass)

private suspend fun SearchLocalCache.readCandidatesOrNull(filters: ListingFilters): List<SearchCacheCandidate>? = try {
    readCandidates(filters)
} catch (exception: CancellationException) {
    throw exception
} catch (_: SQLiteException) {
    null
} catch (_: CorruptExploreCacheException) {
    null
} catch (_: SearchCacheLimitExceededException) {
    null
}

private fun List<SearchCacheCandidate>.toLocalResult(
    query: SearchQuery,
    page: SearchPageRequest,
    localSequence: Int,
): SearchResult {
    val queryTokens = query.text.toSearchTokens()
    val matches = asSequence()
        .filter { candidate -> candidate.listing.matches(query.filters) }
        .filter { candidate -> candidate.matches(queryTokens) }
        .distinctBy { candidate -> candidate.listing.id }
        .filterNot { candidate -> candidate.listing.id in page.excludedListingIds }
        .take(page.limit + 1)
        .toList()
    val hasNextPage = matches.size > page.limit
    return SearchResult(
        items = matches.take(page.limit).map(SearchCacheCandidate::listing),
        nextCursor = localNextCursor(hasNextPage, localSequence),
        source = SearchResultSource.LocalCache,
    )
}

private fun localNextCursor(hasNextPage: Boolean, localSequence: Int): String? =
    if (hasNextPage && localSequence < MAX_LOCAL_CURSOR_SEQUENCE) {
        localCursor(localSequence + 1)
    } else {
        null
    }

private fun SearchCacheCandidate.matches(queryTokens: Set<String>): Boolean {
    if (queryTokens.isEmpty()) return false
    val searchableText = buildString {
        append(listing.name)
        append(' ').append(listing.cityId)
        append(' ').append(listing.categoryId)
        cityName?.let { city -> append(' ').append(city) }
        categoryNameKey?.let { category -> append(' ').append(category) }
    }
    val candidateTokens = searchableText.toSearchTokens()
    return queryTokens.all(candidateTokens::contains)
}

private fun String.toSearchTokens(): Set<String> =
    normalizeForSearch().split(SEARCH_TOKEN_SEPARATOR).filter(String::isNotEmpty).toSet()

private fun String.normalizeForSearch(): String = buildString(length) {
    this@normalizeForSearch.lowercase().forEach { character ->
        val foldedCharacter = SEARCH_CHARACTER_FOLDS[character]
        when (character) {
            in COMBINING_DIACRITICS -> Unit
            else -> when {
                foldedCharacter != null -> append(foldedCharacter)
                character.isLetterOrDigit() -> append(character)
                else -> append(' ')
            }
        }
    }
}

private fun String?.toLocalCursorSequenceOrNull(): Int? {
    if (this == null || !startsWith(LOCAL_CURSOR_PREFIX)) {
        return null
    }
    return removePrefix(LOCAL_CURSOR_PREFIX)
        .toIntOrNull()
        ?.takeIf { sequence -> sequence in FIRST_LOCAL_CURSOR_SEQUENCE..MAX_LOCAL_CURSOR_SEQUENCE }
}

private fun String?.isReservedLocalCursor(): Boolean = this?.startsWith(LOCAL_CURSOR_NAMESPACE) == true

private fun String?.isValidRemoteCursor(): Boolean =
    this == null || (isNotBlank() && length <= MAX_REMOTE_CURSOR_LENGTH && none(Char::isWhitespace))

private fun localCursor(sequence: Int): String = "$LOCAL_CURSOR_PREFIX$sequence"

private fun <T> invalidPageFailure(): DomainResult<T> =
    DomainResult.Failure(DomainError.Validation(SEARCH_PAGE_INVALID_ERROR_KEY))

private fun <T> invalidPayloadFailure(): DomainResult<T> =
    DomainResult.Failure(DomainError.Unexpected(SEARCH_PAYLOAD_INVALID_ERROR_KEY))

private fun <T> localStorageFailure(): DomainResult<T> =
    DomainResult.Failure(DomainError.LocalStorageUnavailable(SEARCH_STORAGE_ERROR_KEY))

private val SEARCH_TOKEN_SEPARATOR = Regex("\\s+")
private val SEARCH_CHARACTER_FOLDS = mapOf(
    'à' to "a",
    'á' to "a",
    'â' to "a",
    'ã' to "a",
    'ä' to "a",
    'å' to "a",
    'æ' to "ae",
    'ç' to "c",
    'è' to "e",
    'é' to "e",
    'ê' to "e",
    'ë' to "e",
    'ì' to "i",
    'í' to "i",
    'î' to "i",
    'ï' to "i",
    'ñ' to "n",
    'ò' to "o",
    'ó' to "o",
    'ô' to "o",
    'õ' to "o",
    'ö' to "o",
    'ø' to "o",
    'œ' to "oe",
    'ù' to "u",
    'ú' to "u",
    'û' to "u",
    'ü' to "u",
    'ý' to "y",
    'ÿ' to "y",
)
private val COMBINING_DIACRITICS = '\u0300'..'\u036f'
private const val INITIAL_LOCAL_CURSOR_SEQUENCE = 0
private const val FIRST_LOCAL_CURSOR_SEQUENCE = 1
private const val MAX_LOCAL_CURSOR_SEQUENCE = 3_200
private const val MAX_REMOTE_CURSOR_LENGTH = 4_096
private const val LOCAL_CURSOR_NAMESPACE = "search-local:"
private const val LOCAL_CURSOR_PREFIX = "search-local:v1:"
private const val SEARCH_PAGE_INVALID_ERROR_KEY = "error.search.page_invalid"
private const val SEARCH_PAYLOAD_INVALID_ERROR_KEY = "error.search.payload_invalid"
private const val SEARCH_STORAGE_ERROR_KEY = "error.search.local_cache_unavailable"
