package com.kwabor.shared.data.explore

import com.kwabor.shared.data.local.ExploreCacheSnapshot
import com.kwabor.shared.data.local.ExploreReferenceSnapshot
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreFeedCacheOperation
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import com.kwabor.shared.domain.explore.ExploreFeedWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineFirstExploreFeedRepositoryTest {
    @Test
    fun readCachedRequiresBothWallAndReferences() = runTest {
        val query = ExploreFeedQuery()
        val wall = FakeExploreWallCache(cachedWall(query, 1..2, "cursor-2", cachedAt = 900L))
        val references = FakeExploreReferenceCache(snapshot = null)

        val result = repository(wall = wall, references = references).readCached(query)

        assertNull(assertIs<DomainResult.Success<ExploreFeedSnapshot?>>(result).value)
    }

    @Test
    fun readCachedReturnsCompleteCacheSnapshotAtOldestComponentTimestamp() = runTest {
        val query = ExploreFeedQuery()
        val wall = FakeExploreWallCache(cachedWall(query, 1..2, "cursor-2", cachedAt = 900L))
        val references = FakeExploreReferenceCache(cachedReferences(cachedAt = 1_000L))

        val result = repository(wall = wall, references = references).readCached(query)

        val snapshot = assertIs<DomainResult.Success<ExploreFeedSnapshot?>>(result).value
        requireNotNull(snapshot)
        assertEquals(ExploreFeedSource.Cache, snapshot.source)
        assertEquals(900L, snapshot.cachedAtEpochMilliseconds)
        assertEquals(testCities(), snapshot.cities)
        assertEquals(testCategories(), snapshot.categories)
        assertEquals(testListings(1..2), snapshot.items)
        assertNull(snapshot.warning)
    }

    @Test
    fun readCachedMapsPhysicalStorageFailureToDomainError() = runTest {
        val wall = FakeExploreWallCache().apply { failReads = true }

        val result = repository(wall = wall).readCached(ExploreFeedQuery())

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals(
            DomainError.LocalStorageUnavailable("error.explore.storage_unavailable"),
            failure.error,
        )
    }

    @Test
    fun readCachedEvictsAWallWhoseListingsNoLongerMatchAuthoritativeReferences() = runTest {
        val query = ExploreFeedQuery()
        val staleSnapshot = cachedWall(query, 1..2, nextCursor = null, cachedAt = 900L).copy(
            items = testListings(1..2).map { listing -> listing.copy(cityId = "city-retired") },
        )
        val wall = FakeExploreWallCache(staleSnapshot)

        val result = repository(wall = wall).readCached(query)

        assertNull(assertIs<DomainResult.Success<ExploreFeedSnapshot?>>(result).value)
        assertEquals(listOf(query.toCacheKey()), wall.clearedKeys)
        assertNull(wall.snapshot)
    }

    @Test
    fun refreshLoadsAndPersistsOneCoherentNetworkSnapshot() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..20), "cursor-20"))
        }
        val wall = FakeExploreWallCache()
        val references = FakeExploreReferenceCache()

        val result = repository(
            catalog,
            wall,
            references,
            clock = MutableExploreClock(2_000L),
        ).refresh(ExploreFeedQuery())

        val snapshot = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(result).value
        assertEquals(ExploreFeedSource.Network, snapshot.source)
        assertEquals(2_000L, snapshot.cachedAtEpochMilliseconds)
        assertEquals("cursor-20", snapshot.nextCursor)
        assertNull(snapshot.warning)
        assertEquals(20, wall.writes.single().items.size)
        assertEquals("cursor-20", wall.writes.single().nextCursor)
        assertEquals(testCities(), references.writes.single().cities)
        assertEquals(20, catalog.listingRequests.single().second.limit)
        assertNull(catalog.listingRequests.single().second.cursor)
    }

    @Test
    fun refreshKeepsTheTimestampAllocatedBeforeTheNetworkResponseCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val clock = MutableExploreClock(1_000L)
        val catalog = FakeExploreCatalogRepository().apply {
            citiesGate = gate
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
        }
        val refresh = async {
            repository(
                catalog = catalog,
                references = FakeExploreReferenceCache(),
                clock = clock,
            ).refresh(ExploreFeedQuery())
        }
        runCurrent()

        clock.now = 9_000L
        gate.complete(Unit)

        val snapshot = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(refresh.await()).value
        assertEquals(1_000L, snapshot.cachedAtEpochMilliseconds)
    }

    @Test
    fun refreshRejectsEachObsoleteReferenceBeforeLoadingListings() = runTest {
        val unknownCityCatalog = FakeExploreCatalogRepository()
        val cityResult = repository(catalog = unknownCityCatalog).refresh(
            ExploreFeedQuery(filters = ListingFilters(cityId = "city-retired")),
        )
        val unknownCategoryCatalog = FakeExploreCatalogRepository()
        val categoryResult = repository(catalog = unknownCategoryCatalog).refresh(
            ExploreFeedQuery(filters = ListingFilters(categoryId = "category-retired")),
        )

        assertEquals(
            DomainError.Validation("error.explore.city_unavailable"),
            assertIs<DomainResult.Failure>(cityResult).error,
        )
        assertEquals(
            DomainError.Validation("error.explore.category_unavailable"),
            assertIs<DomainResult.Failure>(categoryResult).error,
        )
        assertTrue(unknownCityCatalog.listingRequests.isEmpty())
        assertTrue(unknownCategoryCatalog.listingRequests.isEmpty())
    }

    @Test
    fun refreshRejectsMalformedAuthoritativeReferencesAsContractFailure() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            citiesResult = DomainResult.Success(listOf(City(id = "lome", name = "Lome", countryCode = "TG")))
        }

        val result = repository(catalog = catalog).refresh(ExploreFeedQuery())

        assertEquals(
            DomainError.Unexpected("error.explore.invalid_payload"),
            assertIs<DomainResult.Failure>(result).error,
        )
        assertTrue(catalog.listingRequests.isEmpty())
    }

    @Test
    fun refreshRejectsListingsThatDoNotMatchAuthoritativeReferences() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            val invalidListings = testListings(1..2).map { listing -> listing.copy(cityId = "city-retired") }
            listingResults += DomainResult.Success(ListingSummaryPage(invalidListings, nextCursor = null))
        }
        val wall = FakeExploreWallCache()
        val references = FakeExploreReferenceCache()

        val result = repository(catalog, wall, references).refresh(ExploreFeedQuery())

        assertEquals(
            DomainError.Unexpected("error.explore.invalid_page"),
            assertIs<DomainResult.Failure>(result).error,
        )
        assertTrue(wall.writes.isEmpty())
        assertTrue(references.writes.isEmpty())
    }

    @Test
    fun refreshDoesNotPersistWhenNetworkFails() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Failure(DomainError.NetworkUnavailable())
        }
        val wall = FakeExploreWallCache()
        val references = FakeExploreReferenceCache()

        val result = repository(catalog, wall, references).refresh(ExploreFeedQuery())

        assertEquals(DomainError.NetworkUnavailable(), assertIs<DomainResult.Failure>(result).error)
        assertTrue(wall.writes.isEmpty())
        assertTrue(references.writes.isEmpty())
    }

    @Test
    fun refreshReportsCacheFailuresWithoutDiscardingNetworkContent() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
        }
        val wall = FakeExploreWallCache().apply { failWrites = true }
        val references = FakeExploreReferenceCache().apply { failWrites = true }

        val result = repository(catalog, wall, references).refresh(ExploreFeedQuery())

        val snapshot = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(result).value
        assertEquals(testListings(1..2), snapshot.items)
        val warning = assertIs<ExploreFeedWarning.LocalPersistenceUnavailable>(snapshot.warning)
        assertEquals(
            setOf(ExploreFeedCacheOperation.WriteWall, ExploreFeedCacheOperation.WriteReferences),
            warning.failedOperations,
        )
    }

    @Test
    fun refreshKeepsNetworkContentAndWarnsWhenTheWatermarkReadIsUnavailable() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
        }
        val unavailableProvider = ExplorePersistenceWatermarkProvider {
            ExplorePersistenceWatermarkRead.Unavailable
        }

        val result = repository(
            catalog = catalog,
            persistentWatermarkProvider = unavailableProvider,
        ).refresh(ExploreFeedQuery())

        val snapshot = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(result).value
        val warning = assertIs<ExploreFeedWarning.LocalPersistenceUnavailable>(snapshot.warning)
        assertEquals(setOf(ExploreFeedCacheOperation.ReadWatermark), warning.failedOperations)
        assertEquals(testListings(1..2), snapshot.items)
    }

    @Test
    fun refreshDoesNotExposeAResponseRejectedByNewerPersistentState() = runTest {
        val query = ExploreFeedQuery()
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
        }
        val wall = FakeExploreWallCache(cachedWall(query, 1..2, nextCursor = null, cachedAt = 9_000L))
        val references = FakeExploreReferenceCache(cachedReferences(cachedAt = 9_000L))

        val result = repository(
            catalog = catalog,
            wall = wall,
            references = references,
            clock = MutableExploreClock(1_000L),
        ).refresh(query)

        assertEquals(
            DomainError.Validation("error.explore.revalidation_required"),
            assertIs<DomainResult.Failure>(result).error,
        )
        assertTrue(wall.writes.isEmpty())
        assertTrue(references.writes.isEmpty())
    }

    @Test
    fun refreshMapsCacheContractRejectionWithoutLeakingAnException() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
        }
        val wall = FakeExploreWallCache().apply { rejectWrites = true }

        val result = repository(catalog, wall).refresh(ExploreFeedQuery())

        assertEquals(
            DomainError.Unexpected("error.explore.invalid_payload"),
            assertIs<DomainResult.Failure>(result).error,
        )
    }

    @Test
    fun refreshPropagatesCancellationFromPersistence() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
        }
        val wall = FakeExploreWallCache().apply { cancelWrites = true }

        assertFailsWith<CancellationException> {
            repository(catalog, wall).refresh(ExploreFeedQuery())
        }
    }

    @Test
    fun cancelledRefreshIsRemovedFromSingleFlightBeforeTheNextAttempt() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..2), nextCursor = null))
        }
        val wall = FakeExploreWallCache().apply { cancelWrites = true }
        val repository = repository(catalog, wall)

        assertFailsWith<CancellationException> {
            repository.refresh(ExploreFeedQuery())
        }
        wall.cancelWrites = false

        assertIs<DomainResult.Success<ExploreFeedSnapshot>>(repository.refresh(ExploreFeedQuery()))
        assertEquals(2, catalog.citiesCallCount)
    }

    @Test
    fun appendDeduplicatesOverlapAndKeepsServerOrder() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(20..39), "cursor-39"))
        }
        val wall = FakeExploreWallCache()
        val references = FakeExploreReferenceCache(cachedReferences(1_000L))
        val current = networkSnapshot(1..20, nextCursor = "cursor-20", cachedAt = 1_000L)

        val result = repository(
            catalog,
            wall,
            references,
            clock = MutableExploreClock(2_000L),
        ).append(ExploreFeedQuery(), current)

        val snapshot = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(result).value
        assertEquals(testListings(1..39), snapshot.items)
        assertEquals("cursor-39", snapshot.nextCursor)
        assertEquals(2_000L, snapshot.cachedAtEpochMilliseconds)
        val persistedWall = wall.writes.single()
        assertEquals(39, persistedWall.items.size)
        assertEquals(
            testListings(1..20).associate { listing -> listing.id to 1_000L },
            persistedWall.itemCachedAtEpochMilliseconds,
        )
        assertTrue(references.writes.isEmpty())
        assertEquals("cursor-20", catalog.listingRequests.single().second.cursor)
    }

    @Test
    fun successiveAppendsPreserveEachPageContentCaptureTimestamp() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(21..40), "cursor-40"))
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(41..60), "cursor-60"))
        }
        val clock = MutableExploreClock(2_000L)
        val repository = repository(catalog = catalog, clock = clock)
        val firstPage = networkSnapshot(1..20, nextCursor = "cursor-20", cachedAt = 1_000L)

        val secondPage = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository.append(ExploreFeedQuery(), firstPage),
        ).value
        clock.now = 3_000L
        val thirdPage = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository.append(ExploreFeedQuery(), secondPage),
        ).value

        assertEquals(3_000L, thirdPage.cachedAtEpochMilliseconds)
        assertEquals(1_000L, thirdPage.referencesCapturedAtEpochMilliseconds)
        assertEquals(
            testListings(1..20).associate { listing -> listing.id to 1_000L } +
                testListings(21..40).associate { listing -> listing.id to 2_000L },
            thirdPage.itemContentCapturedAtEpochMilliseconds,
        )
        assertTrue(
            testListings(41..60).none { listing ->
                listing.id in thirdPage.itemContentCapturedAtEpochMilliseconds
            },
        )
    }

    @Test
    fun appendRepairsAFailedRefreshAtomicallyBeforeClearingItsWarning() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(1..20), "cursor-20"))
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(21..40), "cursor-40"))
        }
        val wall = FakeExploreWallCache().apply { failWrites = true }
        val references = FakeExploreReferenceCache()
        val clock = MutableExploreClock(1_000L)
        val repository = repository(catalog, wall, references, clock = clock)
        val refreshed = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository.refresh(ExploreFeedQuery()),
        ).value
        assertEquals(
            setOf(ExploreFeedCacheOperation.WriteWall, ExploreFeedCacheOperation.WriteReferences),
            assertIs<ExploreFeedWarning.LocalPersistenceUnavailable>(refreshed.warning).failedOperations,
        )

        wall.failWrites = false
        clock.now = 2_000L
        val appended = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository.append(ExploreFeedQuery(), refreshed),
        ).value

        assertNull(appended.warning)
        assertEquals(testListings(1..40), appended.items)
        assertEquals("cursor-40", wall.writes.single().nextCursor)
        val repairedWall = wall.writes.single()
        assertEquals(2_000L, repairedWall.cachedAtEpochMilliseconds)
        assertEquals(
            testListings(1..20).associate { listing -> listing.id to 1_000L },
            repairedWall.itemCachedAtEpochMilliseconds,
        )
        assertEquals(1_000L, references.writes.single().cachedAtEpochMilliseconds)
        val cached = assertIs<DomainResult.Success<ExploreFeedSnapshot?>>(
            repository.readCached(ExploreFeedQuery()),
        ).value
        assertEquals(testListings(1..40), requireNotNull(cached).items)
    }

    @Test
    fun appendRepairKeepsGlobalReferencesThatAreAlreadyFresher() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(21..40), "cursor-40"))
        }
        val wall = FakeExploreWallCache()
        val references = FakeExploreReferenceCache(cachedReferences(cachedAt = 1_500L))
        val current = networkSnapshot(1..20, nextCursor = "cursor-20", cachedAt = 1_000L).copy(
            warning = persistenceWarning(
                ExploreFeedCacheOperation.WriteWall,
                ExploreFeedCacheOperation.WriteReferences,
            ),
        )

        val appended = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository(
                catalog = catalog,
                wall = wall,
                references = references,
                clock = MutableExploreClock(2_000L),
            ).append(ExploreFeedQuery(), current),
        ).value

        assertNull(appended.warning)
        assertEquals(2_000L, wall.writes.single().cachedAtEpochMilliseconds)
        assertTrue(references.writes.isEmpty())
        assertEquals(1_500L, references.snapshot?.cachedAtEpochMilliseconds)
    }

    @Test
    fun cachedWallKeepsItsItemFreshnessWhenReferencesAreNewerBeforeAppend() = runTest {
        val query = ExploreFeedQuery()
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(21..40), "cursor-40"))
        }
        val wall = FakeExploreWallCache(
            cachedWall(query, 1..20, nextCursor = "cursor-20", cachedAt = 3_000L),
        )
        val references = FakeExploreReferenceCache(cachedReferences(cachedAt = 5_000L))
        val repository = repository(
            catalog = catalog,
            wall = wall,
            references = references,
            clock = MutableExploreClock(6_000L),
        )
        val cached = assertIs<DomainResult.Success<ExploreFeedSnapshot?>>(repository.readCached(query)).value
        val appendable = requireNotNull(cached).copy(source = ExploreFeedSource.Network)

        val appended = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository.append(query, appendable),
        ).value

        assertEquals(5_000L, appended.referencesCapturedAtEpochMilliseconds)
        assertEquals(
            testListings(1..20).associate { listing -> listing.id to 3_000L },
            appended.itemContentCapturedAtEpochMilliseconds,
        )
        assertEquals(
            testListings(1..20).associate { listing -> listing.id to 3_000L },
            wall.writes.single().itemCachedAtEpochMilliseconds,
        )
    }

    @Test
    fun appendKeepsAtomicFailureOperationsWhenTheAtomicCacheIsUnavailable() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(21..40), "cursor-40"))
        }
        val wall = FakeExploreWallCache()
        val references = FakeExploreReferenceCache()
        val unavailableWatermark = ExplorePersistenceWatermarkProvider {
            ExplorePersistenceWatermarkRead.Unavailable
        }
        val repository = OfflineFirstExploreFeedRepository(
            catalogRepository = catalog,
            cache = ExploreFeedCacheDependencies(
                wall = wall,
                references = references,
                persistence = null,
                watermarkProvider = unavailableWatermark,
            ),
            clockProvider = MutableExploreClock(2_000L),
            singleFlightScope = backgroundScope,
        )
        val current = networkSnapshot(1..20, nextCursor = "cursor-20", cachedAt = 1_000L).copy(
            warning = persistenceWarning(
                ExploreFeedCacheOperation.WriteWall,
                ExploreFeedCacheOperation.WriteReferences,
            ),
        )

        val appended = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository.append(ExploreFeedQuery(), current),
        ).value

        assertEquals(
            setOf(
                ExploreFeedCacheOperation.ReadWatermark,
                ExploreFeedCacheOperation.WriteWall,
                ExploreFeedCacheOperation.WriteReferences,
            ),
            assertIs<ExploreFeedWarning.LocalPersistenceUnavailable>(appended.warning).failedOperations,
        )
        assertTrue(wall.writes.isEmpty())
        assertTrue(references.writes.isEmpty())
    }

    @Test
    fun appendRepairsOnlyTheSafeFortyItemPrefixAndKeepsItsExactCursor() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(41..60), "cursor-60"))
        }
        val wall = FakeExploreWallCache()
        val references = FakeExploreReferenceCache()
        val current = networkSnapshot(1..40, nextCursor = "cursor-40", cachedAt = 1_000L).copy(
            warning = persistenceWarning(
                ExploreFeedCacheOperation.ReadWatermark,
                ExploreFeedCacheOperation.WriteWall,
                ExploreFeedCacheOperation.WriteReferences,
            ),
        )

        val appended = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository(
                catalog,
                wall,
                references,
                clock = MutableExploreClock(2_000L),
            ).append(ExploreFeedQuery(), current),
        ).value

        assertEquals(60, appended.items.size)
        assertEquals("cursor-60", appended.nextCursor)
        assertEquals(
            setOf(ExploreFeedCacheOperation.ReadWatermark),
            assertIs<ExploreFeedWarning.LocalPersistenceUnavailable>(appended.warning).failedOperations,
        )
        assertEquals(testListings(1..40), wall.writes.single().items)
        assertEquals("cursor-40", wall.writes.single().nextCursor)
        assertEquals(1_000L, references.writes.single().cachedAtEpochMilliseconds)
    }

    @Test
    fun appendBeyondTheSafePrefixKeepsUnrepairedWarningsWithoutWriting() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(61..80), "cursor-80"))
        }
        val wall = FakeExploreWallCache()
        val references = FakeExploreReferenceCache()
        val failedOperations = setOf(
            ExploreFeedCacheOperation.ReadWatermark,
            ExploreFeedCacheOperation.WriteWall,
            ExploreFeedCacheOperation.WriteReferences,
        )
        val current = networkSnapshot(1..60, nextCursor = "cursor-60", cachedAt = 1_000L).copy(
            warning = ExploreFeedWarning.LocalPersistenceUnavailable(failedOperations),
        )

        val appended = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository(
                catalog,
                wall,
                references,
                clock = MutableExploreClock(2_000L),
            ).append(ExploreFeedQuery(), current),
        ).value

        assertEquals(80, appended.items.size)
        assertEquals(
            failedOperations,
            assertIs<ExploreFeedWarning.LocalPersistenceUnavailable>(appended.warning).failedOperations,
        )
        assertTrue(wall.writes.isEmpty())
        assertTrue(references.writes.isEmpty())
    }

    @Test
    fun appendRejectsCachedSnapshotBeforeCallingCatalogOrPersistence() = runTest {
        val catalog = FakeExploreCatalogRepository()
        val wall = FakeExploreWallCache()
        val references = FakeExploreReferenceCache()
        val cached = networkSnapshot(1..20, nextCursor = "cursor-20", cachedAt = 1_000L).copy(
            source = ExploreFeedSource.Cache,
        )

        val result = repository(catalog = catalog, wall = wall, references = references)
            .append(ExploreFeedQuery(), cached)

        assertEquals(
            DomainError.Validation("error.explore.revalidation_required"),
            assertIs<DomainResult.Failure>(result).error,
        )
        assertTrue(catalog.listingRequests.isEmpty())
        assertTrue(wall.writes.isEmpty())
        assertTrue(references.writes.isEmpty())
    }

    @Test
    fun appendRejectsAbsentOrRepeatedCursorWithoutCorruptingCache() = runTest {
        val noCursorWall = FakeExploreWallCache()
        val noCursorResult = repository(wall = noCursorWall).append(
            ExploreFeedQuery(),
            networkSnapshot(1..2, nextCursor = null, cachedAt = 1_000L),
        )
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(3..4), "cursor-2"))
        }
        val repeatedCursorWall = FakeExploreWallCache()
        val repeatedCursorResult = repository(catalog, repeatedCursorWall).append(
            ExploreFeedQuery(),
            networkSnapshot(1..2, nextCursor = "cursor-2", cachedAt = 1_000L),
        )

        assertEquals(
            DomainError.Validation("error.explore.no_next_page"),
            assertIs<DomainResult.Failure>(noCursorResult).error,
        )
        assertEquals(
            DomainError.Unexpected("error.explore.invalid_page"),
            assertIs<DomainResult.Failure>(repeatedCursorResult).error,
        )
        assertTrue(noCursorWall.writes.isEmpty())
        assertTrue(repeatedCursorWall.writes.isEmpty())
    }

    @Test
    fun appendRejectsANonTerminalPageWithoutAnyNewListing() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(
                ListingSummaryPage(testListings(1..2), nextCursor = "cursor-3"),
            )
        }
        val wall = FakeExploreWallCache()
        val current = networkSnapshot(1..2, nextCursor = "cursor-2", cachedAt = 1_000L)

        val result = repository(catalog, wall).append(ExploreFeedQuery(), current)

        assertEquals(
            DomainError.Unexpected("error.explore.invalid_page"),
            assertIs<DomainResult.Failure>(result).error,
        )
        assertTrue(wall.writes.isEmpty())
    }

    @Test
    fun appendAcceptsADeduplicatedTerminalPageToClosePagination() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(
                ListingSummaryPage(testListings(1..2), nextCursor = null),
            )
        }
        val current = networkSnapshot(1..2, nextCursor = "cursor-2", cachedAt = 1_000L)

        val result = repository(catalog = catalog).append(ExploreFeedQuery(), current)

        val snapshot = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(result).value
        assertEquals(testListings(1..2), snapshot.items)
        assertEquals(null, snapshot.nextCursor)
    }

    @Test
    fun appendNeverAdvancesPersistedCursorPastTheSafeFortyItemPrefix() = runTest {
        val catalog = FakeExploreCatalogRepository().apply {
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(21..40), "cursor-40"))
            listingResults += DomainResult.Success(ListingSummaryPage(testListings(41..60), "cursor-60"))
        }
        val wall = FakeExploreWallCache()
        val repository = repository(catalog, wall)
        val firstPage = networkSnapshot(1..20, nextCursor = "cursor-20", cachedAt = 1_000L)

        val secondPage = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository.append(ExploreFeedQuery(), firstPage),
        ).value
        val thirdPage = assertIs<DomainResult.Success<ExploreFeedSnapshot>>(
            repository.append(ExploreFeedQuery(), secondPage),
        ).value

        assertEquals(60, thirdPage.items.size)
        assertEquals("cursor-60", thirdPage.nextCursor)
        assertEquals(40, wall.writes.last().items.size)
        assertEquals("cursor-40", wall.writes.last().nextCursor)
        assertTrue(wall.writes.last().items == testListings(1..40))
    }
}

