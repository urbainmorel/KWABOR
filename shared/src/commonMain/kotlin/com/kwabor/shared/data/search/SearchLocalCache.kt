package com.kwabor.shared.data.search

import com.kwabor.shared.data.local.SearchCacheCandidate
import com.kwabor.shared.data.local.SearchCacheStore
import com.kwabor.shared.domain.catalog.ListingFilters

internal interface SearchLocalCache {
    suspend fun readCandidates(filters: ListingFilters): List<SearchCacheCandidate>
}

internal class StoredSearchLocalCache(
    private val store: Lazy<SearchCacheStore>,
) : SearchLocalCache {
    override suspend fun readCandidates(filters: ListingFilters): List<SearchCacheCandidate> =
        store.value.readCandidates(filters)
}
