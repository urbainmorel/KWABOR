package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreFeedSingleFlightTest {
    @Test
    fun concurrentRefreshesShareOneCatalogCallAndCleanUpAfterCompletion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val catalog = FakeExploreCatalogRepository().apply {
            citiesGate = gate
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
        }
        val repository = singleFlightRepository(catalog)

        val first = async { repository.refresh(ExploreFeedQuery()) }
        val second = async { repository.refresh(ExploreFeedQuery()) }
        runCurrent()
        assertEquals(1, catalog.citiesCallCount)

        gate.complete(Unit)
        assertIs<DomainResult.Success<ExploreFeedSnapshot>>(first.await())
        assertIs<DomainResult.Success<ExploreFeedSnapshot>>(second.await())
        assertEquals(1, catalog.citiesCallCount)

        assertIs<DomainResult.Success<ExploreFeedSnapshot>>(repository.refresh(ExploreFeedQuery()))
        assertEquals(2, catalog.citiesCallCount)
    }

    @Test
    fun concurrentAppendsShareOnlyThePageAndRejectTheObsoleteSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val catalog = FakeExploreCatalogRepository().apply {
            listingsGate = gate
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(3..4), "cursor-4"))
        }
        val repository = singleFlightRepository(catalog)
        val older = ExploreFeedSnapshot(
            cities = testCities(),
            categories = testCategories(),
            items = testListings(1..2),
            nextCursor = "cursor-2",
            cachedAtEpochMilliseconds = 1_000L,
            source = ExploreFeedSource.Network,
        )
        val newer = older.copy(
            items = testListings(1..2) + testListings(99..99),
            cachedAtEpochMilliseconds = 1_500L,
        )

        val first = async { repository.append(ExploreFeedQuery(), older) }
        val second = async { repository.append(ExploreFeedQuery(), newer) }
        runCurrent()
        assertEquals(1, catalog.listingsCallCount)

        gate.complete(Unit)
        val results = listOf(first.await(), second.await())
        val success = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            results.single { result -> result is DomainResult.Success },
        )
        val failure = assertIs<DomainResult.Failure>(
            results.single { result -> result is DomainResult.Failure },
        )
        assertTrue(success.value.items.any { listing -> listing.id == "listing-99" })
        assertEquals(
            DomainError.Validation("error.explore.revalidation_required"),
            failure.error,
        )
        assertEquals(1, catalog.listingsCallCount)
    }

    @Test
    fun cancellingOneRefreshWaiterDoesNotCancelTheSharedRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val catalog = FakeExploreCatalogRepository().apply {
            citiesGate = gate
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
        }
        val repository = singleFlightRepository(catalog)
        val cancelledWaiter = async { repository.refresh(ExploreFeedQuery()) }
        runCurrent()
        cancelledWaiter.cancel()

        val survivingWaiter = async { repository.refresh(ExploreFeedQuery()) }
        runCurrent()
        gate.complete(Unit)

        assertIs<DomainResult.Success<ExploreFeedSnapshot>>(survivingWaiter.await())
        assertTrue(cancelledWaiter.isCancelled)
        assertEquals(1, catalog.citiesCallCount)
    }

    @Test
    fun cancellingTheOnlyWaiterStillLetsTheRegisteredRequestFinishAndCleanUp() = runTest {
        val gate = CompletableDeferred<Unit>()
        val requestStarted = CompletableDeferred<Unit>()
        val singleFlight = ExploreFeedSingleFlight<Int>(backgroundScope)
        var requestCount = 0

        val cancelledWaiter = async {
            singleFlight.execute("request") {
                requestCount += 1
                requestStarted.complete(Unit)
                gate.await()
                DomainResult.Success(requestCount)
            }
        }
        requestStarted.await()
        cancelledWaiter.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()

        val nextResult = singleFlight.execute("request") {
            requestCount += 1
            DomainResult.Success(requestCount)
        }

        assertEquals(2, assertIs<DomainResult.Success<Int>>(nextResult).value)
        assertEquals(2, requestCount)
    }
}

private fun TestScope.singleFlightRepository(
    catalog: FakeExploreCatalogRepository,
): OfflineFirstExploreFeedRepository {
    val wall = FakeExploreWallCache()
    val references = FakeExploreReferenceCache()
    return OfflineFirstExploreFeedRepository(
        catalogRepository = catalog,
        cache = ExploreFeedCacheDependencies(
            wall = wall,
            references = references,
            persistence = FakeExploreFeedPersistenceCache(wall, references),
        ),
        clockProvider = MutableExploreClock(2_000L),
        singleFlightScope = backgroundScope,
    )
}
