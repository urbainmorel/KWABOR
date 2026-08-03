package com.kwabor.shared.data.guide

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.domain.guide.GuideDiscoveryRepository
import com.kwabor.shared.domain.guide.GuideFacet
import com.kwabor.shared.domain.guide.GuidePageRequest
import com.kwabor.shared.domain.guide.GuideSummaryPage

class DataGuideDiscoveryRepository internal constructor(
    private val dataSource: GuideDiscoveryDataSource,
) : GuideDiscoveryRepository {
    override suspend fun listFacets(): DomainResult<List<GuideFacet>> = runGuideDiscoveryCall {
        dataSource.listFacets().toDomainFacets()
    }

    override suspend fun listServices(
        filters: GuideDiscoveryFilters,
        page: GuidePageRequest,
    ): DomainResult<GuideSummaryPage> = runGuideDiscoveryCall {
        filters.requireValid()
        if (page.cursor?.isValidGuideCursor() == false) {
            throw GuideDiscoveryDataException.Validation("error.guide.cursor_invalid")
        }
        dataSource.listServices(filters = filters, page = page).toDomain()
    }
}

private inline fun <T> runGuideDiscoveryCall(block: () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: GuideDiscoveryDataException) {
    DomainResult.Failure(exception.domainError)
}

private fun GuideDiscoveryFilters.requireValid() {
    if (cityId?.isCanonicalGuideIdentifier() == false ||
        languageId?.isCanonicalGuideIdentifier() == false ||
        specialtyId?.isCanonicalGuideIdentifier() == false
    ) {
        throw GuideDiscoveryDataException.Validation("error.guide.filter_invalid")
    }
}
