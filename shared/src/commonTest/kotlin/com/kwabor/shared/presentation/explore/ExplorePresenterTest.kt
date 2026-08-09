package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import com.kwabor.shared.domain.explore.ExploreFeedRepository
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExplorePresenterTest {
    private val strings = stringsFor(AppLocale.French)
    private val clockProvider = FixedClockProvider(nowEpochMilliseconds = 1_000L)

    @Test
    fun load_mapsPublishedCatalogListingsToReadOnlyExploreState() = runSuspendTest {
        val repository = publishedListingRepository()
        val presenter = testPresenter(repository, clockProvider)

        val state = presenter.load(
            request = ExploreLoadRequest(
                selectedTab = ExploreTab.Places,
                selectedChipId = "heritage-historique",
            ),
            strings = strings,
        )

        assertFalse(state.isLoading)
        assertFalse(state.hasError)
        assertEquals(strings.currentCity, state.cityLabel)
        assertEquals(ExploreTab.Places, state.selectedTab)
        assertEquals("heritage-historique", state.selectedChipId)
        assertEquals(ListingType.Place, repository.lastFilters?.listingType)
        assertEquals("heritage-historique", repository.lastFilters?.categoryId)

        val listing = state.listings.single()
        assertEquals("ouidah-gate", listing.id)
        assertEquals("Porte du non-retour", listing.title)
        assertEquals("Ouidah", listing.cityLabel)
        assertEquals("https://example.invalid/cover.jpg", listing.coverImageUrl)
        assertEquals("4,7", listing.ratingLabel)
        assertTrue(listing.sponsored)
    }

    @Test
    fun load_keepsUnknownChipAsTabFilterOnly() = runSuspendTest {
        val repository = FakeCatalogRepository()
        val presenter = testPresenter(repository, clockProvider)

        presenter.load(
            request = ExploreLoadRequest(selectedTab = ExploreTab.Events, selectedChipId = "concerts"),
            strings = strings,
        )

        assertEquals(ListingType.Event, repository.lastFilters?.listingType)
        assertEquals(null, repository.lastFilters?.categoryId)
    }

    @Test
    fun load_returnsOfflineErrorStateForNetworkFailure() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(listingsError = DomainError.NetworkUnavailable()),
        )
        val presenter = testPresenter(repository, clockProvider)

        val state = presenter.load(request = ExploreLoadRequest(), strings = strings)

        assertTrue(state.hasError)
        assertTrue(state.isOffline)
        assertEquals(strings.offlineBanner, state.errorMessage)
        assertTrue(state.listings.isEmpty())
    }

    @Test
    fun load_usesServerSponsorSnapshotDespiteDeviceClockSkew() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(
                listings = listOf(
                    listingSummary(
                        ListingSummaryFixture(
                            sponsoredUntilEpochMilliseconds = 500L,
                            isSponsoredPlacement = false,
                        ),
                    ),
                ),
            ),
        )
        val presenter = testPresenter(repository, FixedClockProvider(nowEpochMilliseconds = 0L))

        val state = presenter.load(request = ExploreLoadRequest(), strings = strings)

        assertFalse(state.listings.single().sponsored)
    }

    @Test
    fun load_appliesViewerInteractionsWhenSessionAllowsIt() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(
                listings = listOf(listingSummary(ListingSummaryFixture(id = "listing-1", likesCount = 12))),
                viewerInteractions = listOf(
                    ListingViewerInteraction(
                        listingId = "listing-1",
                        likedByViewer = true,
                        favoritedByViewer = true,
                        likesCount = 13,
                    ),
                ),
            ),
        )
        val presenter = testPresenter(repository, clockProvider)

        val state = presenter.load(request = ExploreLoadRequest(), strings = strings)

        val listing = state.listings.single()
        assertTrue(listing.liked)
        assertTrue(listing.favorited)
        assertEquals(13, listing.likesCount)
        assertFalse(state.hasError)
    }

    @Test
    fun load_keepsListingsWhenViewerInteractionsRequireAuth() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(interactionError = DomainError.AuthenticationRequired()),
        )
        val presenter = testPresenter(repository, clockProvider)

        val state = presenter.load(request = ExploreLoadRequest(), strings = strings)

        assertFalse(state.hasError)
        assertEquals(1, state.listings.size)
        assertFalse(state.listings.single().liked)
        assertFalse(state.listings.single().favorited)
    }

    @Test
    fun toggleLike_updatesListingFromRepositoryInteraction() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(
                interactionResult = ListingViewerInteraction(
                    listingId = "listing-1",
                    likedByViewer = true,
                    favoritedByViewer = false,
                    likesCount = 13,
                ),
            ),
        )
        val presenter = testPresenter(repository, clockProvider)
        val state = stateWithListing(
            ExploreListingItem(
                id = "listing-1",
                title = "Listing test",
                cityLabel = "Cotonou",
                cityId = "cotonou",
                coverImageUrl = null,
                price = null,
                likesCount = 12,
            ),
        )

        val updatedState = presenter.toggleLike(state = state, listingId = "listing-1", strings = strings)

        assertEquals("like", repository.lastInteractionAction)
        assertTrue(updatedState.listings.single().liked)
        assertEquals(13, updatedState.listings.single().likesCount)
        assertFalse(updatedState.hasQueuedInteractions)
    }

    @Test
    fun toggleLike_whenAlreadyLikedDelegatesUnlike() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(
                interactionResult = ListingViewerInteraction(
                    listingId = "listing-1",
                    likedByViewer = false,
                    favoritedByViewer = false,
                    likesCount = 12,
                ),
            ),
        )
        val presenter = testPresenter(repository, clockProvider)
        val state = stateWithListing(
            ExploreListingItem(
                id = "listing-1",
                title = "Listing test",
                cityLabel = "Cotonou",
                coverImageUrl = null,
                price = null,
                liked = true,
                favorited = true,
                likesCount = 13,
            ),
        )

        val updatedState = presenter.toggleLike(state = state, listingId = "listing-1", strings = strings)

        assertEquals("unlike", repository.lastInteractionAction)
        assertFalse(updatedState.listings.single().liked)
        assertTrue(updatedState.listings.single().favorited)
        assertEquals(12, updatedState.listings.single().likesCount)
    }

    @Test
    fun toggleFavoriteUsesDedicatedRepositoryAndPreservesLikeState() = runSuspendTest {
        val catalogRepository = FakeCatalogRepository()
        val favoritesRepository = RecordingExploreFavoritesRepository()
        val presenter = testPresenter(
            repository = catalogRepository,
            clockProvider = clockProvider,
            favoritesRepository = favoritesRepository,
        )
        val state = stateWithListing(
            ExploreListingItem(
                id = "listing-1",
                title = "Listing test",
                cityLabel = "Cotonou",
                coverImageUrl = null,
                price = null,
                liked = true,
                likesCount = 42,
            ),
        )

        val updatedState = presenter.toggleFavorite(state, "listing-1", strings)

        assertEquals(listOf("listing-1" to true), favoritesRepository.mutations)
        assertTrue(updatedState.listings.single().favorited)
        assertTrue(updatedState.listings.single().liked)
        assertEquals(42, updatedState.listings.single().likesCount)
        assertEquals(null, catalogRepository.lastInteractionAction)
    }

    @Test
    fun toggleFavorite_authRequiredShowsSoftWallWithoutBlockingListings() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(interactionError = DomainError.AuthenticationRequired()),
        )
        val presenter = testPresenter(
            repository = repository,
            clockProvider = clockProvider,
            favoritesRepository = AuthenticationRequiredFavoritesRepository,
        )
        val state = stateWithListing(
            ExploreListingItem(
                id = "listing-1",
                title = "Listing test",
                cityLabel = "Cotonou",
                coverImageUrl = null,
                price = null,
                cityId = "cotonou",
            ),
        )

        val updatedState = presenter.toggleFavorite(state = state, listingId = "listing-1", strings = strings)

        assertFalse(updatedState.hasError)
        assertEquals(strings.signInRequiredForInteraction, updatedState.interactionMessage)
        assertEquals(
            PendingExploreAuthInteraction(
                listingId = "listing-1",
                kind = ExploreInteractionKind.Favorite,
                suggestedCityId = "cotonou",
            ),
            updatedState.pendingAuthInteraction,
        )
        assertFalse(updatedState.listings.single().favorited)
        assertFalse(updatedState.hasQueuedInteractions)
    }

    @Test
    fun toggleLike_networkFailureQueuesOptimisticInteraction() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(interactionError = DomainError.NetworkUnavailable()),
        )
        val presenter = testPresenter(repository, clockProvider)
        val state = stateWithListing(
            ExploreListingItem(
                id = "listing-1",
                title = "Listing test",
                cityLabel = "Cotonou",
                coverImageUrl = null,
                price = null,
                likesCount = 12,
            ),
        )

        val updatedState = presenter.toggleLike(state = state, listingId = "listing-1", strings = strings)

        assertTrue(updatedState.isOffline)
        assertEquals(strings.interactionQueuedOffline, updatedState.interactionMessage)
        assertTrue(updatedState.listings.single().liked)
        assertEquals(13, updatedState.listings.single().likesCount)
        assertEquals(
            QueuedExploreInteraction(
                listingId = "listing-1",
                kind = ExploreInteractionKind.Like,
                selected = true,
                queuedAtEpochMilliseconds = 1_000L,
            ),
            updatedState.queuedInteractions.single(),
        )
    }

    @Test
    fun toggleFavorite_networkFailureQueuesOptimisticInteraction() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(interactionError = DomainError.NetworkUnavailable()),
        )
        val presenter = testPresenter(
            repository = repository,
            clockProvider = clockProvider,
            favoritesRepository = OfflineFavoritesRepository,
        )
        val state = stateWithListing(
            ExploreListingItem(
                id = "listing-1",
                title = "Listing test",
                cityLabel = "Cotonou",
                coverImageUrl = null,
                price = null,
            ),
        )

        val updatedState = presenter.toggleFavorite(state = state, listingId = "listing-1", strings = strings)

        assertTrue(updatedState.isOffline)
        assertTrue(updatedState.listings.single().favorited)
        assertEquals(ExploreInteractionKind.Favorite, updatedState.queuedInteractions.single().kind)
        assertEquals(true, updatedState.queuedInteractions.single().selected)
    }

    @Test
    fun queuedInteraction_replacesPreviousActionForSameListingAndKind() = runSuspendTest {
        val repository = FakeCatalogRepository(
            FakeCatalogScenario(interactionError = DomainError.NetworkUnavailable()),
        )
        val presenter = testPresenter(repository, clockProvider)
        val state = stateWithListing(
            ExploreListingItem(
                id = "listing-1",
                title = "Listing test",
                cityLabel = "Cotonou",
                coverImageUrl = null,
                price = null,
                liked = true,
                likesCount = 1,
            ),
        ).copy(
            queuedInteractions = listOf(
                QueuedExploreInteraction(
                    listingId = "listing-1",
                    kind = ExploreInteractionKind.Like,
                    selected = true,
                    queuedAtEpochMilliseconds = 500L,
                ),
            ),
        )

        val updatedState = presenter.toggleLike(state = state, listingId = "listing-1", strings = strings)

        assertFalse(updatedState.listings.single().liked)
        assertEquals(0, updatedState.listings.single().likesCount)
        assertEquals(1, updatedState.queuedInteractions.size)
        assertEquals(false, updatedState.queuedInteractions.single().selected)
        assertEquals(1_000L, updatedState.queuedInteractions.single().queuedAtEpochMilliseconds)
    }
}

