package com.kwabor.shared.app

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.observability.AnalyticsContext
import com.kwabor.shared.domain.observability.AnalyticsEntityType
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.domain.observability.AnalyticsSessionSource
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExploreEffect
import com.kwabor.shared.presentation.explore.ExploreIntent
import com.kwabor.shared.presentation.explore.ExploreInteractionKind
import com.kwabor.shared.presentation.explore.ExploreListingItem
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.initialExploreUiState
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
class IosExploreControllerTest {
    @Test
    fun unconfiguredControllerExposesSafeUnavailableState() = runTest {
        val controller = IosExploreController(
            runtime = null,
            dispatcherProvider = testDispatcherProvider(testScheduler),
            viewerSessionScopeTracker = ViewerSessionScopeTracker(),
        )
        var observedState: ExploreUiState? = null

        controller.observe(
            stateObserver = { state -> observedState = state },
            effectObserver = {},
            favoriteObserver = { _, _, _ -> },
        )
        runCurrent()

        assertFalse(controller.isConfigured)
        assertEquals(controller.strings.configurationUnavailable, controller.currentState.errorMessage)
        assertEquals(controller.currentState, observedState)
        assertTrue(controller.currentState.hasError)
        controller.close()
    }

    @Test
    fun observerIsReplaceableAndUnobserveStopsCallbacks() = runTest {
        val runtime = FakeIosExploreRuntime()
        val controller = configuredController(runtime, testScheduler)
        var firstObserverCalls = 0
        var secondObserverCalls = 0

        controller.observe(
            stateObserver = { firstObserverCalls += 1 },
            effectObserver = {},
            favoriteObserver = { _, _, _ -> },
        )
        runCurrent()
        controller.observe(
            stateObserver = { secondObserverCalls += 1 },
            effectObserver = {},
            favoriteObserver = { _, _, _ -> },
        )
        runCurrent()
        runtime.publishState(runtime.state.value.copy(isRefreshing = true))
        runCurrent()

        assertEquals(1, firstObserverCalls)
        assertEquals(2, secondObserverCalls)

        controller.unobserve()
        runtime.publishState(runtime.state.value.copy(isRefreshing = false))
        runCurrent()

        assertEquals(1, firstObserverCalls)
        assertEquals(2, secondObserverCalls)
        controller.close()
    }

    @Test
    fun actionsMapToSharedRuntimeIntents() = runTest {
        val runtime = FakeIosExploreRuntime()
        val viewerSessionScopeTracker = ViewerSessionScopeTracker()
        val controller = configuredController(runtime, testScheduler, viewerSessionScopeTracker)
        val accountScope = viewerSessionScopeTracker.update("viewer-1", accountSetupComplete = true)

        val guestScope = dispatchAllExploreActions(controller, viewerSessionScopeTracker, accountScope)

        assertEquals(
            expectedExploreIntents(accountScope, guestScope),
            runtime.dispatchedIntents,
        )
        controller.close()
    }

    @Test
    fun viewerReplacementPurgesPrivateStateSynchronouslyAndDropsStaleEpochs() = runTest {
        val runtime = FakeIosExploreRuntime()
        val tracker = ViewerSessionScopeTracker()
        val controller = configuredController(runtime, testScheduler, tracker)
        var observedState = runtime.state.value
        controller.observe(
            stateObserver = { state -> observedState = state },
            effectObserver = {},
            favoriteObserver = { _, _, _ -> },
        )
        runCurrent()

        val accountAFirstScope = tracker.update("account-a", accountSetupComplete = true)
        controller.interactionActions.updateViewerContext(accountAFirstScope)
        val privateAccountAState = privateExploreState(runtime.state.value, accountAFirstScope)
        runtime.publishState(privateAccountAState)
        runCurrent()
        assertTrue(observedState.listings.single().favorited)

        val accountBScope = tracker.update("account-b", accountSetupComplete = true)
        controller.interactionActions.updateViewerContext(accountBScope)

        assertEquals(accountBScope, observedState.viewerScope)
        assertTrue(observedState.listings.none { listing -> listing.liked || listing.favorited })

        runtime.publishState(privateAccountAState)
        runCurrent()

        assertEquals(accountBScope, observedState.viewerScope)
        assertTrue(observedState.listings.none { listing -> listing.liked || listing.favorited })

        val guestScope = tracker.update(null, accountSetupComplete = false)
        controller.interactionActions.updateViewerContext(guestScope)
        val accountASecondScope = tracker.update("account-a", accountSetupComplete = true)
        controller.interactionActions.updateViewerContext(accountASecondScope)
        runtime.publishState(privateAccountAState)
        runCurrent()

        assertEquals(accountASecondScope, observedState.viewerScope)
        assertTrue(observedState.listings.none { listing -> listing.liked || listing.favorited })
        controller.close()
    }

