package com.kwabor.android.presentation.explore

import com.kwabor.android.auth.ApproximateLocationResult
import com.kwabor.android.auth.ApproximateLocationService
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
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.observability.AnalyticsEntityType
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.domain.preferences.AppPreferences
import com.kwabor.shared.domain.preferences.AppPreferencesRepository
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    private val strings = stringsFor(AppLocale.French)
    private val viewerSessionScopeTracker = ViewerSessionScopeTracker()

    @Test
    fun initialState_showsLoadingSkeletonBeforePreferencesResume() = runTest {
        val preferencesGate = CompletableDeferred<Unit>()
        val viewModel = viewModel(
            repository = ViewModelCatalogRepository(),
            appPreferencesRepository = BlockingAppPreferencesRepository(preferencesGate),
        )

        assertTrue(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.isEmpty)
        runCurrent()
        assertTrue(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.isEmpty)

        preferencesGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun selectTab_reloadsExploreAndReducesImmutableState() = runTest {
        val repository = ViewModelCatalogRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.SelectTab(ExploreTab.Events))
        advanceUntilIdle()

        assertEquals(ExploreTab.Events, viewModel.state.value.selectedTab)
        assertEquals(ListingType.Event, repository.lastFilters?.listingType)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun authRequired_duplicateGuestScopePreservesPendingUntilExplicitClear() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = true)
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.ToggleLike(TEST_LISTING_ID))
        advanceUntilIdle()

        val effect = assertIs<ExploreEffect.AuthenticationRequired>(viewModel.effects.first())
        assertEquals(TEST_CITY_ID, effect.suggestedCityId)
        assertEquals(viewerSessionScopeTracker.currentScope, effect.scope)
        assertEquals(TEST_LISTING_ID, viewModel.state.value.pendingAuthInteraction?.listingId)

        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope(accountId = null)))
        advanceUntilIdle()

        assertEquals(TEST_LISTING_ID, viewModel.state.value.pendingAuthInteraction?.listingId)
        viewModel.onIntent(ExploreIntent.ClearPendingAuthentication)
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingAuthInteraction)
        assertNull(viewModel.state.value.interactionMessage)
    }

    @Test
    fun authenticatedViewerContext_replaysPendingInteraction() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = true)
        val trackedEvents = mutableListOf<AnalyticsEvent>()
        val viewModel = viewModel(repository, track = trackedEvents::add)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect()
        }
        advanceUntilIdle()
        viewModel.onIntent(ExploreIntent.ToggleLike(TEST_LISTING_ID))
        advanceUntilIdle()
        assertEquals(TEST_LISTING_ID, viewModel.state.value.pendingAuthInteraction?.listingId)

        repository.requiresAuthentication = false
        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope("viewer-a")))
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingAuthInteraction)
        assertTrue(viewModel.state.value.listings.single().liked)
        assertEquals(1, viewModel.state.value.listings.single().likesCount)
        assertEquals(1, trackedEvents.size)
        assertEquals(AnalyticsEventName.ProtectedActionReplayed, trackedEvents.single().name)
        assertEquals(AnalyticsEntityType.Place, trackedEvents.single().context.entityType)
        assertEquals(TEST_LISTING_ID, trackedEvents.single().context.entityId)
        assertEquals(TEST_CITY_ID, trackedEvents.single().context.cityId)
    }

    @Test
    fun viewerContextChanged_toGuestClearsPreviousViewerState() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = true)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        repository.requiresAuthentication = false
        repository.viewerInteractions = listOf(
            ListingViewerInteraction(
                listingId = TEST_LISTING_ID,
                likedByViewer = true,
                favoritedByViewer = true,
                likesCount = 1,
            ),
        )
        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope("viewer-a")))
        advanceUntilIdle()

        repository.interactionError = DomainError.NetworkUnavailable()
        viewModel.onIntent(ExploreIntent.ToggleFavorite(TEST_LISTING_ID))
        advanceUntilIdle()
        repository.interactionError = null
        repository.requiresAuthentication = true
        viewModel.onIntent(ExploreIntent.ToggleLike(TEST_LISTING_ID))
        advanceUntilIdle()
        viewModel.effects.first()

        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope(accountId = null)))

        val immediatelyPurgedState = viewModel.state.value
        assertFalse(immediatelyPurgedState.listings.single().liked)
        assertFalse(immediatelyPurgedState.listings.single().favorited)
        assertTrue(immediatelyPurgedState.queuedInteractions.isEmpty())
        assertNull(immediatelyPurgedState.pendingAuthInteraction)
        assertNull(immediatelyPurgedState.interactionMessage)
        assertTrue(immediatelyPurgedState.isLoading)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.listings.single().liked)
        assertFalse(state.listings.single().favorited)
        assertTrue(state.queuedInteractions.isEmpty())
        assertNull(state.pendingAuthInteraction)
        assertNull(state.interactionMessage)
    }

    @Test
    fun viewerContextChanged_toNewAccountReloadsViewerInteractions() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = true)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        repository.requiresAuthentication = false
        repository.viewerInteractions = listOf(
            ListingViewerInteraction(
                listingId = TEST_LISTING_ID,
                likedByViewer = true,
                favoritedByViewer = false,
                likesCount = 1,
            ),
        )
        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope("viewer-a")))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.listings.single().liked)
        val firstViewerRequestCount = repository.viewerInteractionRequestCount

        repository.viewerInteractions = listOf(
            ListingViewerInteraction(
                listingId = TEST_LISTING_ID,
                likedByViewer = false,
                favoritedByViewer = true,
                likesCount = 1,
            ),
        )
        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope("viewer-b")))

        val immediatelyPurgedState = viewModel.state.value
        assertFalse(immediatelyPurgedState.listings.single().liked)
        assertFalse(immediatelyPurgedState.listings.single().favorited)
        assertTrue(immediatelyPurgedState.isLoading)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.listings.single().liked)
        assertTrue(state.listings.single().favorited)
        assertTrue(repository.viewerInteractionRequestCount > firstViewerRequestCount)
    }

    @Test
    fun favoriteStateChanged_appliesExternalFavoriteWithoutChangingLikeState() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = false)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val scope = viewerScope("viewer-a")
        viewModel.onIntent(ExploreIntent.ViewerContextChanged(scope))
        advanceUntilIdle()

        val intent =
            ExploreIntent.FavoriteStateChanged(
                listingId = TEST_LISTING_ID,
                favorited = true,
                clientMutationSequence = TEST_CLIENT_MUTATION_SEQUENCE,
                scope = scope,
            )
        val sharedIntent = intent.toSharedIntent()

        assertEquals(TEST_CLIENT_MUTATION_SEQUENCE, sharedIntent.clientMutationSequence)
        assertEquals(scope, sharedIntent.scope)
        viewModel.onIntent(intent)
        advanceUntilIdle()

        val listing = viewModel.state.value.listings.single()
        assertTrue(listing.favorited)
        assertFalse(listing.liked)
        assertEquals(0, listing.likesCount)
    }

    @Test
    fun toggleFavorite_emitsCurrentScopedChangeForFavoritesBridge() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = false)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val scope = viewerScope("viewer-a")
        viewModel.onIntent(ExploreIntent.ViewerContextChanged(scope))
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.ToggleFavorite(TEST_LISTING_ID))
        advanceUntilIdle()

        val effect = assertIs<ExploreEffect.FavoriteChanged>(viewModel.effects.first())
        assertEquals(TEST_LISTING_ID, effect.listingId)
        assertTrue(effect.favorited)
        assertEquals(TEST_CLIENT_MUTATION_SEQUENCE, effect.clientMutationSequence)
        assertEquals(scope, effect.scope)
    }

    @Test
    fun bufferedFavoriteEffect_isDroppedAfterScopeChanges() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = false)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope("viewer-a")))
        advanceUntilIdle()
        viewModel.onIntent(ExploreIntent.ToggleFavorite(TEST_LISTING_ID))
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope("viewer-b")))
        val observed = mutableListOf<ExploreEffect>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect(observed::add)
        }
        advanceUntilIdle()

        assertTrue(observed.isEmpty())
        collection.cancel()
    }

    @Test
    fun bufferedGuestAuthenticationEffect_isDroppedAfterTrackerBecomesAuthenticated() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = true)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onIntent(ExploreIntent.ToggleLike(TEST_LISTING_ID))
        advanceUntilIdle()
        assertEquals(TEST_LISTING_ID, viewModel.state.value.pendingAuthInteraction?.listingId)

        viewerScope("viewer-a")
        val observed = mutableListOf<ExploreEffect>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect(observed::add)
        }
        advanceUntilIdle()

        assertTrue(observed.isEmpty())
        collection.cancel()
    }

    @Test
    fun bufferedReplayAnalytics_isDroppedAfterLogoutAndSameAccountRelogin() = runTest {
        val repository = ViewModelCatalogRepository(requiresAuthentication = true)
        val trackedEvents = mutableListOf<AnalyticsEvent>()
        val viewModel = viewModel(repository, track = trackedEvents::add)
        advanceUntilIdle()
        viewModel.onIntent(ExploreIntent.ToggleLike(TEST_LISTING_ID))
        advanceUntilIdle()

        repository.requiresAuthentication = false
        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope("viewer-a")))
        advanceUntilIdle()
        assertNull(viewModel.state.value.pendingAuthInteraction)
        assertTrue(viewModel.state.value.listings.single().liked)

        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope(accountId = null)))
        viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerScope("viewer-a")))
        advanceUntilIdle()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect()
        }
        advanceUntilIdle()

        assertTrue(trackedEvents.isEmpty())
        collection.cancel()
    }

    @Test
    fun delayedAuthenticationFailure_afterFeedChangeDoesNotCreatePendingInteraction() = runTest {
        val repository = ViewModelCatalogRepository(
            requiresAuthentication = true,
            firstPageListingsByType = mapOf(ListingType.Event to emptyList()),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val interactionGate = CompletableDeferred<Unit>()
        repository.interactionGate = interactionGate

        viewModel.onIntent(ExploreIntent.ToggleLike(TEST_LISTING_ID))
        runCurrent()
        viewModel.onIntent(ExploreIntent.SelectTab(ExploreTab.Events))
        runCurrent()
        interactionGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ExploreTab.Events, viewModel.state.value.selectedTab)
        assertTrue(viewModel.state.value.listings.isEmpty())
        assertNull(viewModel.state.value.pendingAuthInteraction)
        assertNull(viewModel.state.value.interactionMessage)
    }

    @Test
    fun loadNext_appendsTheNextPageWithoutReplacingVisibleItems() = runTest {
        val repository = ViewModelCatalogRepository(
            firstPageCursor = "cursor-1",
            nextPageListings = listOf(testListing(id = "listing-2")),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.LoadNext)
        advanceUntilIdle()

        assertEquals(listOf(TEST_LISTING_ID, "listing-2"), viewModel.state.value.listings.map { it.id })
        assertEquals(null, viewModel.state.value.nextCursor)
        assertFalse(viewModel.state.value.isAppending)
    }

    @Test
    fun refresh_doesNotBlockUiIntentsAndPreservesConcurrentInteraction() = runTest {
        val repository = ViewModelCatalogRepository()
        val feedRepository = ViewModelExploreFeedRepository(repository)
        val viewModel = viewModel(repository = repository, feedRepository = feedRepository)
        advanceUntilIdle()
        val refreshGate = CompletableDeferred<Unit>()
        feedRepository.refreshGate = refreshGate

        viewModel.onIntent(ExploreIntent.Refresh)
        runCurrent()
        assertTrue(viewModel.state.value.isRefreshing)

        viewModel.onIntent(ExploreIntent.OpenCitySelector)
        viewModel.onIntent(ExploreIntent.ToggleLike(TEST_LISTING_ID))
        runCurrent()

        assertTrue(viewModel.state.value.isCitySelectorOpen)
        assertTrue(viewModel.state.value.listings.single().liked)

        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isCitySelectorOpen)
        assertTrue(viewModel.state.value.listings.single().liked)
        assertFalse(viewModel.state.value.isRefreshing)
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        repository: ViewModelCatalogRepository,
        feedRepository: ExploreFeedRepository = ViewModelExploreFeedRepository(repository),
        appPreferencesRepository: AppPreferencesRepository? = null,
        locationService: ApproximateLocationService = ApproximateLocationService {
            ApproximateLocationResult.Unavailable
        },
        track: (AnalyticsEvent) -> Unit = {},
    ): ExploreViewModel {
        val viewModelScope = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))
        requireNotNull(coroutineContext[Job]).invokeOnCompletion { viewModelScope.cancel() }
        return ExploreViewModel(
            presenter = ExplorePresenter(
                exploreFeedRepository = feedRepository,
                catalogInteractionRepository = repository,
                favoritesRepository = ViewModelFavoritesRepository(repository),
                appPreferencesRepository = appPreferencesRepository,
                clockProvider = FixedViewModelClock,
            ),
            locationService = locationService,
            strings = strings,
            coroutineScope = viewModelScope,
            viewerSessionScopeTracker = viewerSessionScopeTracker,
            track = track,
        ).also { viewModel ->
            viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerSessionScopeTracker.currentScope))
        }
    }

    private fun viewerScope(accountId: String?): ViewerSessionScope =
        viewerSessionScopeTracker.update(accountId, accountSetupComplete = accountId != null)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelLocationTest {
    private val strings = stringsFor(AppLocale.French)
    private val viewerSessionScopeTracker = ViewerSessionScopeTracker()

    @Test
    fun locationPermission_selectsNearestCityAndReloadsItsFeed() = runTest {
        val repository = ViewModelCatalogRepository()
        val viewModel = viewModel(
            repository = repository,
            locationService = ApproximateLocationService {
                ApproximateLocationResult.Available(latitude = 6.3631, longitude = 2.0851)
            },
        )
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.OpenCitySelector)
        viewModel.onIntent(ExploreIntent.RequestLocation)
        advanceUntilIdle()

        assertIs<ExploreEffect.RequestLocationPermission>(viewModel.effects.first())
        viewModel.onIntent(ExploreIntent.LocationPermissionResult(granted = true))
        advanceUntilIdle()

        assertEquals("ouidah", viewModel.state.value.selectedCityId)
        assertEquals("ouidah", repository.lastFilters?.cityId)
        assertFalse(viewModel.state.value.isLocating)
    }

    @Test
    fun locationDisabled_mapsPlatformResultToSharedRuntimeState() = runTest {
        val repository = ViewModelCatalogRepository()
        val viewModel = viewModel(
            repository = repository,
            locationService = ApproximateLocationService {
                ApproximateLocationResult.LocationDisabled
            },
        )
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.OpenCitySelector)
        viewModel.onIntent(ExploreIntent.RequestLocation)
        runCurrent()
        viewModel.effects.first()
        viewModel.onIntent(ExploreIntent.LocationPermissionResult(granted = true))
        advanceUntilIdle()

        assertEquals(strings.exploreLocationDisabled, viewModel.state.value.locationMessage)
        assertFalse(viewModel.state.value.isLocating)
    }

    @Test
    fun locationInsideBenin_withoutCityReferencesReportsUnavailable() = runTest {
        val repository = ViewModelCatalogRepository(cities = emptyList())
        val viewModel = viewModel(
            repository = repository,
            locationService = ApproximateLocationService {
                ApproximateLocationResult.Available(latitude = 6.3631, longitude = 2.0851)
            },
        )
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.OpenCitySelector)
        viewModel.onIntent(ExploreIntent.RequestLocation)
        runCurrent()
        viewModel.effects.first()
        viewModel.onIntent(ExploreIntent.LocationPermissionResult(granted = true))
        advanceUntilIdle()

        assertEquals(strings.exploreLocationUnavailable, viewModel.state.value.locationMessage)
        assertFalse(viewModel.state.value.isLocating)
    }

    @Test
    fun locationOutsideBenin_reportsOutsideBenin() = runTest {
        val repository = ViewModelCatalogRepository()
        val viewModel = viewModel(
            repository = repository,
            locationService = ApproximateLocationService {
                ApproximateLocationResult.Available(latitude = 48.8566, longitude = 2.3522)
            },
        )
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.OpenCitySelector)
        viewModel.onIntent(ExploreIntent.RequestLocation)
        runCurrent()
        viewModel.effects.first()
        viewModel.onIntent(ExploreIntent.LocationPermissionResult(granted = true))
        advanceUntilIdle()

        assertEquals(strings.exploreLocationOutsideBenin, viewModel.state.value.locationMessage)
        assertFalse(viewModel.state.value.isLocating)
    }

    @Test
    fun cityPersistenceFailure_remainsVisibleAfterSelectorCloses() = runTest {
        val repository = ViewModelCatalogRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onIntent(ExploreIntent.OpenCitySelector)
        viewModel.onIntent(ExploreIntent.SelectCity("ouidah"))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isCitySelectorOpen)
        assertEquals(strings.exploreCityPersistenceError, viewModel.state.value.locationMessage)
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        repository: ViewModelCatalogRepository,
        locationService: ApproximateLocationService = ApproximateLocationService {
            ApproximateLocationResult.Unavailable
        },
    ): ExploreViewModel {
        val viewModelScope = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))
        requireNotNull(coroutineContext[Job]).invokeOnCompletion { viewModelScope.cancel() }
        return ExploreViewModel(
            presenter = ExplorePresenter(
                exploreFeedRepository = ViewModelExploreFeedRepository(repository),
                catalogInteractionRepository = repository,
                favoritesRepository = ViewModelFavoritesRepository(repository),
                appPreferencesRepository = null,
                clockProvider = FixedViewModelClock,
            ),
            locationService = locationService,
            strings = strings,
            coroutineScope = viewModelScope,
            viewerSessionScopeTracker = viewerSessionScopeTracker,
        ).also { viewModel ->
            viewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerSessionScopeTracker.currentScope))
        }
    }
}

