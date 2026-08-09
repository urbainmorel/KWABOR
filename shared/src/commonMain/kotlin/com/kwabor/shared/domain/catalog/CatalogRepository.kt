package com.kwabor.shared.domain.catalog

import com.kwabor.shared.domain.core.DomainResult

interface CatalogRepository : CatalogQueryRepository, CatalogInteractionRepository

interface CatalogQueryRepository {
    suspend fun listCities(): DomainResult<List<City>>

    suspend fun listCategories(): DomainResult<List<Category>>

    suspend fun listListings(
        filters: ListingFilters,
        page: ListingPageRequest = ListingPageRequest(),
    ): DomainResult<ListingSummaryPage>

    suspend fun searchListings(
        query: ListingSearchQuery,
        page: ListingPageRequest = ListingPageRequest(),
    ): DomainResult<ListingSummaryPage>

    suspend fun getListingDetail(listingId: String): DomainResult<CatalogDetail>
}

interface CatalogInteractionRepository {
    suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction>

    suspend fun listListingViewerInteractions(listingIds: List<String>): DomainResult<List<ListingViewerInteraction>>

    suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction>

    suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction>
}