    @Test
    fun sharedEffectsMapToClosedIosEffects() = runTest {
        val runtime = FakeIosExploreRuntime()
        val tracker = ViewerSessionScopeTracker()
        val controller = configuredController(runtime, testScheduler, tracker)
        val observedEffects = mutableListOf<IosExploreEffect>()
        val replayEvent = protectedReplayEvent()
        controller.observe(
            stateObserver = {},
            effectObserver = observedEffects::add,
            favoriteObserver = { _, _, _ -> },
        )
        runCurrent()

        runtime.publishEffect(
            ExploreEffect.AuthenticationRequired(
                kind = ExploreInteractionKind.Favorite,
                suggestedCityId = "cotonou",
                scope = tracker.currentScope,
            ),
        )
        runCurrent()
        val accountScope = tracker.update("account-a", accountSetupComplete = true)
        controller.interactionActions.updateViewerContext(accountScope)
        runtime.publishEffect(
            ExploreEffect.ProtectedActionReplayed(
                kind = ExploreInteractionKind.Favorite,
                listingId = "listing-1",
                analyticsEvent = replayEvent,
                scope = accountScope,
            ),
        )
        runtime.publishEffect(ExploreEffect.RequestLocation)
        runCurrent()

        assertMappedExploreEffects(observedEffects, replayEvent, accountScope)
        controller.close()
    }

    @Test
    fun bufferedAuthenticationEffectsDropAfterEpochChangeWhileLocationRemainsUnscoped() = runTest {
        val runtime = FakeIosExploreRuntime()
        val tracker = ViewerSessionScopeTracker()
        val controller = configuredController(runtime, testScheduler, tracker)
        val observedEffects = mutableListOf<IosExploreEffect>()
        controller.observe(
            stateObserver = {},
            effectObserver = observedEffects::add,
            favoriteObserver = { _, _, _ -> },
        )
        runCurrent()

        val accountASecondScope = publishStaleExploreEffectsAndAdvanceEpoch(runtime, tracker)
        runCurrent()

        assertExploreEffectKinds(observedEffects, IosExploreEffectKind.RequestLocation)

        runtime.publishAuthenticationRequired(accountASecondScope)
        runCurrent()

        assertExploreEffectKinds(observedEffects, IosExploreEffectKind.RequestLocation)

        controller.interactionActions.updateViewerContext(accountASecondScope)
        runtime.publishAuthenticationRequired(accountASecondScope)
        runtime.publishProtectedReplay("current-replay", accountASecondScope)
        runCurrent()

        assertExploreEffectKinds(
            observedEffects,
            IosExploreEffectKind.RequestLocation,
            IosExploreEffectKind.RequireAuthentication,
            IosExploreEffectKind.ProtectedActionReplayed,
        )
        controller.close()
    }

    @Test
    fun scopedFavoriteEffectsDropPreviousAccountAndPreviousEpoch() = runTest {
        val runtime = FakeIosExploreRuntime()
        val tracker = ViewerSessionScopeTracker()
        val controller = configuredController(runtime, testScheduler, tracker)
        val favoriteChanges = mutableListOf<Triple<String, Boolean, ViewerSessionScope>>()
        controller.observe(
            stateObserver = {},
            effectObserver = {},
            favoriteObserver = { listingId, favorited, scope ->
                favoriteChanges += Triple(listingId, favorited, scope)
            },
        )
        runCurrent()

        val accountAFirstScope = tracker.update("account-a", accountSetupComplete = true)
        runtime.publishEffect(ExploreEffect.FavoriteChanged("listing-a", true, accountAFirstScope))
        runCurrent()

        val accountBScope = tracker.update("account-b", accountSetupComplete = true)
        runtime.publishEffect(ExploreEffect.FavoriteChanged("stale-a", false, accountAFirstScope))
        runtime.publishEffect(ExploreEffect.FavoriteChanged("listing-b", true, accountBScope))
        runCurrent()

        tracker.update(null, accountSetupComplete = false)
        val accountASecondScope = tracker.update("account-a", accountSetupComplete = true)
        runtime.publishEffect(ExploreEffect.FavoriteChanged("stale-a-epoch", false, accountAFirstScope))
        runtime.publishEffect(ExploreEffect.FavoriteChanged("listing-a-new", false, accountASecondScope))
        runCurrent()

        assertEquals(
            listOf(
                Triple("listing-a", true, accountAFirstScope),
                Triple("listing-b", true, accountBScope),
                Triple("listing-a-new", false, accountASecondScope),
            ),
            favoriteChanges,
        )
        controller.close()
    }

