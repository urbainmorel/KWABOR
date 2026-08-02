package com.kwabor.shared.app

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExploreEffect
import com.kwabor.shared.presentation.explore.ExploreIntent
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.initialExploreUiState
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
        )
        var observedState: ExploreUiState? = null

        controller.observe(
            stateObserver = { state -> observedState = state },
            effectObserver = {},
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
        )
        runCurrent()
        controller.observe(
            stateObserver = { secondObserverCalls += 1 },
            effectObserver = {},
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
        val controller = configuredController(runtime, testScheduler)

        controller.feedActions.selectTab(ExploreTab.Events)
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
        controller.interactionActions.updateViewerContext("viewer-1")
        controller.interactionActions.updateViewerContext(null)

        assertEquals(
            listOf(
                ExploreIntent.SelectTab(ExploreTab.Events),
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
                ExploreIntent.ViewerContextChanged("viewer-1"),
                ExploreIntent.ViewerContextChanged(null),
            ),
            runtime.dispatchedIntents,
        )
        controller.close()
    }

    @Test
    fun sharedEffectsMapToClosedIosEffects() = runTest {
        val runtime = FakeIosExploreRuntime()
        val controller = configuredController(runtime, testScheduler)
        val observedEffects = mutableListOf<IosExploreEffect>()
        controller.observe(
            stateObserver = {},
            effectObserver = observedEffects::add,
        )
        runCurrent()

        runtime.publishEffect(ExploreEffect.AuthenticationRequired)
        runtime.publishEffect(ExploreEffect.RequestLocation)
        runCurrent()

        assertEquals(
            listOf(IosExploreEffect.RequireAuthentication, IosExploreEffect.RequestLocation),
            observedEffects,
        )
        assertTrue(observedEffects.first().requiresAuthentication)
        assertTrue(observedEffects.last().requestsLocation)
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
    ): IosExploreController = IosExploreController(
        runtime = runtime,
        dispatcherProvider = testDispatcherProvider(scheduler),
    )
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