private fun publishedListingRepository(): FakeCatalogRepository = FakeCatalogRepository(
    FakeCatalogScenario(
        listings = listOf(
            listingSummary(
                ListingSummaryFixture(
                    id = "ouidah-gate",
                    name = "Porte du non-retour",
                    cityId = "ouidah",
                    coverImageUrl = "https://example.invalid/cover.jpg",
                    ratingAverage = 4.74,
                    sponsoredUntilEpochMilliseconds = 2_000L,
                    isSponsoredPlacement = true,
                ),
            ),
        ),
    ),
)

private data class FakeCatalogScenario(
    val cities: List<City> = listOf(
        City(id = "cotonou", name = "Cotonou"),
        City(id = "ouidah", name = "Ouidah"),
    ),
    val categories: List<Category> = listOf(
        Category(
            id = "heritage-historique",
            nameKey = "category.heritage.historique",
            listingType = ListingType.Place,
            defaultListingClass = ListingClass.Heritage,
        ),
        Category(
            id = "commercial-hotel",
            nameKey = "category.commercial.hotel",
            listingType = ListingType.Establishment,
            defaultListingClass = ListingClass.Commercial,
        ),
    ),
    val listings: List<ListingSummary> = listOf(listingSummary()),
    val listingsError: DomainError? = null,
    val viewerInteractions: List<ListingViewerInteraction> = emptyList(),
    val interactionResult: ListingViewerInteraction = ListingViewerInteraction(
        listingId = "listing-1",
        likedByViewer = false,
        favoritedByViewer = false,
        likesCount = 0,
    ),
    val interactionError: DomainError? = null,
)