    @Test
    fun closeIsIdempotentStopsCallbacksAndRejectsFurtherActions() = runTest {
        val runtime = FakeIosExploreRuntime()
        val controller = configuredController(runtime, testScheduler)
        var stateCallbacks = 0
        var effectCallbacks = 0
        controller.observe(
            stateObserver = { stateCallbacks += 1 },
            effectObserver = { effectCallbacks += 1 },
            favoriteObserver = { _, _, _ -> effectCallbacks += 1 },
        )
        runCurrent()

        controller.close()
        controller.close()
        runtime.publishState(runtime.state.value.copy(isRefreshing = true))
        runtime.publishEffect(ExploreEffect.RequestLocation)
        controller.feedActions.refresh()
        runCurrent()

        assertEquals(1, runtime.closeCalls)
        assertEquals(1, stateCallbacks)
        assertEquals(0, effectCallbacks)
        assertTrue(runtime.dispatchedIntents.isEmpty())
    }

    private fun configuredController(
        runtime: FakeIosExploreRuntime,
        scheduler: TestCoroutineScheduler,
        viewerSessionScopeTracker: ViewerSessionScopeTracker = ViewerSessionScopeTracker(),
    ): IosExploreController = IosExploreController(
        runtime = runtime,
        dispatcherProvider = testDispatcherProvider(scheduler),
        viewerSessionScopeTracker = viewerSessionScopeTracker,
    )
}

private fun dispatchAllExploreActions(
    controller: IosExploreController,
    tracker: ViewerSessionScopeTracker,
    accountScope: ViewerSessionScope,
): ViewerSessionScope {
    controller.feedActions.selectPlacesTab()
    controller.feedActions.selectEventsTab()
    controller.feedActions.selectHotelsRestaurantsTab()
    controller.feedActions.selectChip("event-culture")
    controller.feedActions.retry()
    controller.feedActions.refresh()
    controller.feedActions.loadNext()
    controller.cityActions.openCitySelector()
    controller.cityActions.closeCitySelector()
    controller.cityActions.selectCity("ouidah")
    controller.cityActions.requestLocation()
    controller.cityActions.locationCoordinates(latitude = 6.37, longitude = 2.08)
    controller.cityActions.locationPermissionDenied()
    controller.cityActions.locationDisabled()
    controller.cityActions.locationUnavailable()
    controller.interactionActions.toggleLike("listing-1")
    controller.interactionActions.toggleFavorite("listing-2")
    controller.interactionActions.replayPendingInteraction()
    controller.interactionActions.updateViewerContext(accountScope)
    controller.interactionActions.applyFavoriteState("listing-2", favorited = false, scope = accountScope)
    val guestScope = tracker.update(null, accountSetupComplete = false)
    controller.interactionActions.updateViewerContext(guestScope)
    controller.interactionActions.clearPendingAuthentication()
    return guestScope
}

private fun expectedExploreIntents(
    accountScope: ViewerSessionScope,
    guestScope: ViewerSessionScope,
): List<ExploreIntent> = listOf(
    ExploreIntent.SelectTab(ExploreTab.Places),
    ExploreIntent.SelectTab(ExploreTab.Events),
    ExploreIntent.SelectTab(ExploreTab.HotelsRestaurants),
    ExploreIntent.SelectChip("event-culture"),
    ExploreIntent.Retry,
    ExploreIntent.Refresh,
    ExploreIntent.LoadNext,
    ExploreIntent.OpenCitySelector,
    ExploreIntent.CloseCitySelector,
    ExploreIntent.SelectCity("ouidah"),
    ExploreIntent.RequestLocation,
    ExploreIntent.LocationCoordinates(latitude = 6.37, longitude = 2.08),
    ExploreIntent.LocationPermissionDenied,
    ExploreIntent.LocationDisabled,
    ExploreIntent.LocationUnavailable,
    ExploreIntent.ToggleLike("listing-1"),
    ExploreIntent.ToggleFavorite("listing-2"),
    ExploreIntent.ReplayPendingInteraction,
    ExploreIntent.ViewerContextChanged(accountScope),
    ExploreIntent.FavoriteStateChanged("listing-2", favorited = false, scope = accountScope),
    ExploreIntent.ViewerContextChanged(guestScope),
    ExploreIntent.ClearPendingAuthentication,
)

