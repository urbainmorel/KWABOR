package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingDetail
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
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

    @Test
    fun getListingViewerInteraction_mapsDto() = runTest {
        val repository = DataCatalogRepository(
            FakeCatalogDataSource(
                interaction = listingViewerInteractionDto(
                    likedByCurrentUser = true,
                    favoritedByCurrentUser = false,
                    likesCount = 7,
                ),
            ),
        )

        val result = repository.getListingViewerInteraction("listing-1")

        val interaction = assertIs<DomainResult.Success<ListingViewerInteraction>>(result).value
        assertEquals(true, interaction.likedByViewer)
        assertEquals(false, interaction.favoritedByViewer)
        assertEquals(7, interaction.likesCount)
    }

    @Test
    fun listListingViewerInteractions_returnsEmptyWithoutDataCall() = runTest {
        val dataSource = FakeCatalogDataSource()
        val repository = DataCatalogRepository(dataSource)

        val result = repository.listListingViewerInteractions(emptyList())

        val success = assertIs<DomainResult.Success<List<ListingViewerInteraction>>>(result)
        assertEquals(emptyList(), success.value)
        assertEquals(0, dataSource.interactionCallCount)
    }

    @Test
    fun listListingViewerInteractions_trimsAndDeduplicatesIds() = runTest {
        val dataSource = FakeCatalogDataSource()
        val repository = DataCatalogRepository(dataSource)

        val result = repository.listListingViewerInteractions(listOf(" listing-1 ", "listing-1", "listing-2"))

        val success = assertIs<DomainResult.Success<List<ListingViewerInteraction>>>(result)
        assertEquals(listOf("listing-1", "listing-2"), dataSource.lastInteractionBatchIds)
        assertEquals(listOf("listing-1", "listing-2"), success.value.map { item -> item.listingId })
    }

    @Test
    fun likeListing_delegatesAndMapsState() = runTest {
        val dataSource = FakeCatalogDataSource(
            interaction = listingViewerInteractionDto(likedByCurrentUser = true, likesCount = 13),
        )
        val repository = DataCatalogRepository(dataSource)

        val result = repository.likeListing("listing-1")

        val interaction = assertIs<DomainResult.Success<ListingViewerInteraction>>(result).value
        assertEquals("listing-1", dataSource.lastInteractionListingId)
        assertEquals(true, interaction.likedByViewer)
        assertEquals(13, interaction.likesCount)
    }

    @Test
    fun favoriteListing_delegatesAndMapsState() = runTest {
        val dataSource = FakeCatalogDataSource(
            interaction = listingViewerInteractionDto(favoritedByCurrentUser = true),
        )
        val repository = DataCatalogRepository(dataSource)

        val result = repository.favoriteListing("listing-1")

        val interaction = assertIs<DomainResult.Success<ListingViewerInteraction>>(result).value
        assertEquals("listing-1", dataSource.lastInteractionListingId)
        assertEquals(true, interaction.favoritedByViewer)
    }

    @Test
    fun blankListingInteractionId_mapsToValidationFailure() = runTest {
        val repository = DataCatalogRepository(FakeCatalogDataSource())

        val result = repository.likeListing(" ")

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
    }

    @Test
    fun interactionAuthRequired_mapsToFailure() = runTest {
        val repository = DataCatalogRepository(
            FakeCatalogDataSource(interactionException = CatalogDataException.AuthenticationRequired()),
        )

        val result = repository.favoriteListing("listing-1")

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.AuthenticationRequired>(failure.error)
    }
}

private class FakeCatalogDataSource(
    private val listings: List<ListingSummaryDto> = listOf(listingSummaryDto()),
    private val throwOnGetDetail: Boolean = false,
    private val interaction: ListingViewerInteractionDto = listingViewerInteractionDto(),
    private val interactionException: CatalogDataException? = null,
) : CatalogDataSource {
    var lastListingFilters: ListingFilters? = null
        private set
    var lastSearchQuery: ListingSearchQuery? = null
        private set
    var lastInteractionListingId: String? = null
        private set
    var lastInteractionBatchIds: List<String>? = null
        private set
    var interactionCallCount: Int = 0
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

    override suspend fun getListingViewerInteraction(listingId: String): ListingViewerInteractionDto =
        runInteraction(listingId)

    override suspend fun listListingViewerInteractions(listingIds: List<String>): List<ListingViewerInteractionDto> {
        interactionException?.let { exception -> throw exception }
        interactionCallCount += 1
        lastInteractionBatchIds = listingIds
        return listingIds.map { listingId -> interaction.copy(listingId = listingId) }
    }

    override suspend fun likeListing(listingId: String): ListingViewerInteractionDto = runInteraction(listingId)

    override suspend fun unlikeListing(listingId: String): ListingViewerInteractionDto = runInteraction(listingId)

    override suspend fun favoriteListing(listingId: String): ListingViewerInteractionDto = runInteraction(listingId)

    override suspend fun unfavoriteListing(listingId: String): ListingViewerInteractionDto = runInteraction(listingId)

    private fun runInteraction(listingId: String): ListingViewerInteractionDto {
        interactionException?.let { exception -> throw exception }
        interactionCallCount += 1
        lastInteractionListingId = listingId
        return interaction.copy(listingId = listingId)
    }
}

private fun listingSummaryDto(id: String = "listing-1", type: String = "etablissement"): ListingSummaryDto =
    ListingSummaryDto(
        listing = listingDto(id = id, type = type),
        coverImageUrl = "https://cdn.kwabor.test/cover.jpg",
    )
