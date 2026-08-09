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
import com.kwabor.shared.domain.explore.ExploreCatalogPage
import com.kwabor.shared.domain.explore.ExploreCatalogRepository
import com.kwabor.shared.domain.explore.ExploreCatalogRequest
import kotlinx.coroutines.CompletableDeferred

internal class FakeExploreCatalogRepository : CatalogRepository, ExploreCatalogRepository {
    var citiesResult: DomainResult<List<City>> = DomainResult.Success(testCities())
    var categoriesResult: DomainResult<List<Category>> = DomainResult.Success(testCategories())
    val listingResults = ArrayDeque<DomainResult<ListingSummaryPage>>()
    val exploreCatalogResults = ArrayDeque<DomainResult<ExploreCatalogPage>>()
    val listingRequests = mutableListOf<Pair<ListingFilters, ListingPageRequest>>()
    val exploreCatalogRequests = mutableListOf<ExploreCatalogRequest>()
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

    override suspend fun listCatalog(request: ExploreCatalogRequest): DomainResult<ExploreCatalogPage> {
        listingsCallCount += 1
        exploreCatalogRequests += request
        listingRequests += ListingFilters(
            cityId = request.cityId,
            categoryId = request.categoryId,
            listingType = request.listingType,
            listingClass = request.listingClass,
        ) to ListingPageRequest(cursor = request.cursor, limit = request.limit)
        listingsGate?.await()
        exploreCatalogResults.removeFirstOrNull()?.let { result -> return result }
        return when (val result = listingResults.removeFirstOrNull() ?: unused()) {
            is DomainResult.Success -> try {
                DomainResult.Success(
                    ExploreCatalogPage(
                        items = result.value.items,
                        nextCursor = result.value.nextCursor,
                        snapshotAtEpochMicroseconds = if (result.value.items.isEmpty()) {
                            null
                        } else {
                            TEST_EXPLORE_SERVER_SNAPSHOT_MICROSECONDS
                        },
                    ),
                )
            } catch (_: IllegalArgumentException) {
                DomainResult.Failure(DomainError.Unexpected("invalid fake Explore page"))
            }
            is DomainResult.Failure -> result
        }
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

internal const val TEST_EXPLORE_SERVER_SNAPSHOT_MICROSECONDS = 1_750_000_000_000_000L
