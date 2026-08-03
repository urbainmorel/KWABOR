package com.kwabor.shared.app

import com.kwabor.shared.presentation.guide.GuideDiscoveryEffect
import com.kwabor.shared.presentation.guide.GuideDiscoveryIntent
import com.kwabor.shared.presentation.guide.GuideDiscoveryUiState
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
class IosGuideDiscoveryControllerTest {
    @Test
    fun unconfiguredControllerExposesSafeUnavailableState() = runTest {
        val controller = IosGuideDiscoveryController(
            runtime = null,
            dispatcherProvider = guideTestDispatcherProvider(testScheduler),
        )
        var observedState: GuideDiscoveryUiState? = null

        controller.observe(
            stateObserver = { state -> observedState = state },
            detailObserver = {},
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
        val runtime = FakeIosGuideDiscoveryRuntime()
        val controller = configuredGuideController(runtime, testScheduler)

        controller.actions.start()
        controller.actions.retry()
        controller.actions.refresh()
        controller.actions.loadNext()
        controller.actions.selectCity("ouidah")
        controller.actions.selectCity(null)
        controller.actions.selectLanguage("portugais")
        controller.actions.selectLanguage(null)
        controller.actions.selectSpecialty("histoire")
        controller.actions.selectSpecialty(null)
        controller.actions.clearFilters()
        controller.actions.openGuide("a1000000-0000-4000-8000-000000000001")

        assertEquals(
            listOf(
                GuideDiscoveryIntent.Start,
                GuideDiscoveryIntent.Retry,
                GuideDiscoveryIntent.Refresh,
                GuideDiscoveryIntent.LoadNext,
                GuideDiscoveryIntent.SelectCity("ouidah"),
                GuideDiscoveryIntent.SelectCity(null),
                GuideDiscoveryIntent.SelectLanguage("portugais"),
                GuideDiscoveryIntent.SelectLanguage(null),
                GuideDiscoveryIntent.SelectSpecialty("histoire"),
                GuideDiscoveryIntent.SelectSpecialty(null),
                GuideDiscoveryIntent.ClearFilters,
                GuideDiscoveryIntent.OpenGuide("a1000000-0000-4000-8000-000000000001"),
            ),
            runtime.dispatchedIntents,
        )
        controller.close()
    }

    @Test
    fun detailEffectIsDeliveredAndUnobserveStopsCallbacks() = runTest {
        val runtime = FakeIosGuideDiscoveryRuntime()
        val controller = configuredGuideController(runtime, testScheduler)
        val openedListings = mutableListOf<String>()
        var stateCallbacks = 0
        controller.observe(
            stateObserver = { stateCallbacks += 1 },
            detailObserver = openedListings::add,
        )
        runCurrent()

        runtime.publishEffect(
            GuideDiscoveryEffect.OpenCatalogDetail("a1000000-0000-4000-8000-000000000001"),
        )
        runCurrent()

        assertEquals(listOf("a1000000-0000-4000-8000-000000000001"), openedListings)
        assertEquals(1, stateCallbacks)

        controller.unobserve()
        runtime.publishState(runtime.state.value.copy(isRefreshing = true))
        runtime.publishEffect(
            GuideDiscoveryEffect.OpenCatalogDetail("a1000000-0000-4000-8000-000000000002"),
        )
        runCurrent()

        assertEquals(1, stateCallbacks)
        assertEquals(1, openedListings.size)
        controller.close()
    }

    @Test
    fun closeIsIdempotentStopsCallbacksAndRejectsFurtherActions() = runTest {
        val runtime = FakeIosGuideDiscoveryRuntime()
        val controller = configuredGuideController(runtime, testScheduler)
        var stateCallbacks = 0
        controller.observe(
            stateObserver = { stateCallbacks += 1 },
            detailObserver = {},
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

    private fun configuredGuideController(
        runtime: FakeIosGuideDiscoveryRuntime,
        scheduler: TestCoroutineScheduler,
    ): IosGuideDiscoveryController = IosGuideDiscoveryController(
        runtime = runtime,
        dispatcherProvider = guideTestDispatcherProvider(scheduler),
    )
}

private class FakeIosGuideDiscoveryRuntime : IosGuideDiscoveryRuntime {
    private val mutableState = MutableStateFlow(GuideDiscoveryUiState())
    private val effectChannel = Channel<GuideDiscoveryEffect>(capacity = Channel.UNLIMITED)
    override val state: StateFlow<GuideDiscoveryUiState> = mutableState
    override val effects: Flow<GuideDiscoveryEffect> = effectChannel.receiveAsFlow()
    val dispatchedIntents = mutableListOf<GuideDiscoveryIntent>()
    var closeCalls = 0
        private set

    override fun dispatch(intent: GuideDiscoveryIntent) {
        dispatchedIntents += intent
    }

    override fun close() {
        closeCalls += 1
    }

    fun publishState(state: GuideDiscoveryUiState) {
        mutableState.value = state
    }

    fun publishEffect(effect: GuideDiscoveryEffect) {
        effectChannel.trySend(effect)
    }
}

private fun guideTestDispatcherProvider(scheduler: TestCoroutineScheduler): DispatcherProvider {
    val dispatcher = StandardTestDispatcher(scheduler)
    return object : DispatcherProvider {
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }
}
