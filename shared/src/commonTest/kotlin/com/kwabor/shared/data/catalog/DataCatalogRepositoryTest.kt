package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingDetail
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
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
    fun listListings_mapsPageAndPropagatesFiltersCursorAndLimit() = runTest {
        val request = ListingPageRequest(cursor = "cursor-current", limit = 2)
        val filters = ListingFilters(cityId = "cotonou", categoryId = "restaurants")
        val dataSource = FakeCatalogDataSource(
            listingsPage = ListingSummaryPageDto(
                items = listOf(
                    listingSummaryDto(id = "listing-1"),
                    listingSummaryDto(id = "listing-2"),
                ),
                nextCursor = "cursor-next-exact",
            ),
        )
        val repository = DataCatalogRepository(dataSource)

        val result = repository.listListings(
            filters = filters,
            page = request,
        )

        val success = assertIs<DomainResult.Success<ListingSummaryPage>>(result)
        assertEquals(2, success.value.items.size)
        val firstListing = success.value.items.first()
        assertEquals("listing-1", firstListing.id)
        assertEquals(ListingType.Establishment, firstListing.type)
        assertEquals(ListingClass.Commercial, firstListing.listingClass)
        assertEquals(ListingStatus.Published, firstListing.status)
        assertEquals("Restaurant Kwabor", firstListing.name)
        assertEquals("cotonou", firstListing.cityId)
        assertEquals("restaurants", firstListing.categoryId)
        assertEquals("https://cdn.kwabor.test/cover.jpg", firstListing.coverImageUrl)
        assertEquals(5_000L, firstListing.priceFromXof?.amount)
        assertEquals(4.5, firstListing.ratingAverage)
        assertEquals(12, firstListing.likesCount)
        assertEquals(true, firstListing.verified)
        assertEquals(null, firstListing.sponsoredUntilEpochMilliseconds)
        assertEquals(false, firstListing.isSponsoredPlacement)
        assertEquals("cursor-next-exact", success.value.nextCursor)
        assertEquals(filters, dataSource.lastListingFilters)
        assertEquals(request, dataSource.lastListingPage)
    }

    @Test
    fun searchListings_delegatesQueryAndCursorAndMapsExactNextCursor() = runTest {
        val request = ListingPageRequest(cursor = "search-current", limit = 7)
        val query = ListingSearchQuery(
            text = "restaurant",
            filters = ListingFilters(cityId = "cotonou", categoryId = "restaurants"),
        )
        val dataSource = FakeCatalogDataSource(
            listingsPage = ListingSummaryPageDto(
                items = listOf(listingSummaryDto()),
                nextCursor = "search-next-exact",
            ),
        )
        val repository = DataCatalogRepository(dataSource)

        val result = repository.searchListings(
            query = query,
            page = request,
        )

        val success = assertIs<DomainResult.Success<ListingSummaryPage>>(result)
        assertEquals("listing-1", success.value.items.first().id)
        assertEquals("search-next-exact", success.value.nextCursor)
        assertEquals(query, dataSource.lastSearchQuery)
        assertEquals(request, dataSource.lastSearchPage)
    }

    @Test
    fun listListings_rejectsUnpublishedScopeWithoutCallingDataSource() = runTest {
        val dataSource = FakeCatalogDataSource()
        val repository = DataCatalogRepository(dataSource)

        val result = repository.listListings(filters = ListingFilters(onlyPublished = false))

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(0, dataSource.listingCallCount)
    }

    @Test
    fun searchListings_rejectsUnpublishedScopeWithoutCallingDataSource() = runTest {
        val dataSource = FakeCatalogDataSource()
        val repository = DataCatalogRepository(dataSource)

        val result = repository.searchListings(
            query = ListingSearchQuery(
                text = "restaurant",
                filters = ListingFilters(onlyPublished = false),
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(0, dataSource.searchCallCount)
    }

    @Test
    fun listListings_mapsDataSourceError() = runTest {
        val repository = DataCatalogRepository(
            FakeCatalogDataSource(listingsException = CatalogDataException.NetworkUnavailable()),
        )

        val result = repository.listListings(filters = ListingFilters())

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.NetworkUnavailable>(failure.error)
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
                listingsPage = ListingSummaryPageDto(
                    items = listOf(listingSummaryDto(type = "invalid")),
                    nextCursor = null,
                ),
            ),
        )

        val result = repository.listListings(ListingFilters(), ListingPageRequest(limit = 1))

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
    private val listingsPage: ListingSummaryPageDto = ListingSummaryPageDto(
        items = listOf(listingSummaryDto()),
        nextCursor = null,
    ),
    private val listingsException: CatalogDataException? = null,
    private val throwOnGetDetail: Boolean = false,
    private val interaction: ListingViewerInteractionDto = listingViewerInteractionDto(),
    private val interactionException: CatalogDataException? = null,
) : CatalogDataSource {
    var lastListingFilters: ListingFilters? = null
        private set
    var lastSearchQuery: ListingSearchQuery? = null
        private set
    var lastListingPage: ListingPageRequest? = null
        private set
    var lastSearchPage: ListingPageRequest? = null
        private set
    var listingCallCount: Int = 0
        private set
    var searchCallCount: Int = 0
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

    override suspend fun listListings(filters: ListingFilters, page: ListingPageRequest): ListingSummaryPageDto {
        listingsException?.let { exception -> throw exception }
        listingCallCount += 1
        lastListingFilters = filters
        lastListingPage = page
        return listingsPage
    }

    override suspend fun searchListings(query: ListingSearchQuery, page: ListingPageRequest): ListingSummaryPageDto {
        listingsException?.let { exception -> throw exception }
        searchCallCount += 1
        lastSearchQuery = query
        lastSearchPage = page
        return listingsPage
    }

    override suspend fun getListingDetail(listingId: String): ListingDetailDto {
        if (throwOnGetDetail) {
            throw CatalogDataException.NotFound()
        }

        return ListingDetailDto(
            listing = listingDto(ListingDtoFixture(id = listingId)),
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
        id = id,
        type = type,
        listingClass = "commercial",
        status = "publie",
        name = "Restaurant Kwabor",
        cityId = "cotonou",
        categoryId = "restaurants",
        coverImageUrl = "https://cdn.kwabor.test/cover.jpg",
        priceFromXof = 5_000,
        ratingAverage = 4.5,
        likesCount = 12,
        verified = true,
        sponsoredUntil = null,
        isSponsoredPlacement = false,
        rowCursor = "cursor-$id",
    )
