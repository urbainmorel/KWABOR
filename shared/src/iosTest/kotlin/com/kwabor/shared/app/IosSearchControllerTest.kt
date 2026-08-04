package com.kwabor.shared.app

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.initialExploreUiState
import com.kwabor.shared.presentation.search.SearchEffect
import com.kwabor.shared.presentation.search.SearchIntent
import com.kwabor.shared.presentation.search.SearchScope
import com.kwabor.shared.presentation.search.SearchUiState
import com.kwabor.shared.presentation.search.toSearchContext
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosSearchControllerTest {
    private val exploreState = initialExploreUiState(stringsFor(AppLocale.French))

    @Test
    fun unconfiguredControllerExposesSafeUnavailableState() = runTest {
        val controller = IosSearchController(
            runtime = null,
            dispatcherProvider = searchTestDispatcherProvider(testScheduler),
        )
        var observedState: SearchUiState? = null

        controller.observe(
            stateObserver = { state -> observedState = state },
            effectObserver = {},
        )
        runCurrent()

        assertFalse(controller.isConfigured)
        assertEquals(controller.strings.loadFailed, controller.currentState.errorMessage)
        assertEquals(controller.currentState, observedState)
        controller.close()
    }

    @Test
    fun actionsMapToSharedRuntimeIntents() = runTest {
        val runtime = FakeIosSearchRuntime()
        val controller = configuredSearchController(runtime, testScheduler)

        controller.actions.activate(exploreState)
        controller.actions.updateExploreContext(exploreState)
        controller.actions.queryChanged("Ouidah")
        controller.actions.selectActiveTabScope()
        controller.actions.selectAllScope()
        controller.actions.submit()
        controller.actions.clear()
        controller.actions.close()
        controller.actions.retry()
        controller.actions.refresh()
        controller.actions.loadNext()
        controller.actions.openListing("listing-1")
        controller.actions.openAssistant()

        assertEquals(
            listOf(
                SearchIntent.Activate(exploreState.toSearchContext()),
                SearchIntent.UpdateContext(exploreState.toSearchContext()),
                SearchIntent.QueryChanged("Ouidah"),
                SearchIntent.SelectScope(SearchScope.ActiveTab),
                SearchIntent.SelectScope(SearchScope.All),
                SearchIntent.Submit,
                SearchIntent.Clear,
                SearchIntent.Close,
                SearchIntent.Retry,
                SearchIntent.Refresh,
                SearchIntent.LoadNext,
                SearchIntent.OpenListing("listing-1"),
                SearchIntent.OpenAssistant,
            ),
            runtime.dispatchedIntents,
        )
        controller.close()
    }

    @Test
    fun effectsExposeSemanticFlagsAndUnobserveStopsCallbacks() = runTest {
        val runtime = FakeIosSearchRuntime()
        val controller = configuredSearchController(runtime, testScheduler)
        val observedEffects = mutableListOf<IosSearchEffect>()
        var stateCallbacks = 0
        controller.observe(
            stateObserver = { stateCallbacks += 1 },
            effectObserver = observedEffects::add,
        )
        runCurrent()

        runtime.publishEffect(SearchEffect.QuerySubmitted(KwaborCurrency.Eur))
        runtime.publishEffect(SearchEffect.OpenCatalogDetail("listing-1"))
        runtime.publishEffect(SearchEffect.OpenAssistant)
        runCurrent()

        assertTrue(observedEffects[0].submitsQuery)
        assertEquals(
            KwaborCurrency.Eur,
            observedEffects[0].querySubmittedEvent?.context?.displayCurrency,
        )
        assertTrue(observedEffects[1].opensCatalogDetail)
        assertEquals("listing-1", observedEffects[1].listingId)
        assertTrue(observedEffects[2].opensAssistant)
        assertNull(observedEffects[2].listingId)
        assertEquals(1, stateCallbacks)

        controller.unobserve()
        runtime.publishState(runtime.state.value.copy(isRefreshing = true))
        runtime.publishEffect(SearchEffect.OpenAssistant)
        runCurrent()

        assertEquals(1, stateCallbacks)
        assertEquals(3, observedEffects.size)
        controller.close()
    }

    @Test
    fun closeIsIdempotentStopsCallbacksAndRejectsFurtherActions() = runTest {
        val runtime = FakeIosSearchRuntime()
        val controller = configuredSearchController(runtime, testScheduler)
        var stateCallbacks = 0
        controller.observe(
            stateObserver = { stateCallbacks += 1 },
            effectObserver = {},
        )
        runCurrent()

        controller.close()
        controller.close()
        runtime.publishState(runtime.state.value.copy(isRefreshing = true))
        controller.actions.refresh()
        runCurrent()

        assertEquals(1, runtime.closeCalls)
        assertEquals(1, stateCallbacks)
        assertTrue(runtime.dispatchedIntents.isEmpty())
    }

    private fun configuredSearchController(
        runtime: FakeIosSearchRuntime,
        scheduler: TestCoroutineScheduler,
    ): IosSearchController = IosSearchController(
        runtime = runtime,
        dispatcherProvider = searchTestDispatcherProvider(scheduler),
    )
}

private class FakeIosSearchRuntime : IosSearchRuntime {
    private val mutableState = MutableStateFlow(SearchUiState())
    private val effectChannel = Channel<SearchEffect>(capacity = Channel.UNLIMITED)
    override val state: StateFlow<SearchUiState> = mutableState
    override val effects: Flow<SearchEffect> = effectChannel.receiveAsFlow()
    val dispatchedIntents = mutableListOf<SearchIntent>()
    var closeCalls = 0
        private set

    override fun dispatch(intent: SearchIntent) {
        dispatchedIntents += intent
    }

    override fun close() {
        closeCalls += 1
    }

    fun publishState(state: SearchUiState) {
        mutableState.value = state
    }

    fun publishEffect(effect: SearchEffect) {
        effectChannel.trySend(effect)
    }
}

private fun searchTestDispatcherProvider(scheduler: TestCoroutineScheduler): DispatcherProvider {
    val dispatcher = StandardTestDispatcher(scheduler)
    return object : DispatcherProvider {
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }
}
