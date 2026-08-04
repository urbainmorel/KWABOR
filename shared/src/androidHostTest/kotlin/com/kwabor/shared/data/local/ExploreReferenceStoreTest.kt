package com.kwabor.shared.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExploreReferenceStoreTest {
    @Test
    fun storeRoundTripsOrderedReferencesAndEmptySnapshots() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val store = ExploreReferenceStore(database.exploreReferenceDao())
            val populated = referenceSnapshot()

            store.replace(populated)

            assertEquals(populated, store.read())
            assertEquals(
                populated.cachedAtEpochMilliseconds,
                ExplorePersistenceWatermarkStore(database.explorePersistenceWatermarkDao()).read(),
            )

            val empty = ExploreReferenceSnapshot(
                cities = emptyList(),
                categories = emptyList(),
                cachedAtEpochMilliseconds = 2_000,
            )
            store.replace(empty)

            assertEquals(empty, store.read())
            assertEquals(
                empty.cachedAtEpochMilliseconds,
                ExplorePersistenceWatermarkStore(database.explorePersistenceWatermarkDao()).read(),
            )
        }
    }

    @Test
    fun replacementIsAtomicWhenAConstraintRejectsNewRows() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val dao = database.exploreReferenceDao()
            val store = ExploreReferenceStore(dao)
            val original = referenceSnapshot()
            store.replace(original)
            val firstCity = cityEntity(id = "cotonou", position = 0)
            val duplicatePosition = cityEntity(id = "ouidah", position = 0)

            assertFails {
                dao.replaceReference(
                    snapshot = referenceSnapshotEntity(cityCount = 2),
                    cities = listOf(firstCity, duplicatePosition),
                    categories = emptyList(),
                )
            }

            assertEquals(original, store.read())
        }
    }

    @Test
    fun olderOrEqualReferencesCannotReplaceTheLatestSnapshot() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val store = ExploreReferenceStore(database.exploreReferenceDao())
            val newer = referenceSnapshot().copy(
                cities = referenceSnapshot().cities.map { city ->
                    if (city.id == "cotonou") city.copy(name = "Cotonou récent") else city
                },
                cachedAtEpochMilliseconds = 2_000,
            )
            store.replace(newer)

            store.replace(
                newer.copy(
                    cities = newer.cities.map { city -> city.copy(name = "Valeur égale") },
                ),
            )
            store.replace(
                newer.copy(
                    cities = newer.cities.map { city -> city.copy(name = "Valeur ancienne") },
                    cachedAtEpochMilliseconds = 1_000,
                ),
            )

            assertEquals(newer, store.read())
        }
    }

    @Test
    fun corruptReferenceEvictionCannotDeleteANewerReplacementAfterAStaleRead() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val dao = database.exploreReferenceDao()
            dao.replaceReference(
                snapshot = referenceSnapshotEntity(cityCount = 2),
                cities = listOf(cityEntity()),
                categories = emptyList(),
            )
            val readStarted = CompletableDeferred<Unit>()
            val continueRead = CompletableDeferred<Unit>()
            val staleStore = ExploreReferenceStore(BarrierExploreReferenceDao(dao, readStarted, continueRead))
            val staleRead = async { staleStore.read() }
            readStarted.await()
            val fresh = referenceSnapshot().copy(cachedAtEpochMilliseconds = 3_000)
            val currentStore = ExploreReferenceStore(dao)
            currentStore.replace(fresh)

            continueRead.complete(Unit)

            assertNull(staleRead.await())
            assertEquals(fresh, currentStore.read())
        }
    }

    @Test
    fun feedTransactionRollsBackTheWallWhenReferenceInsertionFails() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val wallStore = ExploreCacheStore(database.exploreCacheDao())
            val referenceStore = ExploreReferenceStore(database.exploreReferenceDao())
            val originalWall = ExploreCacheSnapshot(
                snapshotKey = "explore:atomic",
                items = listOf(listingSummary(id = "listing-original", name = "Original")),
                nextCursor = null,
                cachedAtEpochMilliseconds = 1_000,
            )
            val originalReferences = referenceSnapshot()
            wallStore.replace(originalWall)
            referenceStore.replace(originalReferences)
            val replacementWall = originalWall.copy(
                items = listOf(listingSummary(id = "listing-replacement", name = "Replacement")),
                cachedAtEpochMilliseconds = 2_000,
            ).toCacheWrite()

            assertFails {
                database.exploreFeedPersistenceDao().replaceFeed(
                    wall = replacementWall,
                    references = ExploreReferenceWrite(
                        snapshot = referenceSnapshotEntity(cityCount = 2),
                        cities = listOf(
                            cityEntity(id = "cotonou", position = 0),
                            cityEntity(id = "ouidah", position = 0),
                        ),
                        categories = emptyList(),
                    ),
                    maxSnapshotCount = DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS,
                )
            }

            assertEquals(originalWall, wallStore.read(originalWall.snapshotKey))
            assertEquals(originalReferences, referenceStore.read())
        }
    }

    @Test
    fun newerWallCanReuseGlobalReferencesThatAreAlreadyFresher() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val referenceStore = ExploreReferenceStore(database.exploreReferenceDao())
            val latestReferences = referenceSnapshot().copy(cachedAtEpochMilliseconds = 3_000)
            referenceStore.replace(latestReferences)
            val incomingWall = ExploreCacheSnapshot(
                snapshotKey = "explore:older-feed",
                items = listOf(listingSummary(id = "listing-older-feed")),
                nextCursor = null,
                cachedAtEpochMilliseconds = 2_000,
            )

            val result = ExploreFeedPersistenceStore(database.exploreFeedPersistenceDao()).replace(
                wall = incomingWall,
                references = referenceSnapshot().copy(cachedAtEpochMilliseconds = 2_000),
            )

            assertEquals(ExplorePersistenceWriteResult.Applied, result)
            assertEquals(incomingWall, ExploreCacheStore(database.exploreCacheDao()).read(incomingWall.snapshotKey))
            assertEquals(latestReferences, referenceStore.read())
        }
    }

    @Test
    fun replaceRejectsInvalidAndDuplicateDomainReferencesBeforeWriting() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val store = ExploreReferenceStore(database.exploreReferenceDao())
            val invalid = referenceSnapshot().copy(
                cities = listOf(
                    City(id = "cotonou", name = "Cotonou", latitude = 6.3703),
                ),
            )
            val outsideBenin = referenceSnapshot().copy(
                cities = listOf(
                    City(
                        id = "paris",
                        name = "Paris",
                        latitude = 48.8566,
                        longitude = 2.3522,
                    ),
                ),
            )
            val duplicate = referenceSnapshot().copy(
                categories = listOf(category(), category()),
            )

            assertFailsWith<IllegalArgumentException> { store.replace(invalid) }
            assertFailsWith<IllegalArgumentException> { store.replace(outsideBenin) }
            assertFailsWith<IllegalArgumentException> { store.replace(duplicate) }
            assertNull(store.read())
        }
    }

    @Test
    fun persistedCityCoordinatesOutsideBeninAreEvicted() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val dao = database.exploreReferenceDao()
            dao.replaceReference(
                snapshot = referenceSnapshotEntity(cityCount = 1),
                cities = listOf(
                    cityEntity(
                        id = "paris",
                        latitude = 48.8566,
                        longitude = 2.3522,
                    ),
                ),
                categories = emptyList(),
            )

            assertNull(ExploreReferenceStore(dao).read())
            assertNull(dao.findReferenceSnapshot(REFERENCE_KEY))
        }
    }

    @Test
    fun cityCoordinatesOnBeninBoundaryAreAccepted() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val store = ExploreReferenceStore(database.exploreReferenceDao())
            val boundarySnapshot = referenceSnapshot().copy(
                cities = listOf(
                    City(
                        id = "boundary",
                        name = "Frontière",
                        latitude = 6.466417,
                        longitude = 2.704951,
                    ),
                ),
            )

            store.replace(boundarySnapshot)

            assertEquals(boundarySnapshot, store.read())
        }
    }

    @Test
    fun corruptReferencesAreEvictedWithoutTouchingListingSnapshots() = runTest {
        withReferenceDatabase(coroutineContext) { database ->
            val listingStore = ExploreCacheStore(database.exploreCacheDao())
            val listingSnapshot = ExploreCacheSnapshot(
                snapshotKey = "explore:healthy",
                items = listOf(listingSummary(id = "listing-healthy")),
                nextCursor = null,
                cachedAtEpochMilliseconds = 1_000,
            )
            listingStore.replace(listingSnapshot)
            val referenceDao = database.exploreReferenceDao()
            referenceDao.replaceReference(
                snapshot = referenceSnapshotEntity(cityCount = 2),
                cities = listOf(cityEntity()),
                categories = emptyList(),
            )

            assertNull(ExploreReferenceStore(referenceDao).read())
            assertNull(referenceDao.findReferenceSnapshot(REFERENCE_KEY))
            assertEquals(listingSnapshot, listingStore.read(listingSnapshot.snapshotKey))
        }
    }
}

