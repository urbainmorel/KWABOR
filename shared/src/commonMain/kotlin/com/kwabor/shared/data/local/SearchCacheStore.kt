package com.kwabor.shared.data.local

import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSummary

internal data class SearchCacheCandidate(
    val listing: ListingSummary,
    val cityName: String?,
    val categoryNameKey: String?,
)

internal class SearchCacheStore(
    private val dao: SearchCacheDao,
) {
    suspend fun readCandidates(filters: ListingFilters): List<SearchCacheCandidate> {
        require(filters.onlyPublished) { "Local search supports published listings only." }
        val records = dao.findCandidates(
            cityId = filters.cityId,
            categoryId = filters.categoryId,
            listingType = filters.listingType?.toCacheValue(),
            listingClass = filters.listingClass?.toCacheValue(),
            candidateLimit = MAX_LOCAL_SEARCH_CANDIDATES + 1,
        )
        if (records.size > MAX_LOCAL_SEARCH_CANDIDATES) {
            throw SearchCacheLimitExceededException()
        }
        return records.map(SearchCacheCandidateRecord::toCandidate)
    }
}

private fun SearchCacheCandidateRecord.toCandidate(): SearchCacheCandidate = SearchCacheCandidate(
    listing = listing.toDomain(),
    cityName = cityName,
    categoryNameKey = categoryNameKey,
)

internal class SearchCacheLimitExceededException :
    IllegalStateException("Local search cache exceeds its bounded candidate limit.")

internal const val MAX_LOCAL_SEARCH_CANDIDATES =
    DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS * MAX_EXPLORE_CACHE_ITEMS