private fun privateExploreState(state: ExploreUiState, scope: ViewerSessionScope): ExploreUiState = state.copy(
    listings = listOf(
        ExploreListingItem(
            id = "listing-private-a",
            title = "Private selection",
            cityLabel = "Cotonou",
            coverImageUrl = null,
            price = null,
            liked = true,
            favorited = true,
        ),
    ),
    viewerScope = scope,
)

private fun protectedReplayEvent(): AnalyticsEvent = AnalyticsEvent(
    name = AnalyticsEventName.ProtectedActionReplayed,
    context = AnalyticsContext(
        cityId = "cotonou",
        entityType = AnalyticsEntityType.Event,
        entityId = "listing-1",
        sessionSource = AnalyticsSessionSource.Sponsored,
    ),
)

private fun assertMappedExploreEffects(
    effects: List<IosExploreEffect>,
    replayEvent: AnalyticsEvent,
    accountScope: ViewerSessionScope,
) {
    assertExploreEffectKinds(
        effects,
        IosExploreEffectKind.RequireAuthentication,
        IosExploreEffectKind.ProtectedActionReplayed,
        IosExploreEffectKind.RequestLocation,
    )
    assertTrue(effects.first().requiresAuthentication)
    assertEquals(ViewerSessionScope.InitialGuest, effects.first().scope)
    assertTrue(effects[1].replaysProtectedAction)
    assertEquals(replayEvent, effects[1].replayAnalyticsEvent)
    assertEquals(accountScope, effects[1].scope)
    assertTrue(effects.last().requestsLocation)
    assertEquals(null, effects.last().scope)
}

private fun publishStaleExploreEffectsAndAdvanceEpoch(
    runtime: FakeIosExploreRuntime,
    tracker: ViewerSessionScopeTracker,
): ViewerSessionScope {
    val firstScope = tracker.update("account-a", accountSetupComplete = true)
    runtime.publishAuthenticationRequired(firstScope, suggestedCityId = "cotonou")
    runtime.publishProtectedReplay("stale-replay", firstScope)
    runtime.publishEffect(ExploreEffect.RequestLocation)
    tracker.update(null, accountSetupComplete = false)
    return tracker.update("account-a", accountSetupComplete = true)
}

private fun FakeIosExploreRuntime.publishAuthenticationRequired(
    scope: ViewerSessionScope,
    suggestedCityId: String? = null,
) {
    publishEffect(
        ExploreEffect.AuthenticationRequired(
            kind = ExploreInteractionKind.Favorite,
            suggestedCityId = suggestedCityId,
            scope = scope,
        ),
    )
}

private fun FakeIosExploreRuntime.publishProtectedReplay(listingId: String, scope: ViewerSessionScope) {
    publishEffect(
        ExploreEffect.ProtectedActionReplayed(
            kind = ExploreInteractionKind.Favorite,
            listingId = listingId,
            analyticsEvent = null,
            scope = scope,
        ),
    )
}

private fun assertExploreEffectKinds(effects: List<IosExploreEffect>, vararg expectedKinds: IosExploreEffectKind) {
    assertEquals(expectedKinds.toList(), effects.map(IosExploreEffect::kind))
}

private class FakeIosExploreRuntime : IosExploreRuntime {
    private val mutableState = MutableStateFlow(initialExploreUiState(stringsFor(AppLocale.French)))
    private val effectChannel = Channel<ExploreEffect>(capacity = Channel.UNLIMITED)
    override val state: StateFlow<ExploreUiState> = mutableState
    override val effects: Flow<ExploreEffect> = effectChannel.receiveAsFlow()
    val dispatchedIntents = mutableListOf<ExploreIntent>()
    var closeCalls: Int = 0
        private set

    override fun dispatch(intent: ExploreIntent) {
        dispatchedIntents += intent
        if (intent is ExploreIntent.ViewerContextChanged) {
            val current = mutableState.value
            if (intent.scope.epoch > current.viewerScope.epoch) {
                mutableState.value = current.copy(
                    listings = current.listings.map { listing ->
                        listing.copy(liked = false, favorited = false)
                    },
                    interactionMessage = null,
                    pendingAuthInteraction = null,
                    queuedInteractions = emptyList(),
                    viewerScope = intent.scope,
                )
            }
        }
    }

    override fun close() {
        closeCalls += 1
    }

    fun publishState(state: ExploreUiState) {
        mutableState.value = state
    }

    fun publishEffect(effect: ExploreEffect) {
        effectChannel.trySend(effect)
    }
}

private fun testDispatcherProvider(scheduler: TestCoroutineScheduler): DispatcherProvider {
    val dispatcher = StandardTestDispatcher(scheduler)
    return object : DispatcherProvider {
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }
}
