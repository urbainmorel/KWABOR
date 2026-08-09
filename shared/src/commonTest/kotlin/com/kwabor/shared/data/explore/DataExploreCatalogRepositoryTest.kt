package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreCatalogPage
import com.kwabor.shared.domain.explore.ExploreCatalogRequest
import com.kwabor.shared.domain.explore.ExploreSort
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataExploreCatalogRepositoryTest {
    @Test
    fun listCatalog_mapsTheEnrichedVersionedPage() = runTest {
        val dataSource = FakeExploreV2DataSource()
        val repository = DataExploreCatalogRepository(dataSource)
        val request = ExploreCatalogRequest(
            listingType = ListingType.Place,
            sort = ExploreSort.Popularity,
        )

        val result = repository.listCatalog(request)

        val page = assertIs<DomainResult.Success<ExploreCatalogPage>>(result).value
        assertEquals(request, dataSource.lastRequest)
        assertEquals(ID, page.items.single().id)
        assertEquals("Alt accessible", page.items.single().coverImageAlt)
        assertEquals(42L, page.items.single().viewsCount)
        assertEquals(SNAPSHOT_MICROSECONDS, page.snapshotAtEpochMicroseconds)
    }

    @Test
    fun listCatalog_mapsTypedDataFailure() = runTest {
        val repository = DataExploreCatalogRepository(
            FakeExploreV2DataSource(exception = ExploreCatalogDataException.NetworkUnavailable()),
        )

        val result = repository.listCatalog(
            ExploreCatalogRequest(
                listingType = ListingType.Place,
                sort = ExploreSort.Popularity,
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.NetworkUnavailable>(failure.error)
    }
}

private class FakeExploreV2DataSource(
    private val exception: ExploreCatalogDataException? = null,
) : ExploreCatalogDataSource {
    var lastRequest: ExploreCatalogRequest? = null
        private set

    override suspend fun listCatalog(request: ExploreCatalogRequest): ExploreCatalogPageDto {
        exception?.let { error -> throw error }
        lastRequest = request
        return ExploreCatalogPageDto(
            items = listOf(
                ExploreCatalogRowDto(
                    id = ID,
                    type = "lieu",
                    listingClass = "patrimonial",
                    status = "publie",
                    name = "Lieu Kwabor",
                    cityId = "cotonou",
                    categoryId = "heritage-historique",
                    coverImageUrl = "https://cdn.kwabor.test/place.jpg",
                    coverImageAlt = "Alt accessible",
                    priceFromXof = null,
                    ratingAverage = 4.5,
                    viewsCount = 42,
                    likesCount = 7,
                    verified = true,
                    sponsoredUntil = null,
                    eventStartAt = null,
                    eventEndAt = null,
                    isEventEnded = false,
                    isSponsoredPlacement = false,
                    snapshotAt = "2026-08-09T15:00:00.123456Z",
                    rowCursor = "cursor-one",
                ),
            ),
            nextCursor = null,
            snapshotAtEpochMicroseconds = SNAPSHOT_MICROSECONDS,
        )
    }
}

private const val ID = "10000000-0000-4000-8000-000000000001"
private const val SNAPSHOT_MICROSECONDS = 1_786_287_600_123_456L
