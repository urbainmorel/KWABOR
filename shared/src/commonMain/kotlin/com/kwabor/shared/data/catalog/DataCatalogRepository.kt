package com.kwabor.shared.data.catalog

import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.DomainResult

class DataCatalogRepository internal constructor(
    private val dataSource: CatalogDataSource,
) : CatalogRepository {
    override suspend fun listCities(): DomainResult<List<City>> = runDataCall {
        dataSource.listCities().map { item -> item.toDomain() }
    }

    override suspend fun listCategories(): DomainResult<List<Category>> = runDataCall {
        dataSource.listCategories().map { item -> item.toDomain() }
    }

    override suspend fun listListings(
        filters: ListingFilters,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = runDataCall {
        filters.requirePublishedOnly()
        dataSource.listListings(filters = filters, page = page)
            .toDomain()
    }

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = runDataCall {
        query.filters.requirePublishedOnly()
        dataSource.searchListings(query = query, page = page)
            .toDomain()
    }

    override suspend fun getListingDetail(listingId: String): DomainResult<CatalogDetail> = runDataCall {
        dataSource.getListingDetail(listingId.toRequiredListingId()).toDomain()
    }

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        runDataCall {
            dataSource.getListingViewerInteraction(listingId.toRequiredListingId()).toDomain()
        }

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = runDataCall {
        val requestedListingIds = listingIds.toRequiredListingIds()
        if (requestedListingIds.isEmpty()) {
            emptyList()
        } else {
            dataSource.listListingViewerInteractions(requestedListingIds).map { item -> item.toDomain() }
        }
    }

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> = runDataCall {
        dataSource.likeListing(listingId.toRequiredListingId()).toDomain()
    }

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> = runDataCall {
        dataSource.unlikeListing(listingId.toRequiredListingId()).toDomain()
    }
}

private inline fun <T> runDataCall(block: () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: CatalogDataException) {
    DomainResult.Failure(exception.domainError)
}

private fun String.toRequiredListingId(): String {
    val value = trim()
    if (!value.isValidUuid()) {
        throw CatalogDataException.Validation("error.catalog.listing_id_invalid")
    }

    return value
}

private fun List<String>.toRequiredListingIds(): List<String> = map { listingId -> listingId.toRequiredListingId() }
    .distinct()

private fun ListingFilters.requirePublishedOnly() {
    if (!onlyPublished) {
        throw CatalogDataException.Validation()
    }
}