private class BlockingAppPreferencesRepository(
    private val gate: CompletableDeferred<Unit>,
) : AppPreferencesRepository {
    override suspend fun get(): DomainResult<AppPreferences> {
        gate.await()
        return DomainResult.Success(AppPreferences.Default)
    }

    override suspend fun setExploreCity(cityId: String?): DomainResult<AppPreferences> =
        DomainResult.Success(AppPreferences.Default)

    override suspend fun setLocale(locale: AppLocale): DomainResult<AppPreferences> =
        DomainResult.Success(AppPreferences.Default)

    override suspend fun setDisplayCurrency(currency: KwaborCurrency): DomainResult<AppPreferences> =
        DomainResult.Success(AppPreferences.Default)
}

private class ViewModelExploreFeedRepository(
    private val catalogRepository: CatalogRepository,
) : ExploreFeedRepository {
    var refreshGate: CompletableDeferred<Unit>? = null

    override suspend fun readCached(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot?> =
        DomainResult.Success(null)

    override suspend fun refresh(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot> {
        refreshGate?.also { gate ->
            refreshGate = null
            gate.await()
        }
        return when (val cities = catalogRepository.listCities()) {
            is DomainResult.Failure -> cities
            is DomainResult.Success -> when (val categories = catalogRepository.listCategories()) {
                is DomainResult.Failure -> categories
                is DomainResult.Success -> when (
                    val page = catalogRepository.listListings(
                        filters = query.filters,
                        page = ListingPageRequest(limit = query.pageSize),
                    )
                ) {
                    is DomainResult.Failure -> page
                    is DomainResult.Success -> DomainResult.Success(
                        ExploreFeedSnapshot(
                            cities = cities.value,
                            categories = categories.value,
                            items = page.value.items,
                            nextCursor = page.value.nextCursor,
                            cachedAtEpochMilliseconds = FixedViewModelClock.nowEpochMilliseconds(),
                            source = ExploreFeedSource.Network,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun append(
        query: ExploreFeedQuery,
        currentSnapshot: ExploreFeedSnapshot,
    ): DomainResult<ExploreFeedSnapshot> {
        val cursor = currentSnapshot.nextCursor ?: return DomainResult.Failure(
            DomainError.Validation("error.test.cursor"),
        )
        return when (
            val result = catalogRepository.listListings(
                filters = query.filters,
                page = ListingPageRequest(cursor = cursor, limit = query.pageSize),
            )
        ) {
            is DomainResult.Success -> DomainResult.Success(
                currentSnapshot.copy(
                    items = (currentSnapshot.items + result.value.items).distinctBy(ListingSummary::id),
                    nextCursor = result.value.nextCursor,
                    source = ExploreFeedSource.Network,
                ),
            )
            is DomainResult.Failure -> result
        }
    }
}

private class ViewModelCatalogRepository(
    var requiresAuthentication: Boolean = false,
    private val firstPageCursor: String? = null,
    private val nextPageListings: List<ListingSummary> = emptyList(),
    private val cities: List<City> = defaultViewModelCities(),
    private val firstPageListingsByType: Map<ListingType, List<ListingSummary>> = emptyMap(),
) : CatalogRepository {
    var interactionError: DomainError? = null
    var interactionGate: CompletableDeferred<Unit>? = null
    var viewerInteractions: List<ListingViewerInteraction> = emptyList()
    var viewerInteractionRequestCount: Int = 0
        private set
    var lastFilters: ListingFilters? = null
        private set

    override suspend fun listCities(): DomainResult<List<City>> = DomainResult.Success(cities)

    override suspend fun listCategories(): DomainResult<List<Category>> = DomainResult.Success(emptyList())

    override suspend fun listListings(
        filters: ListingFilters,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> {
        lastFilters = filters
        return if (page.cursor == null) {
            DomainResult.Success(
                ListingSummaryPage(
                    items = firstPageListingsByType[filters.listingType] ?: listOf(testListing()),
                    nextCursor = firstPageCursor,
                ),
            )
        } else {
            DomainResult.Success(ListingSummaryPage(items = nextPageListings, nextCursor = null))
        }
    }

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = DomainResult.Success(ListingSummaryPage(emptyList(), null))

    override suspend fun getListingDetail(listingId: String): DomainResult<CatalogDetail> =
        DomainResult.Failure(DomainError.NotFound("error.catalog.not_found"))

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        interaction(listingId)

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> {
        viewerInteractionRequestCount += 1
        return if (requiresAuthentication) {
            DomainResult.Failure(DomainError.AuthenticationRequired("error.auth.required"))
        } else {
            DomainResult.Success(viewerInteractions.filter { interaction -> interaction.listingId in listingIds })
        }
    }

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> = interaction(listingId)

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> = interaction(
        listingId,
    )

    private suspend fun interaction(listingId: String): DomainResult<ListingViewerInteraction> {
        interactionGate?.also { gate ->
            interactionGate = null
            gate.await()
        }
        interactionError?.let { error -> return DomainResult.Failure(error) }
        return if (requiresAuthentication) {
            DomainResult.Failure(DomainError.AuthenticationRequired("error.auth.required"))
        } else {
            DomainResult.Success(
                ListingViewerInteraction(
                    listingId = listingId,
                    likedByViewer = true,
                    favoritedByViewer = false,
                    likesCount = 1,
                ),
            )
        }
    }
}

private class ViewModelFavoritesRepository(
    private val behavior: ViewModelCatalogRepository,
) : FavoritesRepository {
    private var clientMutationSequence = TEST_CLIENT_MUTATION_SEQUENCE - 1L

    override suspend fun listFavorites(
        filter: ListingType?,
        page: ListingPageRequest,
    ): DomainResult<FavoriteListingPage> = DomainResult.Success(FavoriteListingPage(emptyList(), null))

    override suspend fun setFavorite(listingId: String, favorited: Boolean): DomainResult<FavoriteMutation> {
        val sequence = ++clientMutationSequence
        behavior.interactionGate?.also { gate ->
            behavior.interactionGate = null
            gate.await()
        }
        val interactionError = behavior.interactionError
        return when {
            interactionError != null -> DomainResult.Failure(interactionError)
            behavior.requiresAuthentication -> DomainResult.Failure(
                DomainError.AuthenticationRequired("error.auth.required"),
            )
            else -> DomainResult.Success(
                FavoriteMutation(
                    listingId = listingId,
                    favorited = favorited,
                    clientMutationSequence = sequence,
                    favoritedAtEpochMilliseconds = if (favorited) {
                        FixedViewModelClock.nowEpochMilliseconds()
                    } else {
                        null
                    },
                ),
            )
        }
    }
}

private object FixedViewModelClock : ClockProvider {
    override fun nowEpochMilliseconds(): Long = 1_000L
}

private fun defaultViewModelCities(): List<City> = listOf(
    City(id = "cotonou", name = "Cotonou", latitude = 6.3703, longitude = 2.3912),
    City(id = "ouidah", name = "Ouidah", latitude = 6.3631, longitude = 2.0851),
)

private fun testListing(id: String = TEST_LISTING_ID): ListingSummary = ListingSummary(
    id = id,
    type = ListingType.Place,
    listingClass = ListingClass.Heritage,
    status = ListingStatus.Published,
    name = "Porte du non-retour",
    cityId = TEST_CITY_ID,
    categoryId = "heritage-historique",
    coverImageUrl = null,
    priceFromXof = null,
    ratingAverage = null,
    likesCount = 0,
    verified = true,
    sponsoredUntilEpochMilliseconds = null,
)

private const val TEST_LISTING_ID = "ouidah-gate"
private const val TEST_CITY_ID = "cotonou"
private const val TEST_CLIENT_MUTATION_SEQUENCE = 4_294_967_297L
