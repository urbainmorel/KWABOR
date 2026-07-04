package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingDetail
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult

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
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> = runDataCall {
        dataSource.listListings(filters = filters, page = page)
            .map { item -> item.toDomain() }
            .toPageResult(page)
    }

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> = runDataCall {
        dataSource.searchListings(query = query, page = page)
            .map { item -> item.toDomain() }
            .toPageResult(page)
    }

    override suspend fun getListingDetail(listingId: String): DomainResult<ListingDetail> = runDataCall {
        dataSource.getListingDetail(listingId).toDomain()
    }
}

private inline fun <T> runDataCall(block: () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: CatalogDataException) {
    DomainResult.Failure(exception.domainError)
} catch (exception: NoSuchElementException) {
    DomainResult.Failure(DomainError.NotFound("error.catalog.listing_not_found"))
} catch (exception: IllegalArgumentException) {
    DomainResult.Failure(DomainError.Unexpected())
} catch (exception: IllegalStateException) {
    DomainResult.Failure(DomainError.Unexpected())
} catch (exception: Exception) {
    DomainResult.Failure(DomainError.Unexpected())
}

private fun <T> List<T>.toPageResult(page: PageRequest): PageResult<T> = PageResult(
    items = this,
    nextOffset = if (size >= page.limit) page.offset + page.limit else null,
)
