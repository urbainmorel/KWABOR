package com.kwabor.shared.app

import com.kwabor.shared.presentation.favorites.FavoritesEffect
import com.kwabor.shared.presentation.favorites.FavoritesFilter
import com.kwabor.shared.presentation.favorites.FavoritesIntent
import com.kwabor.shared.presentation.favorites.FavoritesUiState
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosFavoritesControllerTest {
    @Test
    fun unconfiguredControllerExposesSafeUnavailableState() = runTest {
        val controller = IosFavoritesController(
            runtime = null,
            dispatcherProvider = favoritesTestDispatcherProvider(testScheduler),
            viewerSessionScopeTracker = ViewerSessionScopeTracker(),
        )
        var observedState: FavoritesUiState? = null

        controller.observe(
            stateObserver = { state -> observedState = state },
            detailObserver = { _, _ -> },
            favoriteObserver = { _, _, _, _ -> },
        )
        runCurrent()

        assertFalse(controller.isConfigured)
        assertEquals(controller.strings.loadFailed, controller.currentState.errorMessage)
        assertEquals(controller.currentState, observedState)
        assertFalse(controller.currentState.isLoading)
        controller.close()
    }

    @Test
    fun actionsMapToSharedRuntimeIntents() = runTest {
        val runtime = FakeIosFavoritesRuntime()
        val tracker = ViewerSessionScopeTracker()
        val controller = configuredFavoritesController(runtime, testScheduler, tracker)
        val accountScope = tracker.update("account-a", accountSetupComplete = true)
        val guestScope = tracker.update(null, accountSetupComplete = false)

        dispatchAllFavoritesActions(controller, accountScope, guestScope)

        assertEquals(
            expectedFavoritesIntents(accountScope, guestScope),
            runtime.dispatchedIntents,
        )
        controller.close()
    }

    @Test
    fun effectsAreDeliveredToTheirDedicatedObservers() = runTest {
        val runtime = FakeIosFavoritesRuntime()
        val tracker = ViewerSessionScopeTracker()
        val controller = configuredFavoritesController(runtime, testScheduler, tracker)
        val accountScope = tracker.update("account-a", accountSetupComplete = true)
        val observations = controller.observeFavoriteEffects()
        runCurrent()
        runtime.publishEffect(FavoritesEffect.OpenCatalogDetail("listing-1", accountScope))
        runtime.publishFavoriteChanged("listing-2", TEST_CLIENT_MUTATION_SEQUENCE, accountScope)
        runCurrent()

        assertEquals(listOf("listing-1" to accountScope), observations.openedListings)
        assertEquals(
            listOf(observedFavoritesChange("listing-2", TEST_CLIENT_MUTATION_SEQUENCE, accountScope)),
            observations.favoriteChanges,
        )

        controller.unobserve()
        runtime.publishEffect(FavoritesEffect.OpenCatalogDetail("listing-3", accountScope))
        runtime.publishFavoriteChanged("listing-4", TEST_CLIENT_MUTATION_SEQUENCE + 1L, accountScope)
        runCurrent()

        assertEquals(listOf("listing-1" to accountScope), observations.openedListings)
        assertEquals(
            listOf(observedFavoritesChange("listing-2", TEST_CLIENT_MUTATION_SEQUENCE, accountScope)),
            observations.favoriteChanges,
        )
        controller.close()
    }

    @Test
    fun viewerReplacementPublishesPrivateResetSynchronouslyAndDropsStaleStates() = runTest {
        val runtime = FakeIosFavoritesRuntime()
        val tracker = ViewerSessionScopeTracker()
        val controller = configuredFavoritesController(runtime, testScheduler, tracker)
        val accountAScope = tracker.update("account-a", accountSetupComplete = true)
        var observedState = FavoritesUiState()
        controller.observe(
            stateObserver = { state -> observedState = state },
            detailObserver = { _, _ -> },
            favoriteObserver = { _, _, _, _ -> },
        )
        runCurrent()
        runtime.publishState(privateFavoritesState("private-account-a-state"))
        runCurrent()

        controller.actions.updateViewerContext(accountAScope)
        val accountBScope = tracker.update("account-b", accountSetupComplete = true)
        controller.actions.updateViewerContext(accountBScope)

        assertFavoritesReset(accountBScope, controller.currentState)
        assertEquals(controller.currentState, observedState)

        runtime.publishState(privateFavoritesState("late-private-account-a-state", accountAScope))
        runCurrent()

        assertFavoritesReset(accountBScope, observedState)

        val guestScope = tracker.update(null, accountSetupComplete = false)
        controller.actions.updateViewerContext(guestScope)
        val accountASecondScope = tracker.update("account-a", accountSetupComplete = true)
        controller.actions.updateViewerContext(accountASecondScope)
        runtime.publishState(privateFavoritesState("late-private-account-a-first-epoch", accountAScope))
        runCurrent()

        assertFavoritesReset(accountASecondScope, observedState)
        controller.close()
    }

    @Test
    fun scopedEffectsDropPreviousAccountAndPreviousEpoch() = runTest {
        val runtime = FakeIosFavoritesRuntime()
        val tracker = ViewerSessionScopeTracker()
        val controller = configuredFavoritesController(runtime, testScheduler, tracker)
        val observations = controller.observeFavoriteEffects()
        runCurrent()

        val accountAFirstScope = tracker.update("account-a", accountSetupComplete = true)
        runtime.publishEffect(FavoritesEffect.OpenCatalogDetail("listing-a", accountAFirstScope))
        runCurrent()

        val accountBScope = tracker.update("account-b", accountSetupComplete = true)
        runtime.publishEffect(FavoritesEffect.OpenCatalogDetail("stale-a", accountAFirstScope))
        runtime.publishFavoriteChanged("listing-b", TEST_CLIENT_MUTATION_SEQUENCE, accountBScope)
        runCurrent()

        tracker.update(null, accountSetupComplete = false)
        val accountASecondScope = tracker.update("account-a", accountSetupComplete = true)
        runtime.publishFavoriteChanged("stale-a-epoch", TEST_CLIENT_MUTATION_SEQUENCE + 1L, accountAFirstScope)
        runtime.publishEffect(FavoritesEffect.OpenCatalogDetail("listing-a-new", accountASecondScope))
        runCurrent()

        assertEquals(
            listOf(
                "listing-a" to accountAFirstScope,
                "listing-a-new" to accountASecondScope,
            ),
            observations.openedListings,
        )
        assertEquals(
            listOf(observedFavoritesChange("listing-b", TEST_CLIENT_MUTATION_SEQUENCE, accountBScope)),
            observations.favoriteChanges,
        )
        controller.close()
    }

    @Test
    fun observerIsReplaceableAndCloseRejectsFurtherWork() = runTest {
        val runtime = FakeIosFavoritesRuntime()
        val controller = configuredFavoritesController(runtime, testScheduler)
        var firstObserverCalls = 0
        var secondObserverCalls = 0

        controller.observe(
            stateObserver = { firstObserverCalls += 1 },
            detailObserver = { _, _ -> },
            favoriteObserver = { _, _, _, _ -> },
        )
        runCurrent()
        controller.observe(
            stateObserver = { secondObserverCalls += 1 },
            detailObserver = { _, _ -> },
            favoriteObserver = { _, _, _, _ -> },
        )
        runCurrent()
        runtime.publishState(runtime.state.value.copy(isRefreshing = true))
        runCurrent()

        assertEquals(1, firstObserverCalls)
        assertEquals(2, secondObserverCalls)

        controller.close()
        controller.close()
        runtime.publishState(runtime.state.value.copy(isRefreshing = false))
        runtime.publishEffect(FavoritesEffect.OpenCatalogDetail("listing-1", ViewerSessionScope.InitialGuest))
        controller.actions.refresh()
        runCurrent()

        assertEquals(1, runtime.closeCalls)
        assertEquals(2, secondObserverCalls)
        assertTrue(runtime.dispatchedIntents.isEmpty())
    }

    private fun configuredFavoritesController(
        runtime: FakeIosFavoritesRuntime,
        scheduler: TestCoroutineScheduler,
        viewerSessionScopeTracker: ViewerSessionScopeTracker = ViewerSessionScopeTracker(),
    ): IosFavoritesController = IosFavoritesController(
        runtime = runtime,
        dispatcherProvider = favoritesTestDispatcherProvider(scheduler),
        viewerSessionScopeTracker = viewerSessionScopeTracker,
    )
}