private fun TestScope.repository(
    catalog: FakeExploreCatalogRepository = FakeExploreCatalogRepository(),
    wall: FakeExploreWallCache = FakeExploreWallCache(),
    references: FakeExploreReferenceCache = FakeExploreReferenceCache(cachedReferences(1_000L)),
    clock: MutableExploreClock = MutableExploreClock(1_500L),
    persistentWatermarkProvider: ExplorePersistenceWatermarkProvider =
        EMPTY_EXPLORE_PERSISTENCE_WATERMARK_PROVIDER,
): OfflineFirstExploreFeedRepository = OfflineFirstExploreFeedRepository(
    catalogRepository = catalog,
    cache = ExploreFeedCacheDependencies(
        wall = wall,
        references = references,
        persistence = FakeExploreFeedPersistenceCache(wall, references),
        watermarkProvider = persistentWatermarkProvider,
    ),
    clockProvider = clock,
    singleFlightScope = backgroundScope,
)

private fun cachedWall(
    query: ExploreFeedQuery,
    range: IntRange,
    nextCursor: String?,
    cachedAt: Long,
): ExploreCacheSnapshot = ExploreCacheSnapshot(
    snapshotKey = query.toCacheKey(),
    items = testListings(range),
    nextCursor = nextCursor,
    cachedAtEpochMilliseconds = cachedAt,
)

private fun cachedReferences(cachedAt: Long): ExploreReferenceSnapshot = ExploreReferenceSnapshot(
    cities = testCities(),
    categories = testCategories(),
    cachedAtEpochMilliseconds = cachedAt,
)

private fun networkSnapshot(range: IntRange, nextCursor: String?, cachedAt: Long): ExploreFeedSnapshot =
    ExploreFeedSnapshot(
        cities = testCities(),
        categories = testCategories(),
        items = testListings(range),
        nextCursor = nextCursor,
        cachedAtEpochMilliseconds = cachedAt,
        source = ExploreFeedSource.Network,
    )

private fun persistenceWarning(vararg operations: ExploreFeedCacheOperation): ExploreFeedWarning =
    ExploreFeedWarning.LocalPersistenceUnavailable(operations.toSet())
