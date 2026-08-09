package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.explore.ExploreCatalogRequest

internal interface ExploreCatalogDataSource {
    suspend fun listCatalog(request: ExploreCatalogRequest): ExploreCatalogPageDto
}

internal sealed class ExploreCatalogDataException(
    val domainError: DomainError,
    cause: Throwable? = null,
) : RuntimeException(domainError.messageKey, cause) {
    class PermissionDenied(cause: Throwable? = null) : ExploreCatalogDataException(
        domainError = DomainError.PermissionDenied("error.explore.permission_denied"),
        cause = cause,
    )

    class AuthenticationRequired(cause: Throwable? = null) : ExploreCatalogDataException(
        domainError = DomainError.AuthenticationRequired(),
        cause = cause,
    )

    class Validation(
        messageKey: String = "error.explore.invalid_request",
        cause: Throwable? = null,
    ) : ExploreCatalogDataException(
        domainError = DomainError.Validation(messageKey),
        cause = cause,
    )

    class NetworkUnavailable(cause: Throwable? = null) : ExploreCatalogDataException(
        domainError = DomainError.NetworkUnavailable(),
        cause = cause,
    )

    class Unexpected(cause: Throwable? = null) : ExploreCatalogDataException(
        domainError = DomainError.Unexpected(),
        cause = cause,
    )
}
