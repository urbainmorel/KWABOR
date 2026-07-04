package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingDetail
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataCatalogRepositoryTest {
    @Test
    fun listCities_mapsDtos() = runTest {
        val repository = DataCatalogRepository(FakeCatalogDataSource())

        val result = repository.listCities()

        val cities = assertIs<DomainResult.Success<List<City>>>(result).value
        assertEquals(listOf("Cotonou", "Ouidah"), cities.map { city -> city.name })
    }

    @Test
    fun listListings_mapsPageAndNextOffset() = runTest {
        val dataSource = FakeCatalogDataSource(
            listings = listOf(
                listingSummaryDto(id = "listing-1"),
                listingSummaryDto(id = "listing-2"),
            ),
        )
        val repository = DataCatalogRepository(dataSource)

        val result = repository.listListings(
            filters = ListingFilters(cityId = "cotonou"),
            page = PageRequest(offset = 10, limit = 2),
        )

        val success = assertIs<DomainResult.Success<PageResult<ListingSummary>>>(result)
        assertEquals(2, success.value.items.size)
        assertEquals(12, success.value.nextOffset)
        assertEquals("cotonou", dataSource.lastListingFilters?.cityId)
    }

    @Test
    fun searchListings_delegatesQueryAndMapsResults() = runTest {
        val dataSource = FakeCatalogDataSource()
        val repository = DataCatalogRepository(dataSource)

        val result = repository.searchListings(
            query = ListingSearchQuery(text = "restaurant"),
            page = PageRequest(limit = 1),
        )

        val success = assertIs<DomainResult.Success<PageResult<ListingSummary>>>(result)
        assertEquals("listing-1", success.value.items.first().id)
        assertEquals("restaurant", dataSource.lastSearchQuery?.text)
    }

    @Test
    fun getListingDetail_mapsDetailAndMedia() = runTest {
        val repository = DataCatalogRepository(FakeCatalogDataSource())

        val result = repository.getListingDetail("listing-1")

        val detail = assertIs<DomainResult.Success<ListingDetail>>(result).value
        assertEquals("restaurant-kwabor", detail.slug)
        assertEquals("https://cdn.kwabor.test/cover.jpg", detail.summary.coverImageUrl)
    }

    @Test
    fun getListingDetail_mapsMissingRowToNotFound() = runTest {
        val repository = DataCatalogRepository(
            FakeCatalogDataSource(throwOnGetDetail = true),
        )

        val result = repository.getListingDetail("missing")

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.NotFound>(failure.error)
    }

    @Test
    fun invalidDto_mapsToUnexpectedFailure() = runTest {
        val repository = DataCatalogRepository(
            FakeCatalogDataSource(
                listings = listOf(listingSummaryDto(type = "invalid")),
            ),
        )

        val result = repository.listListings(ListingFilters(), PageRequest(limit = 1))

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Unexpected>(failure.error)
    }
}

private class FakeCatalogDataSource(
    private val listings: List<ListingSummaryDto> = listOf(listingSummaryDto()),
    private val throwOnGetDetail: Boolean = false,
) : CatalogDataSource {
    var lastListingFilters: ListingFilters? = null
        private set
    var lastSearchQuery: ListingSearchQuery? = null
        private set

    override suspend fun listCities(): List<CityDto> = listOf(
        CityDto(id = "cotonou", name = "Cotonou"),
        CityDto(id = "ouidah", name = "Ouidah"),
    )

    override suspend fun listCategories(): List<CategoryDto> = listOf(
        CategoryDto(
            id = "restaurants",
            listingType = "etablissement",
            nameKey = "category.restaurants",
            defaultListingClass = "commercial",
        ),
    )

    override suspend fun listListings(filters: ListingFilters, page: PageRequest): List<ListingSummaryDto> {
        lastListingFilters = filters
        return listings
    }

    override suspend fun searchListings(query: ListingSearchQuery, page: PageRequest): List<ListingSummaryDto> {
        lastSearchQuery = query
        return listings
    }

    override suspend fun getListingDetail(listingId: String): ListingDetailDto {
        if (throwOnGetDetail) {
            throw CatalogDataException.NotFound()
        }

        return ListingDetailDto(
            listing = listingDto(id = listingId),
            media = listOf(
                ListingMediaDto(
                    url = "https://cdn.kwabor.test/cover.jpg",
                    alt = "Photo principale",
                    displayOrder = 0,
                    isCover = true,
                ),
            ),
        )
    }
}

private fun listingSummaryDto(id: String = "listing-1", type: String = "etablissement"): ListingSummaryDto =
    ListingSummaryDto(
        listing = listingDto(id = id, type = type),
        coverImageUrl = "https://cdn.kwabor.test/cover.jpg",
    )
