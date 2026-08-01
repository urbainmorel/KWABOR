package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.CatalogInteractionRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import com.kwabor.shared.domain.explore.ExploreFeedRepository
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.preferences.AppPreferences
import com.kwabor.shared.domain.preferences.AppPreferencesRepository
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExploreFeedPresenterTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun cachedWall_isRenderedWithDynamicFiltersBeforeNetworkRefresh() = runTest {
        val feedRepository = ScriptedExploreFeedRepository(cachedResult = DomainResult.Success(snapshot()))
        val presenter = presenter(feedRepository)
        val prepared = presenter.prepareInitialState(ExploreLoadRequest(), strings)

        val state = presenter.loadCached(prepared, strings)

        assertEquals(listOf("heritage-historique"), state.chips.map(ExploreChip::id))
        assertEquals(listOf("listing-1"), state.listings.map(ExploreListingItem::id))
        assertEquals("cursor-1", state.nextCursor)
        assertEquals("Cotonou", state.cityLabel)
        assertNotNull(state.feedSnapshot)
    }

    @Test
    fun failedRefresh_keepsCachedWallAndReportsOfflineState() = runTest {
        val feedRepository = ScriptedExploreFeedRepository(
            cachedResult = DomainResult.Success(snapshot()),
            refreshResult = DomainResult.Failure(DomainError.NetworkUnavailable()),
        )
        val presenter = presenter(feedRepository)
        val cachedState = presenter.loadCached(
            presenter.prepareInitialState(ExploreLoadRequest(), strings),
            strings,
        )

        val state = presenter.refresh(cachedState.copy(isRefreshing = true), strings)

        assertEquals(listOf("listing-1"), state.listings.map(ExploreListingItem::id))
        assertTrue(state.isOffline)
        assertFalse(state.isRefreshing)
        assertEquals(strings.exploreRefreshError, state.refreshMessage)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun append_replacesWallWithDeduplicatedRepositorySnapshot() = runTest {
        val initialSnapshot = snapshot().copy(source = ExploreFeedSource.Network)
        val appendedSnapshot = initialSnapshot.copy(
            items = listOf(listing("listing-1"), listing("listing-2")),
            nextCursor = null,
            cachedAtEpochMilliseconds = 2_000,
            source = ExploreFeedSource.Network,
        )
        val feedRepository = ScriptedExploreFeedRepository(
            cachedResult = DomainResult.Success(initialSnapshot),
            appendResult = DomainResult.Success(appendedSnapshot),
        )
        val presenter = presenter(feedRepository)
        val cachedState = presenter.loadCached(
            presenter.prepareInitialState(ExploreLoadRequest(), strings),
            strings,
        )

        val state = presenter.append(cachedState.copy(isAppending = true), strings)

        assertEquals(listOf("listing-1", "listing-2"), state.listings.map(ExploreListingItem::id))
        assertEquals(null, state.nextCursor)
        assertFalse(state.isAppending)
        assertEquals(null, state.appendErrorMessage)
    }

    @Test
    fun append_requiresSuccessfulNetworkRevalidation() = runTest {
        val feedRepository = ScriptedExploreFeedRepository(
            cachedResult = DomainResult.Success(snapshot()),
            appendResult = DomainResult.Success(snapshot().copy(source = ExploreFeedSource.Network)),
        )
        val presenter = presenter(feedRepository)
        val cachedState = presenter.loadCached(
            presenter.prepareInitialState(ExploreLoadRequest(), strings),
            strings,
        )

        val state = presenter.append(cachedState.copy(isAppending = true), strings)

        assertEquals(0, feedRepository.appendCalls)
        assertFalse(state.isAppending)
        assertEquals(strings.exploreLoadMoreError, state.appendErrorMessage)
    }

    @Test
    fun append_loadsViewerInteractionsOnlyForNewListings() = runTest {
        val initialSnapshot = snapshot().copy(source = ExploreFeedSource.Network)
        val interactionRepository = RecordingInteractionRepository()
        val presenter = presenter(
            feedRepository = ScriptedExploreFeedRepository(
                cachedResult = DomainResult.Success(initialSnapshot),
                appendResult = DomainResult.Success(
                    initialSnapshot.copy(
                        items = listOf(listing("listing-1"), listing("listing-2")),
                        nextCursor = null,
                        source = ExploreFeedSource.Network,
                    ),
                ),
            ),
            interactionRepository = interactionRepository,
        )
        val current = presenter.loadCached(
            presenter.prepareInitialState(ExploreLoadRequest(), strings),
            strings,
        )

        presenter.append(current.copy(isAppending = true), strings)

        assertEquals(listOf(listOf("listing-2")), interactionRepository.requestedListingIds)
    }

    @Test
    fun citySelection_isPersistedAndUpdatesTheAppliedContext() = runTest {
        val preferences = RecordingPreferencesRepository()
        val presenter = presenter(
            feedRepository = ScriptedExploreFeedRepository(),
            preferencesRepository = preferences,
        )
        val state = initialExploreUiState(strings).copy(
            selectedCityId = "cotonou",
            availableCities = listOf(
                ExploreCityOption("cotonou", "Cotonou"),
                ExploreCityOption("ouidah", "Ouidah"),
            ),
        )

        val updated = presenter.selectCity(state, "ouidah", strings)

        assertEquals("ouidah", preferences.lastCityId)
        assertEquals("ouidah", updated.selectedCityId)
        assertEquals("Ouidah", updated.cityLabel)
        assertFalse(updated.isCitySelectorOpen)
    }
}

