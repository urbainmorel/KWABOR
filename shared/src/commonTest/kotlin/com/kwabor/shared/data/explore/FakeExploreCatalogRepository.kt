package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import kotlinx.coroutines.CompletableDeferred

internal class FakeExploreCatalogRepository : CatalogRepository {
    var citiesResult: DomainResult<List<City>> = DomainResult.Success(testCities())
    var categoriesResult: DomainResult<List<Category>> = DomainResult.Success(testCategories())
    val listingResults = ArrayDeque<DomainResult<ListingSummaryPage>>()
    val listingRequests = mutableListOf<Pair<ListingFilters, ListingPageRequest>>()
    var citiesGate: CompletableDeferred<Unit>? = null
    var citiesCallCount: Int = 0
    var listingsGate: CompletableDeferred<Unit>? = null
    var listingsCallCount: Int = 0

    override suspend fun listCities(): DomainResult<List<City>> {
        citiesCallCount += 1
        citiesGate?.await()
        return citiesResult
    }

    override suspend fun listCategories(): DomainResult<List<Category>> = categoriesResult

    override suspend fun listListings(
        filters: ListingFilters,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> {
        listingsCallCount += 1
        listingRequests += filters to page
        listingsGate?.await()
        return listingResults.removeFirstOrNull() ?: unused()
    }

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = unused()

    override suspend fun getListingDetail(listingId: String): DomainResult<CatalogDetail> = unused()

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        unused()

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = unused()

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> = unused()

    private fun <T> unused(): DomainResult<T> = DomainResult.Failure(DomainError.Unexpected("unused"))
}
