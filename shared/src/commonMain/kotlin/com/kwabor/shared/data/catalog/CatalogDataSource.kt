package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.PageRequest

internal interface CatalogDataSource : CatalogQueryDataSource, CatalogInteractionDataSource

internal interface CatalogQueryDataSource {
    suspend fun listCities(): List<CityDto>

    suspend fun listCategories(): List<CategoryDto>

    suspend fun listListings(filters: ListingFilters, page: PageRequest): List<ListingSummaryDto>

    suspend fun searchListings(query: ListingSearchQuery, page: PageRequest): List<ListingSummaryDto>

    suspend fun getListingDetail(listingId: String): ListingDetailDto
}

internal interface CatalogInteractionDataSource {
    suspend fun getListingViewerInteraction(listingId: String): ListingViewerInteractionDto

    suspend fun listListingViewerInteractions(listingIds: List<String>): List<ListingViewerInteractionDto>

    suspend fun likeListing(listingId: String): ListingViewerInteractionDto

    suspend fun unlikeListing(listingId: String): ListingViewerInteractionDto

    suspend fun favoriteListing(listingId: String): ListingViewerInteractionDto

    suspend fun unfavoriteListing(listingId: String): ListingViewerInteractionDto
}

internal sealed class CatalogDataException(
    val domainError: DomainError,
    cause: Throwable? = null,
) : RuntimeException(domainError.messageKey, cause) {
    class NotFound(
        messageKey: String = "error.catalog.listing_not_found",
        cause: Throwable? = null,
    ) : CatalogDataException(DomainError.NotFound(messageKey), cause)

    class PermissionDenied(
        messageKey: String = "error.catalog.permission_denied",
        cause: Throwable? = null,
    ) : CatalogDataException(DomainError.PermissionDenied(messageKey), cause)

    class AuthenticationRequired(cause: Throwable? = null) :
        CatalogDataException(DomainError.AuthenticationRequired(), cause)

    class Validation(
        messageKey: String = "error.catalog.invalid_request",
        cause: Throwable? = null,
    ) : CatalogDataException(DomainError.Validation(messageKey), cause)

    class NetworkUnavailable(cause: Throwable? = null) :
        CatalogDataException(DomainError.NetworkUnavailable(), cause)

    class Unexpected(cause: Throwable? = null) : CatalogDataException(DomainError.Unexpected(), cause)
}
