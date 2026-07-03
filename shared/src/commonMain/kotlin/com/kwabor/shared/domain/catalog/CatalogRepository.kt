package com.kwabor.shared.domain.catalog

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult

interface CatalogRepository {
    suspend fun listCities(): DomainResult<List<City>>

    suspend fun listCategories(): DomainResult<List<Category>>

    suspend fun listListings(
        filters: ListingFilters,
        page: PageRequest = PageRequest(),
    ): DomainResult<PageResult<ListingSummary>>

    suspend fun searchListings(
        query: ListingSearchQuery,
        page: PageRequest = PageRequest(),
    ): DomainResult<PageResult<ListingSummary>>

    suspend fun getListingDetail(listingId: String): DomainResult<ListingDetail>
}