private suspend fun withReferenceDatabase(
    queryCoroutineContext: CoroutineContext,
    block: suspend (KwaborDatabase) -> Unit,
) {
    val database = buildKwaborDatabase(
        builder = Room.inMemoryDatabaseBuilder(
            context = ApplicationProvider.getApplicationContext<Context>(),
            factory = KwaborDatabaseConstructor::initialize,
        ),
        queryCoroutineContext = queryCoroutineContext,
        driver = AndroidSQLiteDriver(),
    )
    try {
        block(database)
    } finally {
        database.close()
    }
}

private class BarrierExploreReferenceDao(
    private val delegate: ExploreReferenceDao,
    private val readStarted: CompletableDeferred<Unit>,
    private val continueRead: CompletableDeferred<Unit>,
) : ExploreReferenceDao by delegate {
    override suspend fun readReference(snapshotKey: String): ExploreReferenceRecord? {
        val record = delegate.readReference(snapshotKey)
        readStarted.complete(Unit)
        continueRead.await()
        return record
    }
}

private fun referenceSnapshot(): ExploreReferenceSnapshot = ExploreReferenceSnapshot(
    cities = listOf(
        City(
            id = "cotonou",
            name = "Cotonou",
            latitude = 6.3703,
            longitude = 2.3912,
        ),
        City(id = "ouidah", name = "Ouidah"),
    ),
    categories = listOf(
        category(
            id = "sites",
            nameKey = "category_sites",
            listingType = ListingType.Place,
            listingClass = ListingClass.Heritage,
        ),
        category(),
        category(
            id = "events",
            nameKey = "category_events",
            listingType = ListingType.Event,
            listingClass = ListingClass.Event,
        ),
    ),
    cachedAtEpochMilliseconds = 1_000,
)

private fun category(
    id: String = "restaurants",
    nameKey: String = "category_restaurants",
    listingType: ListingType = ListingType.Establishment,
    listingClass: ListingClass = ListingClass.Commercial,
): Category = Category(
    id = id,
    nameKey = nameKey,
    listingType = listingType,
    defaultListingClass = listingClass,
)

private fun referenceSnapshotEntity(cityCount: Int): ExploreReferenceSnapshotEntity = ExploreReferenceSnapshotEntity(
    snapshotKey = REFERENCE_KEY,
    cachedAtEpochMilliseconds = 2_000,
    cityCount = cityCount,
    categoryCount = 0,
)

private fun cityEntity(
    id: String = "cotonou",
    position: Int = 0,
    latitude: Double? = null,
    longitude: Double? = null,
): ExploreReferenceCityEntity = ExploreReferenceCityEntity(
    snapshotKey = REFERENCE_KEY,
    cityId = id,
    position = position,
    name = id,
    countryCode = "BJ",
    latitude = latitude,
    longitude = longitude,
)

private const val REFERENCE_KEY = "explore"