private fun presenter(
    feedRepository: ExploreFeedRepository,
    preferencesRepository: AppPreferencesRepository? = null,
    interactionRepository: CatalogInteractionRepository = EmptyInteractionRepository,
): ExplorePresenter = ExplorePresenter(
    exploreFeedRepository = feedRepository,
    catalogInteractionRepository = interactionRepository,
    appPreferencesRepository = preferencesRepository,
    clockProvider = TestClock,
)

private class ScriptedExploreFeedRepository(
    private val cachedResult: DomainResult<ExploreFeedSnapshot?> = DomainResult.Success(null),
    private val refreshResult: DomainResult<ExploreFeedSnapshot> = DomainResult.Success(snapshot()),
    private val appendResult: DomainResult<ExploreFeedSnapshot> = DomainResult.Failure(DomainError.Unexpected()),
) : ExploreFeedRepository {
    var appendCalls: Int = 0
        private set

    override suspend fun readCached(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot?> = cachedResult

    override suspend fun refresh(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot> = refreshResult

    override suspend fun append(
        query: ExploreFeedQuery,
        currentSnapshot: ExploreFeedSnapshot,
    ): DomainResult<ExploreFeedSnapshot> {
        appendCalls += 1
        return appendResult
    }
}

private class RecordingInteractionRepository : CatalogInteractionRepository by EmptyInteractionRepository {
    val requestedListingIds = mutableListOf<List<String>>()

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> {
        requestedListingIds += listingIds
        return DomainResult.Success(emptyList())
    }
}

private object EmptyInteractionRepository : CatalogInteractionRepository {
    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        DomainResult.Failure(DomainError.AuthenticationRequired())

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = DomainResult.Success(emptyList())

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        DomainResult.Failure(DomainError.AuthenticationRequired())

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        DomainResult.Failure(DomainError.AuthenticationRequired())

    override suspend fun favoriteListing(listingId: String): DomainResult<ListingViewerInteraction> =
        DomainResult.Failure(DomainError.AuthenticationRequired())

    override suspend fun unfavoriteListing(listingId: String): DomainResult<ListingViewerInteraction> =
        DomainResult.Failure(DomainError.AuthenticationRequired())
}

private class RecordingPreferencesRepository : AppPreferencesRepository {
    var lastCityId: String? = null
        private set

    override suspend fun get(): DomainResult<AppPreferences> = DomainResult.Success(AppPreferences.Default)

    override suspend fun setExploreCity(cityId: String?): DomainResult<AppPreferences> {
        lastCityId = cityId
        return AppPreferences.create(
            exploreCityId = cityId,
            locale = AppLocale.French,
            displayCurrency = KwaborCurrency.Xof,
        )
    }

    override suspend fun setLocale(locale: AppLocale): DomainResult<AppPreferences> =
        DomainResult.Failure(DomainError.Validation("error.test.unsupported"))

    override suspend fun setDisplayCurrency(currency: KwaborCurrency): DomainResult<AppPreferences> =
        DomainResult.Failure(DomainError.Validation("error.test.unsupported"))
}

private fun snapshot(): ExploreFeedSnapshot = ExploreFeedSnapshot(
    cities = listOf(City(id = "cotonou", name = "Cotonou", latitude = 6.3703, longitude = 2.3912)),
    categories = listOf(
        Category(
            id = "heritage-historique",
            nameKey = "category.heritage.historique",
            listingType = ListingType.Place,
            defaultListingClass = ListingClass.Heritage,
        ),
    ),
    items = listOf(listing("listing-1")),
    nextCursor = "cursor-1",
    cachedAtEpochMilliseconds = 1_000,
    source = ExploreFeedSource.Cache,
)

private fun listing(id: String): ListingSummary = ListingSummary(
    id = id,
    type = ListingType.Place,
    listingClass = ListingClass.Heritage,
    status = ListingStatus.Published,
    name = "Lieu $id",
    cityId = "cotonou",
    categoryId = "heritage-historique",
    coverImageUrl = null,
    priceFromXof = null,
    ratingAverage = 4.5,
    likesCount = 0,
    verified = true,
    sponsoredUntilEpochMilliseconds = null,
)

private object TestClock : ClockProvider {
    override fun nowEpochMilliseconds(): Long = 1_000
}
