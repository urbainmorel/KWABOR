package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.PageRequest

internal interface CatalogDataSource {
    suspend fun listCities(): List<CityDto>

    suspend fun listCategories(): List<CategoryDto>

    suspend fun listListings(filters: ListingFilters, page: PageRequest): List<ListingSummaryDto>

    suspend fun searchListings(query: ListingSearchQuery, page: PageRequest): List<ListingSummaryDto>

    suspend fun getListingDetail(listingId: String): ListingDetailDto

    suspend fun getListingViewerInteraction(listingId: String): ListingViewerInteractionDto

    suspend fun listListingViewerInteractions(listingIds: List<String>): List<ListingViewerInteractionDto>

    suspend fun likeListing(listingId: String): ListingViewerInteractionDto

    suspend fun unlikeListing(listingId: String): ListingViewerInteractionDto

    suspend fun favoriteListing(listingId: String): ListingViewerInteractionDto

    suspend fun unfavoriteListing(listingId: String): ListingViewerInteractionDto
}

internal sealed class CatalogDataException(
    val domainError: DomainError,
) : RuntimeException(domainError.messageKey) {
    class NotFound(
        messageKey: String = "error.catalog.listing_not_found",
    ) : CatalogDataException(DomainError.NotFound(messageKey))

    class PermissionDenied(
        messageKey: String = "error.catalog.permission_denied",
    ) : CatalogDataException(DomainError.PermissionDenied(messageKey))

    class AuthenticationRequired : CatalogDataException(DomainError.AuthenticationRequired())

    class Validation(
        messageKey: String = "error.catalog.invalid_request",
    ) : CatalogDataException(DomainError.Validation(messageKey))

    class NetworkUnavailable : CatalogDataException(DomainError.NetworkUnavailable())

    class Unexpected : CatalogDataException(DomainError.Unexpected())
}
