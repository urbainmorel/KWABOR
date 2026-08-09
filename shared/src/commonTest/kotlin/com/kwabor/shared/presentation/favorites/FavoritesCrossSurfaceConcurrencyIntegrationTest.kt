package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.data.favorites.DataFavoritesRepository
import com.kwabor.shared.data.favorites.FAVORITE_LISTING_ID_ONE
import com.kwabor.shared.data.favorites.FavoriteListingPageDto
import com.kwabor.shared.data.favorites.FavoriteMutationRowDto
import com.kwabor.shared.data.favorites.FavoritesDataSource
import com.kwabor.shared.data.favorites.validFavoriteListingRow
import com.kwabor.shared.data.favorites.validFavoriteMutationRow
import com.kwabor.shared.domain.catalog.CatalogInteractionRepository
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import com.kwabor.shared.domain.explore.ExploreFeedRepository
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExploreEffect
import com.kwabor.shared.presentation.explore.ExploreIntent
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.ExploreRuntime
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesCrossSurfaceConcurrencyIntegrationTest {
    @Test
    fun favoritesRemovalThenExploreAdditionConvergesOnTheLaterAddition() = runTest {
        verifyCrossSurfaceConvergence(firstSurface = FavoriteSurface.Favorites)
    }

    @Test
    fun exploreAdditionThenFavoritesRemovalConvergesOnTheLaterRemoval() = runTest {
        verifyCrossSurfaceConvergence(firstSurface = FavoriteSurface.Explore)
    }

    @Test
    fun confirmedExploreAdditionIsRelayedAcrossTabAndCityContextChanges() = runTest {
        listOf(ExploreFeedContextChange.Tab, ExploreFeedContextChange.City).forEach { contextChange ->
            verifyExploreConfirmationSurvives(contextChange)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.verifyCrossSurfaceConvergence(firstSurface: FavoriteSurface) {
    val scenario = CrossSurfaceFavoriteScenario(this)
    try {
        scenario.initialize()
        val observers = scenario.observeEffects()
        val secondSurface = firstSurface.other()
        val firstFavorited = firstSurface.requestedFavoritedState
        val finalFavorited = secondSurface.requestedFavoritedState

        scenario.dispatchMutation(firstSurface)
        runCurrent()
        scenario.expectNextTransportMutation(firstFavorited)
        scenario.dispatchMutation(secondSurface)
        runCurrent()
        assertFalse(scenario.hasUnexpectedTransportMutation())

        scenario.releaseNextTransportMutation()
        runCurrent()
        scenario.relay(observers.await(firstSurface).asserts(firstFavorited, sequence = 1L))
        runCurrent()
        scenario.expectNextTransportMutation(finalFavorited)

        scenario.releaseNextTransportMutation()
        runCurrent()
        scenario.relay(observers.await(secondSurface).asserts(finalFavorited, sequence = 2L))
        advanceUntilIdle()

        scenario.assertConvergenceAndRejectsStaleConfirmation(finalFavorited)
        assertEquals(listOf(firstFavorited, finalFavorited), scenario.requestedFavoritedStates)
    } finally {
        scenario.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.verifyExploreConfirmationSurvives(contextChange: ExploreFeedContextChange) {
    val scenario = CrossSurfaceFavoriteScenario(this, initialServerFavorited = false)
    try {
        scenario.initialize()
        val observers = scenario.observeEffects()

        scenario.dispatchMutation(FavoriteSurface.Explore)
        runCurrent()
        scenario.expectNextTransportMutation(expectedFavorited = true)
        scenario.dispatchFeedContextChange(contextChange)
        runCurrent()
        scenario.assertFeedContextChanged(contextChange)

        scenario.releaseNextTransportMutation()
        runCurrent()
        assertTrue(observers.hasCompleted(FavoriteSurface.Explore))
        val confirmation = observers.await(FavoriteSurface.Explore).asserts(expectedFavorited = true, sequence = 1L)
        scenario.relay(confirmation)
        advanceUntilIdle()

        scenario.assertServerAndFavorites(expectedFavorited = true)
        scenario.assertFeedContextChanged(contextChange)
    } finally {
        scenario.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class CrossSurfaceFavoriteScenario(
    private val testScope: TestScope,
    private val initialServerFavorited: Boolean = true,
) {
    private val transport = OrderedFavoritesTransport(initialFavorited = initialServerFavorited)
    private val repository = DataFavoritesRepository(transport)
    private val strings = stringsFor(AppLocale.French)
    private var effectObservers: SurfaceEffectObservers? = null
    private val explore =
        ExploreRuntime(
            presenter =
            ExplorePresenter(
                exploreFeedRepository = IntegrationExploreFeedRepository,
                catalogInteractionRepository = IntegrationCatalogInteractionRepository,
                favoritesRepository = repository,
                appPreferencesRepository = null,
                clockProvider = IntegrationClock,
            ),
            strings = strings,
            coroutineScope = testScope,
        )
    private val favorites =
        FavoritesRuntime(
            presenter = FavoritesPresenter(repository),
            strings = strings.favorites,
            coroutineScope = testScope,
        )

    val requestedFavoritedStates: List<Boolean>
        get() = transport.requestedFavoritedStates

    suspend fun initialize() {
        explore.dispatch(ExploreIntent.ViewerContextChanged(INTEGRATION_VIEWER_SCOPE))
        favorites.dispatch(FavoritesIntent.ViewerContextChanged(INTEGRATION_VIEWER_SCOPE))
        favorites.dispatch(FavoritesIntent.ScreenAppeared)
        testScope.advanceUntilIdle()

        assertEquals(initialServerFavorited, transport.serverFavorited)
        assertFalse(explore.state.value.listings.single().favorited)
        val expectedFavoriteIds = if (initialServerFavorited) listOf(FAVORITE_LISTING_ID_ONE) else emptyList()
        assertEquals(expectedFavoriteIds, favorites.state.value.items.map { item -> item.id })
    }

    fun observeEffects(): SurfaceEffectObservers = SurfaceEffectObservers(
        explore =
        testScope.async(start = CoroutineStart.UNDISPATCHED) {
            explore.effects.filterIsInstance<ExploreEffect.FavoriteChanged>().first()
        },
        favorites =
        testScope.async(start = CoroutineStart.UNDISPATCHED) {
            favorites.effects.filterIsInstance<FavoritesEffect.FavoriteChanged>().first()
        },
    ).also { observers -> effectObservers = observers }

    fun dispatchMutation(surface: FavoriteSurface) {
        when (surface) {
            FavoriteSurface.Explore -> explore.dispatch(ExploreIntent.ToggleFavorite(FAVORITE_LISTING_ID_ONE))
            FavoriteSurface.Favorites -> favorites.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_LISTING_ID_ONE))
        }
    }

    fun dispatchFeedContextChange(contextChange: ExploreFeedContextChange) {
        when (contextChange) {
            ExploreFeedContextChange.Tab -> explore.dispatch(ExploreIntent.SelectTab(ExploreTab.Events))
            ExploreFeedContextChange.City -> explore.dispatch(ExploreIntent.SelectCity(INTEGRATION_SECOND_CITY_ID))
        }
    }

    suspend fun expectNextTransportMutation(expectedFavorited: Boolean) {
        assertEquals(expectedFavorited, transport.awaitNextMutation())
    }

    fun hasUnexpectedTransportMutation(): Boolean = transport.hasStartedMutation()

    fun releaseNextTransportMutation() {
        transport.releaseNextMutation()
    }

    fun relay(change: ConfirmedFavoriteChange) {
        when (change.surface) {
            FavoriteSurface.Explore -> favorites.dispatch(change.toFavoritesIntent())
            FavoriteSurface.Favorites -> explore.dispatch(change.toExploreIntent())
        }
    }

    fun replayStaleConfirmation(favorited: Boolean) {
        val stale =
            ConfirmedFavoriteChange(
                surface = FavoriteSurface.Favorites,
                favorited = favorited,
                clientMutationSequence = 1L,
            )
        explore.dispatch(stale.toExploreIntent())
        favorites.dispatch(stale.toFavoritesIntent())
    }

    fun assertConverged(expectedFavorited: Boolean) {
        assertServerAndFavorites(expectedFavorited)
        assertEquals(expectedFavorited, explore.state.value.listings.single().favorited)
    }

    fun assertServerAndFavorites(expectedFavorited: Boolean) {
        assertEquals(expectedFavorited, transport.serverFavorited)
        val visibleFavoriteIds = favorites.state.value.items.map { item -> item.id }
        assertEquals(if (expectedFavorited) listOf(FAVORITE_LISTING_ID_ONE) else emptyList(), visibleFavoriteIds)
    }

    fun assertFeedContextChanged(contextChange: ExploreFeedContextChange) {
        when (contextChange) {
            ExploreFeedContextChange.Tab -> assertEquals(ExploreTab.Events, explore.state.value.selectedTab)
            ExploreFeedContextChange.City ->
                assertEquals(
                    INTEGRATION_SECOND_CITY_ID,
                    explore.state.value.selectedCityId,
                )
        }
        assertTrue(explore.state.value.listings.isEmpty())
    }

    suspend fun assertConvergenceAndRejectsStaleConfirmation(expectedFavorited: Boolean) {
        assertConverged(expectedFavorited)
        replayStaleConfirmation(favorited = !expectedFavorited)
        testScope.advanceUntilIdle()
        assertConverged(expectedFavorited)
    }

    suspend fun close() {
        effectObservers?.cancel()
        transport.releaseAllMutations()
        testScope.runCurrent()
        explore.close()
        favorites.close()
        testScope.advanceUntilIdle()
    }
}

private data class SurfaceEffectObservers(
    val explore: Deferred<ExploreEffect.FavoriteChanged>,
    val favorites: Deferred<FavoritesEffect.FavoriteChanged>,
) {
    suspend fun await(surface: FavoriteSurface): ConfirmedFavoriteChange = when (surface) {
        FavoriteSurface.Explore -> explore.await().toConfirmedChange()
        FavoriteSurface.Favorites -> favorites.await().toConfirmedChange()
    }

    fun hasCompleted(surface: FavoriteSurface): Boolean = when (surface) {
        FavoriteSurface.Explore -> explore.isCompleted
        FavoriteSurface.Favorites -> favorites.isCompleted
    }

    fun cancel() {
        explore.cancel()
        favorites.cancel()
    }
}

private data class ConfirmedFavoriteChange(
    val surface: FavoriteSurface,
    val favorited: Boolean,
    val clientMutationSequence: Long,
) {
    fun asserts(expectedFavorited: Boolean, sequence: Long): ConfirmedFavoriteChange {
        assertEquals(expectedFavorited, favorited)
        assertEquals(sequence, clientMutationSequence)
        return this
    }

    fun toExploreIntent(): ExploreIntent.FavoriteStateChanged = ExploreIntent.FavoriteStateChanged(
        listingId = FAVORITE_LISTING_ID_ONE,
        favorited = favorited,
        clientMutationSequence = clientMutationSequence,
        scope = INTEGRATION_VIEWER_SCOPE,
    )

    fun toFavoritesIntent(): FavoritesIntent.ExternalFavoriteStateChanged =
        FavoritesIntent.ExternalFavoriteStateChanged(
            listingId = FAVORITE_LISTING_ID_ONE,
            favorited = favorited,
            clientMutationSequence = clientMutationSequence,
            scope = INTEGRATION_VIEWER_SCOPE,
        )
}

private fun ExploreEffect.FavoriteChanged.toConfirmedChange(): ConfirmedFavoriteChange {
    assertEquals(FAVORITE_LISTING_ID_ONE, listingId)
    assertEquals(INTEGRATION_VIEWER_SCOPE, scope)
    return ConfirmedFavoriteChange(FavoriteSurface.Explore, favorited, clientMutationSequence)
}

private fun FavoritesEffect.FavoriteChanged.toConfirmedChange(): ConfirmedFavoriteChange {
    assertEquals(FAVORITE_LISTING_ID_ONE, listingId)
    assertEquals(INTEGRATION_VIEWER_SCOPE, scope)
    return ConfirmedFavoriteChange(FavoriteSurface.Favorites, favorited, clientMutationSequence)
}

private enum class FavoriteSurface {
    Explore,
    Favorites,
    ;

    val requestedFavoritedState: Boolean
        get() = this == Explore

    fun other(): FavoriteSurface = when (this) {
        Explore -> Favorites
        Favorites -> Explore
    }
}

private enum class ExploreFeedContextChange {
    Tab,
    City,
}

private class OrderedFavoritesTransport(
    initialFavorited: Boolean,
) : FavoritesDataSource {
    private val mutationStarts = Channel<Boolean>(capacity = Channel.UNLIMITED)
    private val mutationReleases = Channel<Unit>(capacity = Channel.UNLIMITED)
    private val mutableRequestedFavoritedStates = mutableListOf<Boolean>()
    var serverFavorited: Boolean = initialFavorited
        private set

    val requestedFavoritedStates: List<Boolean>
        get() = mutableRequestedFavoritedStates.toList()

    override suspend fun listFavorites(filter: ListingType?, page: ListingPageRequest): FavoriteListingPageDto =
        FavoriteListingPageDto(
            items = if (serverFavorited) listOf(validFavoriteListingRow(type = "lieu")) else emptyList(),
            nextCursor = null,
        )

    override suspend fun setFavorite(listingId: String, favorited: Boolean): FavoriteMutationRowDto {
        mutableRequestedFavoritedStates += favorited
        mutationStarts.send(favorited)
        mutationReleases.receive()
        serverFavorited = favorited
        return validFavoriteMutationRow(listingId = listingId, favorited = favorited)
    }

    suspend fun awaitNextMutation(): Boolean = mutationStarts.receive()

    fun hasStartedMutation(): Boolean = mutationStarts.tryReceive().isSuccess

    fun releaseNextMutation() {
        check(mutationReleases.trySend(Unit).isSuccess)
    }

    fun releaseAllMutations() {
        repeat(2) { mutationReleases.trySend(Unit) }
    }
}

private object IntegrationExploreFeedRepository : ExploreFeedRepository {
    private val snapshot =
        ExploreFeedSnapshot(
            cities =
            listOf(
                City(id = "cotonou", name = "Cotonou"),
                City(id = INTEGRATION_SECOND_CITY_ID, name = "Porto-Novo"),
            ),
            categories = emptyList(),
            items =
            listOf(
                ListingSummary(
                    id = FAVORITE_LISTING_ID_ONE,
                    type = ListingType.Place,
                    listingClass = ListingClass.Heritage,
                    status = ListingStatus.Published,
                    name = "Maison Kwabor",
                    cityId = "cotonou",
                    categoryId = "heritage-historique",
                    coverImageUrl = null,
                    priceFromXof = null,
                    ratingAverage = null,
                    likesCount = 0,
                    verified = true,
                    sponsoredUntilEpochMilliseconds = null,
                ),
            ),
            nextCursor = null,
            cachedAtEpochMilliseconds = INTEGRATION_TIMESTAMP_MILLISECONDS,
            source = ExploreFeedSource.Network,
        )

    override suspend fun readCached(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot?> =
        DomainResult.Success(null)

    override suspend fun refresh(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot> = DomainResult.Success(
        snapshot.takeIf { query.isInitialExploreContext() } ?: snapshot.copy(items = emptyList()),
    )

    override suspend fun append(
        query: ExploreFeedQuery,
        currentSnapshot: ExploreFeedSnapshot,
    ): DomainResult<ExploreFeedSnapshot> = DomainResult.Success(snapshot)
}

private fun ExploreFeedQuery.isInitialExploreContext(): Boolean =
    filters.listingType == ListingType.Place && filters.cityId != INTEGRATION_SECOND_CITY_ID

private object IntegrationCatalogInteractionRepository : CatalogInteractionRepository {
    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        DomainResult.Success(integrationInteraction(listingId))

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = DomainResult.Success(listingIds.map(::integrationInteraction))

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        DomainResult.Success(integrationInteraction(listingId).copy(likedByViewer = true, likesCount = 1))

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        DomainResult.Success(integrationInteraction(listingId))
}

private fun integrationInteraction(listingId: String): ListingViewerInteraction = ListingViewerInteraction(
    listingId = listingId,
    likedByViewer = false,
    favoritedByViewer = false,
    likesCount = 0,
)

private object IntegrationClock : ClockProvider {
    override fun nowEpochMilliseconds(): Long = INTEGRATION_TIMESTAMP_MILLISECONDS
}

private val INTEGRATION_VIEWER_SCOPE = ViewerSessionScope(accountId = "integration-viewer", epoch = 1L)
private const val INTEGRATION_SECOND_CITY_ID = "porto-novo"
private const val INTEGRATION_TIMESTAMP_MILLISECONDS = 1_000L
