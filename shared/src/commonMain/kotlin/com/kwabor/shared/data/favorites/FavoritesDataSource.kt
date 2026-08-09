package com.kwabor.shared.data.favorites

import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainError

internal interface FavoritesDataSource {
    suspend fun listFavorites(filter: ListingType?, page: ListingPageRequest): FavoriteListingPageDto

    suspend fun setFavorite(listingId: String, favorited: Boolean): FavoriteMutationRowDto
}

internal sealed class FavoritesDataException(
    val domainError: DomainError,
    cause: Throwable? = null,
) : RuntimeException(domainError.messageKey, cause) {
    class NotFound(cause: Throwable? = null) : FavoritesDataException(
        domainError = DomainError.NotFound("error.favorites.listing_not_found"),
        cause = cause,
    )

    class PermissionDenied(cause: Throwable? = null) : FavoritesDataException(
        domainError = DomainError.PermissionDenied("error.favorites.permission_denied"),
        cause = cause,
    )

    class AuthenticationRequired(cause: Throwable? = null) : FavoritesDataException(
        domainError = DomainError.AuthenticationRequired(),
        cause = cause,
    )

    class Validation(
        messageKey: String = "error.favorites.invalid_request",
        cause: Throwable? = null,
    ) : FavoritesDataException(
        domainError = DomainError.Validation(messageKey),
        cause = cause,
    )

    class NetworkUnavailable(cause: Throwable? = null) : FavoritesDataException(
        domainError = DomainError.NetworkUnavailable(),
        cause = cause,
    )

    class Unexpected(cause: Throwable? = null) : FavoritesDataException(
        domainError = DomainError.Unexpected(),
        cause = cause,
    )
}
