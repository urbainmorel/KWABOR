package com.kwabor.shared.domain.guide

import com.kwabor.shared.domain.core.DomainResult

interface GuideDiscoveryRepository {
    suspend fun listFacets(): DomainResult<List<GuideFacet>>

    suspend fun listServices(
        filters: GuideDiscoveryFilters = GuideDiscoveryFilters(),
        page: GuidePageRequest = GuidePageRequest(),
    ): DomainResult<GuideSummaryPage>
}
