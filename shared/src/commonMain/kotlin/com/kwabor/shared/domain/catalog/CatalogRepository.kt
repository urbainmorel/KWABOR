package com.kwabor.shared.domain.catalog

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult

interface CatalogRepository : CatalogQueryRepository, CatalogInteractionRepository

interface CatalogQueryRepository {
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

interface CatalogInteractionRepository {
    suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction>

    suspend fun listListingViewerInteractions(listingIds: List<String>): DomainResult<List<ListingViewerInteraction>>

    suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction>

    suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction>

    suspend fun favoriteListing(listingId: String): DomainResult<ListingViewerInteraction>

    suspend fun unfavoriteListing(listingId: String): DomainResult<ListingViewerInteraction>
}
