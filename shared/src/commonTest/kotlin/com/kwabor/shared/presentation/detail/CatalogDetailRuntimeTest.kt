package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDetailRuntimeTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun retry_reloadsTheSameListingAfterNetworkFailure() = runTest {
        var attempt = 0
        val repository = FakeDetailCatalogQueryRepository { listingId ->
            attempt += 1
            if (attempt == 1) {
                DomainResult.Failure(DomainError.NetworkUnavailable())
            } else {
                DomainResult.Success(catalogDetailFixture(id = listingId))
            }
        }
        val runtime = runtime(repository)

        runtime.dispatch(CatalogDetailIntent.Open("detail-retry"))
        advanceUntilIdle()
        assertIs<CatalogDetailUiState.OfflineFailure>(runtime.state.value)

        runtime.dispatch(CatalogDetailIntent.Retry)
        advanceUntilIdle()

        val content = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertEquals("detail-retry", content.model.id)
        assertEquals(listOf("detail-retry", "detail-retry"), repository.requestedListingIds)
        runtime.close()
    }

    @Test
    fun closeDuringRequest_keepsTheRuntimeClosedWhenTheRequestCompletes() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val resultGate = CompletableDeferred<DomainResult<com.kwabor.shared.domain.catalog.CatalogDetail>>()
        val repository = FakeDetailCatalogQueryRepository {
            requestStarted.complete(Unit)
            resultGate.await()
        }
        val runtime = runtime(repository)

        runtime.dispatch(CatalogDetailIntent.Open("detail-slow"))
        runCurrent()
        assertTrue(requestStarted.isCompleted)
        assertIs<CatalogDetailUiState.Loading>(runtime.state.value)

        runtime.dispatch(CatalogDetailIntent.Close)
        runCurrent()
        resultGate.complete(DomainResult.Success(catalogDetailFixture(id = "detail-slow")))
        advanceUntilIdle()

        assertIs<CatalogDetailUiState.Closed>(runtime.state.value)
        runtime.close()
    }

    @Test
    fun secondOpenWinsOverTheEarlierInFlightRequest() = runTest {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<DomainResult<com.kwabor.shared.domain.catalog.CatalogDetail>>()
        val firstResponseDelivered = CompletableDeferred<Unit>()
        val repository = FakeDetailCatalogQueryRepository { listingId ->
            if (listingId == "detail-first") {
                firstRequestStarted.complete(Unit)
                withContext(NonCancellable) {
                    val result = firstResult.await()
                    firstResponseDelivered.complete(Unit)
                    result
                }
            } else {
                DomainResult.Success(catalogDetailFixture(id = listingId))
            }
        }
        val runtime = runtime(repository)

        runtime.dispatch(CatalogDetailIntent.Open("detail-first"))
        runCurrent()
        assertTrue(firstRequestStarted.isCompleted)
        runtime.dispatch(CatalogDetailIntent.Open("detail-second"))
        advanceUntilIdle()

        var content = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertEquals("detail-second", content.model.id)

        firstResult.complete(DomainResult.Success(catalogDetailFixture(id = "detail-first")))
        advanceUntilIdle()

        assertTrue(firstResponseDelivered.isCompleted)
        content = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertEquals("detail-second", content.model.id)
        assertEquals(listOf("detail-first", "detail-second"), repository.requestedListingIds)
        runtime.close()
    }

    @Test
    fun temporalTick_refreshesOpeningAndEventStatusWithoutRefetching() = runTest {
        val initialNow = Instant.parse("2026-08-03T10:00:00Z").toEpochMilliseconds()
        val transitionNow = Instant.parse("2026-08-03T18:00:00Z").toEpochMilliseconds()
        val source = catalogDetailFixture(
            variant = DetailFixtureVariant.Event,
            eventSchedule = DetailEventSchedule(
                startsAtEpochMilliseconds = Instant.parse("2026-08-03T09:00:00Z").toEpochMilliseconds(),
                endsAtEpochMilliseconds = transitionNow,
            ),
        )
        val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }
        val clock = MutableDetailClock(initialNow)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val runtime = runtime(repository, clock, ticks)

        runtime.dispatch(CatalogDetailIntent.Open(source.common.id))
        advanceUntilIdle()

        val initial = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertEquals(strings.detail.openNow, initial.model.openingStatusLabel)
        assertFalse(assertIs<CatalogDetailContentUiModel.Event>(initial.model.content).isEnded)

        clock.nowEpochMilliseconds = transitionNow
        assertTrue(ticks.tryEmit(Unit))
        runCurrent()

        val refreshed = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertEquals(strings.detail.closedNow, refreshed.model.openingStatusLabel)
        assertTrue(assertIs<CatalogDetailContentUiModel.Event>(refreshed.model.content).isEnded)
        assertTrue(refreshed.model.contextLabel.endsWith(strings.detail.eventEnded))
        assertEquals(listOf(source.common.id), repository.requestedListingIds)
        runtime.close()
    }

    @Test
    fun selectMedia_isBoundedToTheOfficialImageCollection() = runTest {
        val repository = FakeDetailCatalogQueryRepository { listingId ->
            DomainResult.Success(catalogDetailFixture(id = listingId))
        }
        val runtime = runtime(repository)
        runtime.dispatch(CatalogDetailIntent.Open("detail-media"))
        advanceUntilIdle()

        var content = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertEquals(2, content.model.media.size)
        assertEquals(0, content.selectedMediaIndex)

        runtime.dispatch(CatalogDetailIntent.SelectMedia(-20))
        advanceUntilIdle()
        content = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertEquals(0, content.selectedMediaIndex)

        runtime.dispatch(CatalogDetailIntent.SelectMedia(20))
        advanceUntilIdle()
        content = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertEquals(1, content.selectedMediaIndex)
        runtime.close()
    }

    @Test
    fun toggleDescription_isReversibleAndPreservesTheLoadedModel() = runTest {
        val repository = FakeDetailCatalogQueryRepository { listingId ->
            DomainResult.Success(catalogDetailFixture(id = listingId))
        }
        val runtime = runtime(repository)
        runtime.dispatch(CatalogDetailIntent.Open("detail-description"))
        advanceUntilIdle()

        val initial = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertFalse(initial.isDescriptionExpanded)

        runtime.dispatch(CatalogDetailIntent.ToggleDescription)
        advanceUntilIdle()
        val expanded = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertTrue(expanded.isDescriptionExpanded)
        assertEquals(initial.model, expanded.model)

        runtime.dispatch(CatalogDetailIntent.ToggleDescription)
        advanceUntilIdle()
        val collapsed = assertIs<CatalogDetailUiState.Content>(runtime.state.value)
        assertFalse(collapsed.isDescriptionExpanded)
        assertEquals(initial.model, collapsed.model)
        runtime.close()
    }

    private fun TestScope.runtime(
        repository: FakeDetailCatalogQueryRepository,
        clock: ClockProvider = FixedDetailClock(DETAIL_TEST_NOW),
        temporalTicks: Flow<Unit> = emptyFlow(),
    ): CatalogDetailRuntime = CatalogDetailRuntime(
        presenter = CatalogDetailPresenter(repository, clock),
        strings = strings,
        coroutineScope = this,
        temporalTicks = temporalTicks,
    )
}

private class MutableDetailClock(
    var nowEpochMilliseconds: Long,
) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = nowEpochMilliseconds
}
