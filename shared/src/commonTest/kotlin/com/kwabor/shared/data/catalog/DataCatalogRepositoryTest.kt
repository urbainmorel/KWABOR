package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.ListingLikeMutation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

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
                    listingSummaryDto(id = CATALOG_LISTING_ID_ONE),
                    listingSummaryDto(id = CATALOG_LISTING_ID_TWO),
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
        assertEquals(CATALOG_LISTING_ID_ONE, firstListing.id)
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
        assertEquals(CATALOG_LISTING_ID_ONE, success.value.items.first().id)
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
    fun listingSearchQuery_rejectsUnpublishedScopeBeforeCallingTheDataLayer() {
        assertFailsWith<IllegalArgumentException> {
            ListingSearchQuery(
                text = "restaurant",
                filters = ListingFilters(onlyPublished = false),
            )
        }
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
    fun getListingDetail_mapsTypedAtomicPayload() = runTest {
        val repository = DataCatalogRepository(FakeCatalogDataSource())

        val result = repository.getListingDetail(CATALOG_LISTING_ID_ONE)

        val detail = assertIs<DomainResult.Success<CatalogDetail.Establishment.Food>>(result).value
        assertEquals("restaurant-kwabor", detail.common.slug)
        assertEquals("https://cdn.kwabor.test/cover.jpg", detail.common.media.single().url)
        assertEquals(listOf("beninoise"), detail.cuisines)
    }

    @Test
    fun getListingDetail_reopensPreviouslyViewedDemoAfterRepositoryRecreationAndNetworkLoss() = runTest {
        val dao = TestDemoCatalogDetailDao()
        val dataSource = FakeCatalogDataSource(detailTags = DEMO_DETAIL_TAGS)
        val first = DataCatalogRepository(dataSource, demoDetailCache(dao))
            .getListingDetail(CATALOG_LISTING_ID_ONE)
        assertIs<DomainResult.Success<CatalogDetail>>(first)
        dataSource.detailException = CatalogDataException.NetworkUnavailable()

        val reopened = DataCatalogRepository(dataSource, demoDetailCache(dao))
        assertEquals(first, reopened.getListingDetail(CATALOG_LISTING_ID_ONE.uppercase()))
        assertIs<DomainError.NetworkUnavailable>(
            assertIs<DomainResult.Failure>(reopened.getListingDetail(CATALOG_LISTING_ID_TWO)).error,
        )
    }

    @Test
    fun getListingDetail_cacheDoesNotReuseExpiredWithdrawnOrForeignDemo() = runTest {
        val dao = TestDemoCatalogDetailDao()
        val dataSource = FakeCatalogDataSource(detailTags = DEMO_DETAIL_TAGS)
        var now = 100.hours.inWholeMilliseconds
        val cache = demoDetailCache(dao, now = { now })
        val repository = DataCatalogRepository(dataSource, cache)
        assertIs<DomainResult.Success<CatalogDetail>>(repository.getListingDetail(CATALOG_LISTING_ID_ONE))
        assertNull(demoDetailCache(dao, projectUrl = "https://other.kwabor.test").read(CATALOG_LISTING_ID_ONE))

        now += 25.hours.inWholeMilliseconds
        assertNull(cache.read(CATALOG_LISTING_ID_ONE))
        assertIs<DomainResult.Success<CatalogDetail>>(repository.getListingDetail(CATALOG_LISTING_ID_ONE))
        dataSource.detailException = CatalogDataException.NotFound()
        assertIs<DomainError.NotFound>(
            assertIs<DomainResult.Failure>(repository.getListingDetail(CATALOG_LISTING_ID_ONE)).error,
        )
        assertNull(cache.read(CATALOG_LISTING_ID_ONE))
    }

    @Test
    fun getListingDetail_doesNotCacheNonDemoPayloads() = runTest {
        val cache = demoDetailCache(TestDemoCatalogDetailDao())
        val repository = DataCatalogRepository(FakeCatalogDataSource(), cache)
        assertIs<DomainResult.Success<CatalogDetail>>(repository.getListingDetail(CATALOG_LISTING_ID_ONE))
        assertNull(cache.read(CATALOG_LISTING_ID_ONE))
    }

    @Test
    fun getListingDetail_mapsMissingRowToNotFound() = runTest {
        val repository = DataCatalogRepository(
            FakeCatalogDataSource(throwOnGetDetail = true),
        )

        val result = repository.getListingDetail(CATALOG_LISTING_ID_ONE)

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.NotFound>(failure.error)
    }

    @Test
    fun getListingDetail_rejectsMalformedIdWithoutCallingDataSource() = runTest {
        val dataSource = FakeCatalogDataSource()
        val repository = DataCatalogRepository(dataSource)

        val result = repository.getListingDetail("not-a-uuid")

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(0, dataSource.detailCallCount)
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

        val result = repository.getListingViewerInteraction(CATALOG_LISTING_ID_ONE)

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
    fun listListingViewerInteractions_withoutSessionSkipsDataSourceCall() = runTest {
        val dataSource = FakeCatalogDataSource()
        val repository = DataCatalogRepository(
            SessionAwareCatalogDataSource(
                delegate = dataSource,
                sessionState = CatalogSessionState { false },
            ),
        )

        val result = repository.listListingViewerInteractions(
            listOf(CATALOG_LISTING_ID_ONE, CATALOG_LISTING_ID_TWO),
        )

        val success = assertIs<DomainResult.Success<List<ListingViewerInteraction>>>(result)
        assertEquals(emptyList(), success.value)
        assertEquals(0, dataSource.interactionCallCount)
        assertEquals(null, dataSource.lastInteractionBatchIds)
    }

    @Test
    fun listListingViewerInteractions_trimsAndDeduplicatesIds() = runTest {
        val dataSource = FakeCatalogDataSource()
        val repository = DataCatalogRepository(
            SessionAwareCatalogDataSource(
                delegate = dataSource,
                sessionState = CatalogSessionState { true },
            ),
        )

        val result = repository.listListingViewerInteractions(
            listOf(" $CATALOG_LISTING_ID_ONE ", CATALOG_LISTING_ID_ONE, CATALOG_LISTING_ID_TWO),
        )

        val success = assertIs<DomainResult.Success<List<ListingViewerInteraction>>>(result)
        assertEquals(listOf(CATALOG_LISTING_ID_ONE, CATALOG_LISTING_ID_TWO), dataSource.lastInteractionBatchIds)
        assertEquals(
            listOf(CATALOG_LISTING_ID_ONE, CATALOG_LISTING_ID_TWO),
            success.value.map { item -> item.listingId },
        )
        assertEquals(1, dataSource.interactionCallCount)
    }

    @Test
    fun listListingViewerInteractions_rejectsMalformedIdWithoutCallingDataSource() = runTest {
        val dataSource = FakeCatalogDataSource()
        val repository = DataCatalogRepository(dataSource)

        val result = repository.listListingViewerInteractions(
            listOf(CATALOG_LISTING_ID_ONE, "not-a-uuid", CATALOG_LISTING_ID_TWO),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
        assertEquals(0, dataSource.interactionCallCount)
    }

    @Test
    fun likeListing_delegatesAndMapsState() = runTest {
        val dataSource = FakeCatalogDataSource(
            interaction = listingViewerInteractionDto(likedByCurrentUser = true, likesCount = 13),
        )
        val repository = DataCatalogRepository(dataSource)

        val result = repository.likeListing(CATALOG_LISTING_ID_ONE)

        val interaction = assertIs<DomainResult.Success<ListingViewerInteraction>>(result).value
        assertEquals(CATALOG_LISTING_ID_ONE, dataSource.lastInteractionListingId)
        assertEquals(true, interaction.likedByViewer)
        assertEquals(13, interaction.likesCount)
    }

    @Test
    fun setListingLike_canonicalizesTargetAndMapsVersionedConfirmation() = runTest {
        val dataSource = FakeCatalogDataSource(
            interaction = listingViewerInteractionDto(likesCount = 14),
        )
        val repository = DataCatalogRepository(dataSource)

        val result = repository.setListingLike(
            expectedAccountId = CATALOG_ACCOUNT_ID.uppercase(),
            listingId = CATALOG_LISTING_ID_ONE.uppercase(),
            liked = true,
        )

        val mutation = assertIs<DomainResult.Success<ListingLikeMutation>>(result).value
        assertEquals(CATALOG_ACCOUNT_ID, dataSource.lastExpectedAccountId)
        assertEquals(CATALOG_LISTING_ID_ONE, dataSource.lastInteractionListingId)
        assertEquals(CATALOG_LISTING_ID_ONE, mutation.listingId)
        assertEquals(true, mutation.liked)
        assertEquals(14, mutation.likesCount)
        assertEquals(1_786_305_600_000L, mutation.mutatedAtEpochMilliseconds)
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

        val result = repository.likeListing(CATALOG_LISTING_ID_ONE)

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.AuthenticationRequired>(failure.error)
    }
}

private fun demoDetailCache(
    dao: DemoCatalogDetailDao,
    projectUrl: String = "https://staging.kwabor.test",
    now: () -> Long = { 100.hours.inWholeMilliseconds },
): DemoCatalogDetailCache = DemoCatalogDetailCache(
    dao = dao,
    projectUrl = projectUrl,
    clock = object : ClockProvider {
        override fun nowEpochMilliseconds(): Long = now()
    },
)

private class TestDemoCatalogDetailDao : DemoCatalogDetailDao {
    private val records = mutableMapOf<Pair<String, String>, DemoCatalogDetailRecord>()

    override suspend fun find(projectUrl: String, listingId: String): DemoCatalogDetailRecord? =
        records[projectUrl to listingId]

    override suspend fun delete(projectUrl: String, listingId: String) {
        records.remove(projectUrl to listingId)
    }

    override suspend fun upsert(record: DemoCatalogDetailRecord) {
        records[record.projectUrl to record.listingId] = record
    }

    override suspend fun prune(oldest: Long) {
        records.entries.removeAll { entry -> entry.value.cachedAt < oldest }
    }
}

private val DEMO_DETAIL_TAGS = listOf("demo-kwabor", "contenu-fictif")

private class FakeCatalogDataSource(
    private val listingsPage: ListingSummaryPageDto = ListingSummaryPageDto(
        items = listOf(listingSummaryDto()),
        nextCursor = null,
    ),
    private val listingsException: CatalogDataException? = null,
    private val throwOnGetDetail: Boolean = false,
    private val detailTags: List<String> = catalogDetailPayloadDto().tags,
    private val interaction: ListingViewerInteractionDto = listingViewerInteractionDto(),
    private val interactionException: CatalogDataException? = null,
) : CatalogDataSource {
    var detailException: CatalogDataException? = null
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
    var detailCallCount: Int = 0
        private set
    var lastInteractionListingId: String? = null
        private set
    var lastExpectedAccountId: String? = null
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

    override suspend fun suggestListings(query: ListingSearchQuery): List<ListingSuggestionDto> = emptyList()

    override suspend fun searchListings(query: ListingSearchQuery, page: ListingPageRequest): ListingSummaryPageDto {
        listingsException?.let { exception -> throw exception }
        searchCallCount += 1
        lastSearchQuery = query
        lastSearchPage = page
        return listingsPage
    }

    override suspend fun getListingDetail(listingId: String): CatalogDetailPayloadDto {
        detailCallCount += 1
        detailException?.let { exception -> throw exception }
        if (throwOnGetDetail) {
            throw CatalogDataException.NotFound()
        }

        return catalogDetailPayloadDto().copy(id = listingId, tags = detailTags)
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

    override suspend fun setListingLike(
        expectedAccountId: String,
        listingId: String,
        liked: Boolean,
    ): ListingLikeMutationDto {
        interactionException?.let { exception -> throw exception }
        interactionCallCount += 1
        lastExpectedAccountId = expectedAccountId
        lastInteractionListingId = listingId
        return ListingLikeMutationDto(
            listingId = listingId,
            liked = liked,
            likesCount = interaction.likesCount,
            mutatedAt = "2026-08-09T20:00:00Z",
        )
    }

    private fun runInteraction(listingId: String): ListingViewerInteractionDto {
        interactionException?.let { exception -> throw exception }
        interactionCallCount += 1
        lastInteractionListingId = listingId
        return interaction.copy(listingId = listingId)
    }
}

private const val CATALOG_ACCOUNT_ID = "99999999-9999-4999-8999-999999999999"

private fun listingSummaryDto(id: String = CATALOG_LISTING_ID_ONE, type: String = "etablissement"): ListingSummaryDto =
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