private class FakeCatalogRepository(
    private val scenario: FakeCatalogScenario = FakeCatalogScenario(),
) : CatalogRepository {
    var lastFilters: ListingFilters? = null
    var lastInteractionAction: String? = null

    override suspend fun listCities(): DomainResult<List<City>> = DomainResult.Success(scenario.cities)

    override suspend fun listCategories(): DomainResult<List<Category>> = DomainResult.Success(scenario.categories)

    override suspend fun listListings(
        filters: ListingFilters,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> {
        lastFilters = filters
        return scenario.listingsError?.let { error -> DomainResult.Failure(error) }
            ?: DomainResult.Success(ListingSummaryPage(items = scenario.listings, nextCursor = null))
    }

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = DomainResult.Success(
        ListingSummaryPage(items = emptyList(), nextCursor = null),
    )

    override suspend fun getListingDetail(listingId: String): DomainResult<CatalogDetail> =
        DomainResult.Failure(DomainError.NotFound("error.catalog.not_found"))

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        scenario.interactionError?.let { error -> DomainResult.Failure(error) }
            ?: DomainResult.Success(scenario.interactionResult.copy(listingId = listingId))

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> =
        scenario.interactionError?.let { error -> DomainResult.Failure(error) }
            ?: DomainResult.Success(
                scenario.viewerInteractions.filter { interaction -> interaction.listingId in listingIds },
            )

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> {
        lastInteractionAction = "like"
        return getListingViewerInteraction(listingId)
    }

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> {
        lastInteractionAction = "unlike"
        return getListingViewerInteraction(listingId)
    }
}

private fun testPresenter(
    repository: CatalogRepository,
    clockProvider: ClockProvider,
    favoritesRepository: FavoritesRepository = RecordingExploreFavoritesRepository(),
): ExplorePresenter = ExplorePresenter(
    exploreFeedRepository = TestExploreFeedRepository(repository, clockProvider),
    catalogInteractionRepository = repository,
    favoritesRepository = favoritesRepository,
    appPreferencesRepository = null,
    clockProvider = clockProvider,
)

private class TestExploreFeedRepository(
    private val catalogRepository: CatalogRepository,
    private val clockProvider: ClockProvider,
) : ExploreFeedRepository {
    override suspend fun readCached(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot?> =
        DomainResult.Success(null)

    override suspend fun refresh(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot> =
        when (val result = catalogRepository.listCities()) {
            is DomainResult.Success -> loadCategories(query, result.value)
            is DomainResult.Failure -> result
        }

    private suspend fun loadCategories(
        query: ExploreFeedQuery,
        cities: List<City>,
    ): DomainResult<ExploreFeedSnapshot> = when (val result = catalogRepository.listCategories()) {
        is DomainResult.Success -> loadPage(query, cities, result.value)
        is DomainResult.Failure -> result
    }

    private suspend fun loadPage(
        query: ExploreFeedQuery,
        cities: List<City>,
        categories: List<Category>,
    ): DomainResult<ExploreFeedSnapshot> {
        val validationError = query.invalidReferenceError(cities, categories)
        return if (validationError != null) {
            DomainResult.Failure(validationError)
        } else {
            loadValidPage(query, cities, categories)
        }
    }

    private suspend fun loadValidPage(
        query: ExploreFeedQuery,
        cities: List<City>,
        categories: List<Category>,
    ): DomainResult<ExploreFeedSnapshot> = when (
        val result = catalogRepository.listListings(
            filters = query.filters,
            page = ListingPageRequest(limit = query.pageSize),
        )
    ) {
        is DomainResult.Success -> DomainResult.Success(
            ExploreFeedSnapshot(
                cities = cities,
                categories = categories,
                items = result.value.items,
                nextCursor = result.value.nextCursor,
                cachedAtEpochMilliseconds = clockProvider.nowEpochMilliseconds(),
                source = ExploreFeedSource.Network,
            ),
        )
        is DomainResult.Failure -> result
    }

    override suspend fun append(
        query: ExploreFeedQuery,
        currentSnapshot: ExploreFeedSnapshot,
    ): DomainResult<ExploreFeedSnapshot> = DomainResult.Failure(DomainError.Unexpected())
}

private fun ExploreFeedQuery.invalidReferenceError(cities: List<City>, categories: List<Category>): DomainError? =
    when {
        filters.cityId != null && cities.none { city -> city.id == filters.cityId } ->
            DomainError.Validation("error.explore.city_unavailable")
        filters.categoryId != null && categories.none { category -> category.id == filters.categoryId } ->
            DomainError.Validation("error.explore.category_unavailable")
        else -> null
    }

private class FixedClockProvider(private val nowEpochMilliseconds: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = nowEpochMilliseconds
}

private data class ListingSummaryFixture(
    val id: String = "listing-1",
    val name: String = "Listing test",
    val cityId: String = "cotonou",
    val coverImageUrl: String? = null,
    val ratingAverage: Double? = null,
    val likesCount: Int = 12,
    val sponsoredUntilEpochMilliseconds: Long? = null,
    val isSponsoredPlacement: Boolean? = null,
)

private fun listingSummary(fixture: ListingSummaryFixture = ListingSummaryFixture()): ListingSummary {
    val price = assertIs<DomainResult.Success<MoneyXof>>(MoneyXof.fromAmount(5_000)).value
    return ListingSummary(
        id = fixture.id,
        type = ListingType.Place,
        listingClass = ListingClass.Heritage,
        status = ListingStatus.Published,
        name = fixture.name,
        cityId = fixture.cityId,
        categoryId = "heritage-historique",
        coverImageUrl = fixture.coverImageUrl,
        priceFromXof = price,
        ratingAverage = fixture.ratingAverage,
        likesCount = fixture.likesCount,
        verified = true,
        sponsoredUntilEpochMilliseconds = fixture.sponsoredUntilEpochMilliseconds,
        isSponsoredPlacement = fixture.isSponsoredPlacement,
    )
}

private fun stateWithListing(listing: ExploreListingItem): ExploreUiState = ExploreUiState(
    cityLabel = "Cotonou",
    selectedTab = ExploreTab.Places,
    selectedChipId = null,
    chips = emptyList(),
    listings = listOf(listing),
)

private fun runSuspendTest(block: suspend () -> Unit) = kotlinx.coroutines.test.runTest {
    block()
}
