package com.kwabor.shared.app

import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.detail.CatalogDetailUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosCatalogDetailControllerTest {
    @Test
    fun unconfiguredControllerFailsSafelyWhenOpeningAndCanClose() = runTest {
        val controller = IosCatalogDetailController(
            runtime = null,
            dispatcherProvider = detailTestDispatcherProvider(testScheduler),
        )
        val observedStates = mutableListOf<CatalogDetailUiState>()
        controller.observe(observedStates::add)
        runCurrent()

        controller.actions.open("  listing-1  ")
        assertEquals(CatalogDetailUiState.Closed, controller.currentState)
        runCurrent()
        val failure = assertIs<CatalogDetailUiState.Failure>(controller.currentState)

        assertFalse(controller.isConfigured)
        assertEquals("listing-1", failure.listingId)
        assertEquals(controller.strings.configurationUnavailable, failure.message)
        assertEquals(failure, observedStates.last())

        controller.actions.close()
        runCurrent()
        assertEquals(CatalogDetailUiState.Closed, controller.currentState)
        controller.close()
    }

    @Test
    fun actionsMapToSharedRuntimeIntents() = runTest {
        val runtime = FakeIosCatalogDetailRuntime()
        val controller = configuredDetailController(runtime, testScheduler)

        controller.actions.open("listing-1")
        controller.actions.retry()
        controller.actions.selectMedia(-1)
        controller.actions.selectMedia(2)
        controller.actions.selectMedia(Int.MAX_VALUE)
        controller.actions.toggleDescription()
        controller.actions.close()

        assertEquals(
            listOf(
                CatalogDetailIntent.Open("listing-1"),
                CatalogDetailIntent.Retry,
                CatalogDetailIntent.SelectMedia(-1),
                CatalogDetailIntent.SelectMedia(2),
                CatalogDetailIntent.SelectMedia(Int.MAX_VALUE),
                CatalogDetailIntent.ToggleDescription,
                CatalogDetailIntent.Close,
            ),
            runtime.dispatchedIntents,
        )
        controller.close()
    }

    @Test
    fun observerIsReplaceableAndUnobserveStopsCallbacks() = runTest {
        val runtime = FakeIosCatalogDetailRuntime()
        val controller = configuredDetailController(runtime, testScheduler)
        var firstObserverCalls = 0
        var secondObserverCalls = 0

        controller.observe { firstObserverCalls += 1 }
        runCurrent()
        controller.observe { secondObserverCalls += 1 }
        runCurrent()
        runtime.publishState(CatalogDetailUiState.Loading("listing-1"))
        runCurrent()

        assertEquals(1, firstObserverCalls)
        assertEquals(2, secondObserverCalls)

        controller.unobserve()
        runtime.publishState(CatalogDetailUiState.NotFound("listing-1", "missing"))
        runCurrent()

        assertEquals(1, firstObserverCalls)
        assertEquals(2, secondObserverCalls)
        controller.close()
    }

    @Test
    fun closeIsIdempotentStopsCallbacksAndRejectsFurtherActions() = runTest {
        val runtime = FakeIosCatalogDetailRuntime()
        val controller = configuredDetailController(runtime, testScheduler)
        var stateCallbacks = 0
        controller.observe { stateCallbacks += 1 }
        runCurrent()

        controller.close()
        controller.close()
        runtime.publishState(CatalogDetailUiState.Loading("listing-1"))
        controller.actions.open("listing-2")
        runCurrent()

        assertEquals(1, runtime.closeCalls)
        assertEquals(1, stateCallbacks)
        assertTrue(runtime.dispatchedIntents.isEmpty())
    }

    private fun configuredDetailController(
        runtime: FakeIosCatalogDetailRuntime,
        scheduler: TestCoroutineScheduler,
    ): IosCatalogDetailController = IosCatalogDetailController(
        runtime = runtime,
        dispatcherProvider = detailTestDispatcherProvider(scheduler),
    )
}

private class FakeIosCatalogDetailRuntime : IosCatalogDetailRuntime {
    private val mutableState = MutableStateFlow<CatalogDetailUiState>(CatalogDetailUiState.Closed)
    override val state: StateFlow<CatalogDetailUiState> = mutableState
    val dispatchedIntents = mutableListOf<CatalogDetailIntent>()
    var closeCalls = 0
        private set

    override fun dispatch(intent: CatalogDetailIntent) {
        dispatchedIntents += intent
    }

    override fun close() {
        closeCalls += 1
    }

    fun publishState(state: CatalogDetailUiState) {
        mutableState.value = state
    }
}

private fun detailTestDispatcherProvider(scheduler: TestCoroutineScheduler): DispatcherProvider {
    val dispatcher = StandardTestDispatcher(scheduler)
    return object : DispatcherProvider {
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val main: CoroutineDispatcher = dispatcher
    }
}
