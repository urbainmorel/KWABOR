package com.kwabor.shared.data.guide

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.domain.guide.GuidePageRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataGuideDiscoveryRepositoryTest {
    @Test
    fun listServices_forwardsValidatedFiltersAndPage() = runTest {
        val dataSource = FakeGuideDiscoveryDataSource()
        val repository = DataGuideDiscoveryRepository(dataSource)
        val filters = GuideDiscoveryFilters(
            cityId = "ouidah",
            languageId = "francais",
            specialtyId = "histoire",
        )
        val page = GuidePageRequest(cursor = "opaque-cursor", limit = 12)

        val result = assertIs<DomainResult.Success<*>>(repository.listServices(filters, page))

        assertIs<com.kwabor.shared.domain.guide.GuideSummaryPage>(result.value)
        assertEquals(filters, dataSource.lastFilters)
        assertEquals(page, dataSource.lastPage)
        assertEquals(1, dataSource.serviceCallCount)
    }

    @Test
    fun listServices_rejectsInvalidFilterWithoutCallingTransport() = runTest {
        val dataSource = FakeGuideDiscoveryDataSource()
        val repository = DataGuideDiscoveryRepository(dataSource)

        val result = repository.listServices(
            filters = GuideDiscoveryFilters(cityId = "Ouidah"),
            page = GuidePageRequest(),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(0, dataSource.serviceCallCount)
    }

    @Test
    fun listServices_rejectsOversizedCursorWithoutCallingTransport() = runTest {
        val dataSource = FakeGuideDiscoveryDataSource()
        val repository = DataGuideDiscoveryRepository(dataSource)

        val result = repository.listServices(
            filters = GuideDiscoveryFilters(),
            page = GuidePageRequest(cursor = "a".repeat(4_097)),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(0, dataSource.serviceCallCount)
    }

    @Test
    fun repository_mapsExpectedTransportFailureToDomainFailure() = runTest {
        val dataSource = FakeGuideDiscoveryDataSource(
            failure = GuideDiscoveryDataException.NetworkUnavailable(),
        )
        val repository = DataGuideDiscoveryRepository(dataSource)

        val result = repository.listFacets()

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.NetworkUnavailable>(failure.error)
    }
}

private class FakeGuideDiscoveryDataSource(
    private val failure: GuideDiscoveryDataException? = null,
) : GuideDiscoveryDataSource {
    var lastFilters: GuideDiscoveryFilters? = null
        private set
    var lastPage: GuidePageRequest? = null
        private set
    var serviceCallCount: Int = 0
        private set

    override suspend fun listFacets(): List<GuideFacetRowDto> {
        failure?.let { exception -> throw exception }
        return listOf(validGuideFacetRow())
    }

    override suspend fun listServices(filters: GuideDiscoveryFilters, page: GuidePageRequest): GuideSummaryPageDto {
        failure?.let { exception -> throw exception }
        serviceCallCount += 1
        lastFilters = filters
        lastPage = page
        return GuideSummaryPageDto(items = listOf(validGuideSummaryRow()), nextCursor = null)
    }
}
