package com.kwabor.shared.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExploreCacheDaoTest {
    @Test
    fun storeRoundTripsOrderedSnapshotCursorTimestampAndPlacement() = runTest {
        withDatabase(coroutineContext) { database ->
            val store = ExploreCacheStore(database.exploreCacheDao())
            val snapshot = ExploreCacheSnapshot(
                snapshotKey = "explore:cotonou:establishments",
                items = listOf(
                    listingSummary(id = "listing-2", name = "Second", isSponsoredPlacement = false),
                    listingSummary(id = "listing-1", name = "Premier", isSponsoredPlacement = true),
                ),
                nextCursor = "cursor-next",
                cachedAtEpochMilliseconds = 2_000,
            )

            store.replace(snapshot)

            assertEquals(snapshot, store.read(snapshot.snapshotKey))
        }
    }

    @Test
    fun replacementIsAtomicAndRemovesStaleRows() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao)
            val original = ExploreCacheSnapshot(
                snapshotKey = "explore:places",
                items = listOf(listingSummary(id = "listing-original")),
                nextCursor = "cursor-original",
                cachedAtEpochMilliseconds = 1_000,
            )
            store.replace(original)
            val replacementListing = listingSummary(id = "listing-replacement")
            val replacementEntity = replacementListing.toExploreCachedListingEntity(
                cachedAtEpochMilliseconds = 2_000,
            )
            val duplicateItem = replacementListing.toExploreCacheSnapshotItemEntity(
                snapshotKey = original.snapshotKey,
                position = 0,
            )

            assertFails {
                dao.replaceSnapshot(
                    snapshot = ExploreCacheSnapshotEntity(
                        snapshotKey = original.snapshotKey,
                        nextCursor = "cursor-replacement",
                        cachedAtEpochMilliseconds = 2_000,
                        itemCount = 2,
                    ),
                    listings = listOf(replacementEntity),
                    items = listOf(duplicateItem, duplicateItem.copy(position = 1)),
                    maxSnapshotCount = DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS,
                )
            }

            assertEquals(original, store.read(original.snapshotKey))
            assertTrue(dao.findListingTimestamps(listOf("listing-replacement")).isEmpty())
        }
    }

    @Test
    fun successfulReplacementRemovesStaleRowsAndAcceptsAnEmptySnapshot() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao)
            val key = "explore:replace"
            store.replace(
                ExploreCacheSnapshot(
                    snapshotKey = key,
                    items = listOf(listingSummary(id = "listing-stale")),
                    nextCursor = "cursor-stale",
                    cachedAtEpochMilliseconds = 1_000,
                ),
            )

            val emptyReplacement = ExploreCacheSnapshot(
                snapshotKey = key,
                items = emptyList(),
                nextCursor = null,
                cachedAtEpochMilliseconds = 2_000,
            )
            store.replace(emptyReplacement)

            assertEquals(emptyReplacement, store.read(key))
            assertTrue(dao.findListingTimestamps(listOf("listing-stale")).isEmpty())
        }
    }

    @Test
    fun canonicalContentIgnoresOlderWritesWhilePlacementRemainsSnapshotSpecific() = runTest {
        withDatabase(coroutineContext) { database ->
            val store = ExploreCacheStore(database.exploreCacheDao())
            val recent = ExploreCacheSnapshot(
                snapshotKey = "explore:recent",
                items = listOf(
                    listingSummary(
                        name = "Nom recent",
                        isSponsoredPlacement = true,
                    ),
                ),
                nextCursor = null,
                cachedAtEpochMilliseconds = 2_000,
            )
            val older = ExploreCacheSnapshot(
                snapshotKey = "explore:older",
                items = listOf(
                    listingSummary(
                        name = "Nom ancien",
                        isSponsoredPlacement = false,
                    ),
                ),
                nextCursor = null,
                cachedAtEpochMilliseconds = 1_000,
            )

            store.replace(recent)
            store.replace(older)

            val recentRead = assertNotNull(store.read(recent.snapshotKey))
            val olderRead = assertNotNull(store.read(older.snapshotKey))
            assertEquals("Nom recent", recentRead.items.single().name)
            assertEquals("Nom recent", olderRead.items.single().name)
            assertEquals(true, recentRead.items.single().isSponsoredPlacement)
            assertEquals(false, olderRead.items.single().isSponsoredPlacement)
        }
    }

    @Test
    fun clearPrunesCanonicalContentOnlyAfterItsLastSnapshotIsRemoved() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao)
            val first = ExploreCacheSnapshot(
                snapshotKey = "explore:first",
                items = listOf(listingSummary()),
                nextCursor = null,
                cachedAtEpochMilliseconds = 1_000,
            )
            val second = first.copy(snapshotKey = "explore:second", cachedAtEpochMilliseconds = 2_000)
            store.replace(first)
            store.replace(second)

            store.clear(first.snapshotKey)

            assertNull(store.read(first.snapshotKey))
            assertNotNull(store.read(second.snapshotKey))
            assertEquals(1, dao.findListingTimestamps(listOf("listing-1")).size)

            store.clear(second.snapshotKey)

            assertTrue(dao.findListingTimestamps(listOf("listing-1")).isEmpty())
        }
    }

    @Test
    fun corruptSnapshotIsEvictedAsACacheMissWithoutTouchingHealthySnapshots() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao)
            val healthy = ExploreCacheSnapshot(
                snapshotKey = "explore:healthy",
                items = listOf(listingSummary(id = "listing-healthy")),
                nextCursor = null,
                cachedAtEpochMilliseconds = 1_000,
            )
            store.replace(healthy)
            val corruptListing = listingSummary(id = "listing-corrupt")
            dao.replaceSnapshot(
                snapshot = ExploreCacheSnapshotEntity(
                    snapshotKey = "explore:corrupt",
                    nextCursor = null,
                    cachedAtEpochMilliseconds = 2_000,
                    itemCount = 1,
                ),
                listings = listOf(
                    corruptListing.toExploreCachedListingEntity(cachedAtEpochMilliseconds = 2_000)
                        .copy(likesCount = -1),
                ),
                items = listOf(
                    corruptListing.toExploreCacheSnapshotItemEntity("explore:corrupt", position = 0),
                ),
                maxSnapshotCount = DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS,
            )

            assertNull(store.read("explore:corrupt"))
            assertEquals(healthy, store.read(healthy.snapshotKey))
            assertTrue(dao.findListingTimestamps(listOf("listing-corrupt")).isEmpty())
        }
    }

    @Test
    fun retentionKeepsOnlyTheMostRecentConfiguredSnapshots() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao = dao, maxSnapshotCount = 2)
            listOf(1L, 2L, THIRD_SNAPSHOT_SEQUENCE).forEach { sequence ->
                store.replace(
                    ExploreCacheSnapshot(
                        snapshotKey = "explore:$sequence",
                        items = listOf(listingSummary(id = "listing-$sequence")),
                        nextCursor = null,
                        cachedAtEpochMilliseconds = sequence,
                    ),
                )
            }

            assertNull(store.read("explore:1"))
            assertNotNull(store.read("explore:2"))
            assertNotNull(store.read("explore:3"))
            assertTrue(dao.findListingTimestamps(listOf("listing-1")).isEmpty())
        }
    }

    @Test
    fun replaceRejectsInvalidListingValuesBeforeWriting() = runTest {
        withDatabase(coroutineContext) { database ->
            val store = ExploreCacheStore(database.exploreCacheDao())
            val invalidSnapshot = ExploreCacheSnapshot(
                snapshotKey = "explore:invalid",
                items = listOf(listingSummary().copy(ratingAverage = Double.NaN, likesCount = -1)),
                nextCursor = null,
                cachedAtEpochMilliseconds = 1_000,
            )

            assertFailsWith<IllegalArgumentException> { store.replace(invalidSnapshot) }
            assertNull(store.read(invalidSnapshot.snapshotKey))
        }
    }
}

private const val THIRD_SNAPSHOT_SEQUENCE = 3L

private suspend fun withDatabase(queryCoroutineContext: CoroutineContext, block: suspend (KwaborDatabase) -> Unit) {
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