private data class FavoritesEffectObservations(
    val openedListings: MutableList<Pair<String, ViewerSessionScope>> = mutableListOf(),
    val favoriteChanges: MutableList<ObservedFavoritesChange> = mutableListOf(),
)

private fun IosFavoritesController.observeFavoriteEffects(): FavoritesEffectObservations {
    val observations = FavoritesEffectObservations()
    observe(
        stateObserver = {},
        detailObserver = { listingId, scope -> observations.openedListings += listingId to scope },
        favoriteObserver = { listingId, favorited, clientMutationSequence, scope ->
            observations.favoriteChanges += ObservedFavoritesChange(
                listingId = listingId,
                favorited = favorited,
                clientMutationSequence = clientMutationSequence,
                scope = scope,
            )
        },
    )
    return observations
}

private fun FakeIosFavoritesRuntime.publishFavoriteChanged(
    listingId: String,
    clientMutationSequence: Long,
    scope: ViewerSessionScope,
) {
    publishEffect(
        FavoritesEffect.FavoriteChanged(
            listingId = listingId,
            favorited = false,
            clientMutationSequence = clientMutationSequence,
            scope = scope,
        ),
    )
}

private fun observedFavoritesChange(
    listingId: String,
    clientMutationSequence: Long,
    scope: ViewerSessionScope,
): ObservedFavoritesChange = ObservedFavoritesChange(
    listingId = listingId,
    favorited = false,
    clientMutationSequence = clientMutationSequence,
    scope = scope,
)

