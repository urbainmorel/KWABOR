package com.kwabor.shared.data.guide

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.domain.guide.GuidePageRequest

internal interface GuideDiscoveryDataSource {
    suspend fun listFacets(): List<GuideFacetRowDto>

    suspend fun listServices(filters: GuideDiscoveryFilters, page: GuidePageRequest): GuideSummaryPageDto
}

internal sealed class GuideDiscoveryDataException(
    val domainError: DomainError,
    cause: Throwable? = null,
) : RuntimeException(domainError.messageKey, cause) {
    class PermissionDenied(cause: Throwable? = null) : GuideDiscoveryDataException(
        domainError = DomainError.PermissionDenied("error.guide.permission_denied"),
        cause = cause,
    )

    class Validation(
        messageKey: String = "error.guide.invalid_request",
        cause: Throwable? = null,
    ) : GuideDiscoveryDataException(
        domainError = DomainError.Validation(messageKey),
        cause = cause,
    )

    class NetworkUnavailable(cause: Throwable? = null) : GuideDiscoveryDataException(
        domainError = DomainError.NetworkUnavailable(),
        cause = cause,
    )

    class Unexpected(cause: Throwable? = null) : GuideDiscoveryDataException(
        domainError = DomainError.Unexpected(),
        cause = cause,
    )
}