private fun dispatchAllFavoritesActions(
    controller: IosFavoritesController,
    accountScope: ViewerSessionScope,
    guestScope: ViewerSessionScope,
) {
    controller.actions.screenAppeared()
    controller.actions.screenDisappeared()
    controller.actions.updateViewerContext(accountScope)
    controller.actions.applyExternalFavoriteState(
        listingId = "listing-0",
        favorited = true,
        clientMutationSequence = TEST_CLIENT_MUTATION_SEQUENCE,
        scope = accountScope,
    )
    controller.actions.updateViewerContext(guestScope)
    controller.actions.selectAll()
    controller.actions.selectPlaces()
    controller.actions.selectEvents()
    controller.actions.selectHotelsRestaurants()
    controller.actions.retry()
    controller.actions.refresh()
    controller.actions.loadNext()
    controller.actions.removeFavorite("listing-1")
    controller.actions.openListing("listing-2")
}

private fun expectedFavoritesIntents(
    accountScope: ViewerSessionScope,
    guestScope: ViewerSessionScope,
): List<FavoritesIntent> = listOf(
    FavoritesIntent.ScreenAppeared,
    FavoritesIntent.ScreenDisappeared,
    FavoritesIntent.ViewerContextChanged(accountScope),
    FavoritesIntent.ExternalFavoriteStateChanged(
        listingId = "listing-0",
        favorited = true,
        clientMutationSequence = TEST_CLIENT_MUTATION_SEQUENCE,
        scope = accountScope,
    ),
    FavoritesIntent.ViewerContextChanged(guestScope),
    FavoritesIntent.SelectFilter(FavoritesFilter.All),
    FavoritesIntent.SelectFilter(FavoritesFilter.Places),
    FavoritesIntent.SelectFilter(FavoritesFilter.Events),
    FavoritesIntent.SelectFilter(FavoritesFilter.HotelsRestaurants),
    FavoritesIntent.Retry,
    FavoritesIntent.Refresh,
    FavoritesIntent.LoadNext,
    FavoritesIntent.RemoveFavorite("listing-1"),
    FavoritesIntent.OpenListing("listing-2"),
)

private fun privateFavoritesState(
    message: String,
    scope: ViewerSessionScope = ViewerSessionScope.InitialGuest,
): FavoritesUiState = FavoritesUiState(
    isAccountReady = true,
    errorMessage = message,
    viewerScope = scope,
)

private fun assertFavoritesReset(scope: ViewerSessionScope, actual: FavoritesUiState) {
    assertEquals(FavoritesUiState(isAccountReady = true, viewerScope = scope), actual)
}

private data class ObservedFavoritesChange(
    val listingId: String,
    val favorited: Boolean,
    val clientMutationSequence: Long,
    val scope: ViewerSessionScope,
)

private class FakeIosFavoritesRuntime : IosFavoritesRuntime {
    private val mutableState = MutableStateFlow(FavoritesUiState())
    private val effectChannel = Channel<FavoritesEffect>(capacity = Channel.UNLIMITED)
    override val state: StateFlow<FavoritesUiState> = mutableState
    override val effects: Flow<FavoritesEffect> = effectChannel.receiveAsFlow()
    val dispatchedIntents = mutableListOf<FavoritesIntent>()
    var closeCalls = 0
        private set

    override fun dispatch(intent: FavoritesIntent) {
        dispatchedIntents += intent
        if (intent is FavoritesIntent.ViewerContextChanged) {
            mutableState.value = FavoritesUiState(
                selectedFilter = mutableState.value.selectedFilter,
                isAccountReady = intent.scope.isAuthenticated,
                viewerScope = intent.scope,
            )
        }
    }

    override fun close() {
        closeCalls += 1
    }

    fun publishState(state: FavoritesUiState) {
        mutableState.value = state
    }

    fun publishEffect(effect: FavoritesEffect) {
        effectChannel.trySend(effect)
    }
}

private fun favoritesTestDispatcherProvider(scheduler: TestCoroutineScheduler): DispatcherProvider {
    val dispatcher = StandardTestDispatcher(scheduler)
    return object : DispatcherProvider {
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }
}

private const val TEST_CLIENT_MUTATION_SEQUENCE = 4_294_967_297L
