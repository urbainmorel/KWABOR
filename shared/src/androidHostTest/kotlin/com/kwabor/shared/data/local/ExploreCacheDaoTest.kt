package com.kwabor.shared.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.data.explore.toCacheKey
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.explore.ExploreFeedQuery
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
                    v2ListingSummary(
                        index = 2,
                        name = "Second",
                        isSponsoredPlacement = false,
                        isEventEnded = false,
                    ),
                    v2ListingSummary(
                        index = 1,
                        name = "Premier",
                        isSponsoredPlacement = false,
                        isEventEnded = false,
                    ).copy(
                        type = ListingType.Event,
                        listingClass = ListingClass.Event,
                        eventStartAtEpochMilliseconds = 1_900,
                        eventEndAtEpochMilliseconds = 2_100,
                    ),
                ),
                nextCursor = "cursor-next",
                cachedAtEpochMilliseconds = 2_000,
                serverSnapshotAtEpochMicroseconds = 2_000_123,
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
            val recent = canonicalContentSnapshot(
                snapshotKey = "explore:recent",
                listing = canonicalContentListing(
                    name = "Nom recent",
                    coverImageAlt = "Façade récente",
                    viewsCount = RECENT_VIEWS_COUNT,
                    isSponsoredPlacement = true,
                ),
                cachedAtEpochMilliseconds = RECENT_CACHE_TIMESTAMP,
            )
            val older = canonicalContentSnapshot(
                snapshotKey = "explore:older",
                listing = canonicalContentListing(
                    name = "Nom ancien",
                    coverImageAlt = "Façade ancienne",
                    viewsCount = OLDER_VIEWS_COUNT,
                    isSponsoredPlacement = false,
                ),
                cachedAtEpochMilliseconds = OLDER_CACHE_TIMESTAMP,
            )

            store.replace(recent)
            store.replace(older)

            val recentRead = assertNotNull(store.read(recent.snapshotKey))
            val olderRead = assertNotNull(store.read(older.snapshotKey))
            assertCanonicalContentAndSnapshotPlacement(recentRead, olderRead)
        }
    }

    @Test
    fun eventEndedStateRemainsSnapshotSpecificWhileEventDatesStayCanonical() = runTest {
        withDatabase(coroutineContext) { database ->
            val store = ExploreCacheStore(database.exploreCacheDao())
            val recent = eventSnapshot(
                snapshotKey = "explore:event:recent",
                startAtEpochMilliseconds = CANONICAL_EVENT_START,
                endAtEpochMilliseconds = CANONICAL_EVENT_END,
                isEventEnded = false,
                cachedAtEpochMilliseconds = RECENT_CACHE_TIMESTAMP,
            )
            val older = eventSnapshot(
                snapshotKey = "explore:event:older",
                startAtEpochMilliseconds = STALE_EVENT_START,
                endAtEpochMilliseconds = STALE_EVENT_END,
                isEventEnded = true,
                cachedAtEpochMilliseconds = OLDER_CACHE_TIMESTAMP,
            )

            store.replace(recent)
            store.replace(older)

            val recentRead = assertNotNull(store.read(recent.snapshotKey)).items.single()
            val olderRead = assertNotNull(store.read(older.snapshotKey)).items.single()
            assertEquals(CANONICAL_EVENT_START, olderRead.eventStartAtEpochMilliseconds)
            assertEquals(CANONICAL_EVENT_END, olderRead.eventEndAtEpochMilliseconds)
            assertEquals(false, recentRead.isEventEnded)
            assertEquals(true, olderRead.isEventEnded)
        }
    }

    @Test
    fun appendAcrossCacheKeysDoesNotOutrankContentFetchedByAnEarlierRequest() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao)
            val sharedListingId = "listing-shared"
            val staleListing = listingSummary(id = sharedListingId, name = "Nom initial")
            val initialWall = ExploreCacheSnapshot(
                snapshotKey = "explore:places",
                items = listOf(staleListing),
                nextCursor = "cursor-initial",
                cachedAtEpochMilliseconds = 1_000,
            )
            store.replace(initialWall)

            store.replace(
                initialWall.copy(
                    items = listOf(staleListing, listingSummary(id = "listing-appended")),
                    nextCursor = "cursor-appended",
                    cachedAtEpochMilliseconds = 3_000,
                    itemCachedAtEpochMilliseconds = mapOf(
                        sharedListingId to initialWall.cachedAtEpochMilliseconds,
                    ),
                ),
            )
            val fresherWall = ExploreCacheSnapshot(
                snapshotKey = "explore:heritage",
                items = listOf(listingSummary(id = sharedListingId, name = "Nom réellement plus frais")),
                nextCursor = null,
                cachedAtEpochMilliseconds = 2_000,
            )
            store.replace(fresherWall)

            val places = assertNotNull(store.read(initialWall.snapshotKey))
            assertEquals("Nom réellement plus frais", places.items.first().name)
            assertEquals(
                fresherWall.cachedAtEpochMilliseconds,
                dao.findListingTimestamps(listOf(sharedListingId)).single().contentCachedAtEpochMilliseconds,
            )
        }
    }

    @Test
    fun watermarkIncludesCanonicalListingContentRetainedByAnOlderSnapshot() = runTest {
        withDatabase(coroutineContext) { database ->
            val store = ExploreCacheStore(database.exploreCacheDao())
            val older = ExploreCacheSnapshot(
                snapshotKey = "explore:watermark-older",
                items = listOf(listingSummary(name = "Ancien")),
                nextCursor = null,
                cachedAtEpochMilliseconds = OLDER_WATERMARK_TIMESTAMP,
            )
            val newer = older.copy(
                snapshotKey = "explore:watermark-newer",
                items = listOf(listingSummary(name = "Récent")),
                cachedAtEpochMilliseconds = CANONICAL_LISTING_WATERMARK,
            )
            store.replace(older)
            store.replace(newer)
            store.clear(newer.snapshotKey)

            val watermark = ExplorePersistenceWatermarkStore(database.explorePersistenceWatermarkDao()).read()

            assertEquals(CANONICAL_LISTING_WATERMARK, watermark)
            assertEquals(
                OLDER_WATERMARK_TIMESTAMP,
                assertNotNull(store.read(older.snapshotKey)).cachedAtEpochMilliseconds,
            )
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
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExploreCacheIntegrityAndRetentionTest {
    @Test
    fun corruptSnapshotEvictionCannotDeleteANewerReplacementAfterAStaleRead() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val snapshotKey = "explore:conditional-clear"
            val corruptListing = listingSummary(id = "listing-corrupt")
            dao.replaceSnapshot(
                snapshot = ExploreCacheSnapshotEntity(
                    snapshotKey = snapshotKey,
                    nextCursor = "cursor-old",
                    cachedAtEpochMilliseconds = 1_000,
                    itemCount = 1,
                ),
                listings = listOf(
                    corruptListing.toExploreCachedListingEntity(cachedAtEpochMilliseconds = 1_000)
                        .copy(likesCount = -1),
                ),
                items = listOf(corruptListing.toExploreCacheSnapshotItemEntity(snapshotKey, position = 0)),
                maxSnapshotCount = DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS,
            )
            val readStarted = CompletableDeferred<Unit>()
            val continueRead = CompletableDeferred<Unit>()
            val staleStore = ExploreCacheStore(BarrierExploreCacheDao(dao, readStarted, continueRead))
            val staleRead = async { staleStore.read(snapshotKey) }
            readStarted.await()
            val fresh = ExploreCacheSnapshot(
                snapshotKey = snapshotKey,
                items = listOf(listingSummary(id = "listing-fresh", name = "Nouveau")),
                nextCursor = "cursor-new",
                cachedAtEpochMilliseconds = 2_000,
            )
            val currentStore = ExploreCacheStore(dao)
            currentStore.replace(fresh)

            continueRead.complete(Unit)

            assertNull(staleRead.await())
            assertEquals(fresh, currentStore.read(snapshotKey))
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
    fun olderOrEqualSnapshotCannotReplaceNewerCanonicalContent() = runTest {
        withDatabase(coroutineContext) { database ->
            val store = ExploreCacheStore(database.exploreCacheDao())
            val newer = ExploreCacheSnapshot(
                snapshotKey = "explore:ordered",
                items = listOf(listingSummary(id = "listing-ordered", name = "Nouveau")),
                nextCursor = "cursor-new",
                cachedAtEpochMilliseconds = 2_000,
            )
            store.replace(newer)

            val equalResult = store.replace(
                newer.copy(
                    items = listOf(listingSummary(id = "listing-ordered", name = "Egal")),
                    nextCursor = "cursor-equal",
                ),
            )
            val olderResult = store.replace(
                newer.copy(
                    items = listOf(listingSummary(id = "listing-ordered", name = "Ancien")),
                    nextCursor = "cursor-old",
                    cachedAtEpochMilliseconds = 1_000,
                ),
            )

            assertEquals(ExplorePersistenceWriteResult.Rejected, equalResult)
            assertEquals(ExplorePersistenceWriteResult.Rejected, olderResult)
            assertEquals(newer, store.read(newer.snapshotKey))
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
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExploreCacheV2ValidationTest {
    @Test
    fun v2KeyRequiresServerSnapshotForNonEmptyReadAndWrite() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao)
            val snapshotKey = realV2CacheKey()
            val listing = v2ListingSummary(index = V2_KEY_VALIDATION_LISTING_INDEX)

            assertFailsWith<IllegalArgumentException> {
                store.replace(v2Snapshot(snapshotKey, listing, serverSnapshotAtEpochMicroseconds = null))
            }
            persistV2Snapshot(
                dao = dao,
                snapshotKey = snapshotKey,
                listing = listing,
                serverSnapshotAtEpochMicroseconds = null,
            )
            assertNull(store.read(snapshotKey))
            assertNull(dao.findSnapshot(snapshotKey))

            val emptyTerminal = ExploreCacheSnapshot(
                snapshotKey = snapshotKey,
                items = emptyList(),
                nextCursor = null,
                cachedAtEpochMilliseconds = DEFAULT_V2_CACHED_AT_MILLISECONDS,
            )
            store.replace(emptyTerminal)
            assertEquals(emptyTerminal, store.read(snapshotKey))
        }
    }

    @Test
    fun v2WriteRejectsEveryMissingRequiredNetworkMetadataField() = runTest {
        withDatabase(coroutineContext) { database ->
            val store = ExploreCacheStore(database.exploreCacheDao())
            val valid = v2ListingSummary()
            val invalidListings = listingsMissingNetworkMetadata(
                valid = valid,
                firstIdIndex = WRITE_MISSING_METADATA_ID_START,
            )

            invalidListings.forEachIndexed { index, listing ->
                val snapshotKey = "explore:v2:missing:$index"
                assertFailsWith<IllegalArgumentException> {
                    store.replace(
                        ExploreCacheSnapshot(
                            snapshotKey = snapshotKey,
                            items = listOf(listing),
                            nextCursor = null,
                            cachedAtEpochMilliseconds = index + 1L,
                            serverSnapshotAtEpochMicroseconds = (index + 1L) * 1_000,
                        ),
                    )
                }
                assertNull(store.read(snapshotKey))
            }
        }
    }

    @Test
    fun v2ReadEvictsEveryMissingRequiredNetworkMetadataField() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao)
            val valid = v2ListingSummary()
            val invalidListings = listingsMissingNetworkMetadata(
                valid = valid,
                firstIdIndex = READ_MISSING_METADATA_ID_START,
            )

            invalidListings.forEachIndexed { index, listing ->
                val snapshotKey = "explore:v2:persisted-missing:$index"
                val cachedAt = index + 1L
                dao.replaceSnapshot(
                    snapshot = ExploreCacheSnapshotEntity(
                        snapshotKey = snapshotKey,
                        nextCursor = null,
                        serverSnapshotAtEpochMicroseconds = cachedAt * 1_000,
                        cachedAtEpochMilliseconds = cachedAt,
                        itemCount = 1,
                    ),
                    listings = listOf(listing.toExploreCachedListingEntity(cachedAt)),
                    items = listOf(listing.toExploreCacheSnapshotItemEntity(snapshotKey, position = 0)),
                    maxSnapshotCount = DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS,
                )

                assertNull(store.read(snapshotKey))
                assertNull(dao.findSnapshot(snapshotKey))
            }
        }
    }

    @Test
    fun v2WriteRejectsNonCanonicalNetworkScalarsAndAcceptsEightyUnicodeCodePoints() = runTest {
        withDatabase(coroutineContext) { database ->
            val store = ExploreCacheStore(database.exploreCacheDao())
            val unicodeBoundary = v2ListingSummary(
                index = 50,
                name = UNICODE_LISTING_NAME_CHARACTER.repeat(MAX_EXPLORE_V2_TEST_NAME_LENGTH),
            )
            store.replace(v2Snapshot("explore:v2:unicode-boundary", unicodeBoundary))
            assertNotNull(store.read("explore:v2:unicode-boundary"))

            val valid = v2ListingSummary(index = 51)
            val invalidListings = listingsWithNonCanonicalNetworkScalars(
                valid = valid,
                firstGeneratedIdIndex = WRITE_INVALID_SCALAR_ID_START,
            )
            invalidListings.forEachIndexed { index, listing ->
                assertFailsWith<IllegalArgumentException> {
                    store.replace(v2Snapshot("explore:v2:invalid-scalar:$index", listing))
                }
            }
            assertFailsWith<IllegalArgumentException> {
                store.replace(
                    v2Snapshot("explore:v2:invalid-cursor", valid).copy(nextCursor = "cursor with-space"),
                )
            }
        }
    }

    @Test
    fun v2ReadEvictsPersistedNonCanonicalNetworkScalarsAndCursor() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao)
            val valid = v2ListingSummary(index = 60)
            val invalidListings = listingsWithNonCanonicalNetworkScalars(
                valid = valid,
                firstGeneratedIdIndex = READ_INVALID_SCALAR_ID_START,
            )
            invalidListings.forEachIndexed { index, listing ->
                val snapshotKey = "explore:v2:persisted-invalid-scalar:$index"
                persistV2Snapshot(dao, snapshotKey, listing)
                assertNull(store.read(snapshotKey))
                assertNull(dao.findSnapshot(snapshotKey))
            }

            val cursorSnapshotKey = "explore:v2:persisted-invalid-cursor"
            persistV2Snapshot(dao, cursorSnapshotKey, valid, nextCursor = "cursor with-space")
            assertNull(store.read(cursorSnapshotKey))
            assertNull(dao.findSnapshot(cursorSnapshotKey))
        }
    }

    @Test
    fun v2WriteRejectsSponsoredPlacementsOutsideTheBoundedPrefix() = runTest {
        withDatabase(coroutineContext) { database ->
            val store = ExploreCacheStore(database.exploreCacheDao())
            val organicThenSponsored = listOf(
                v2ListingSummary(index = 30, isSponsoredPlacement = false),
                v2ListingSummary(index = 31, isSponsoredPlacement = true),
            )
            val threeSponsored = invalidSponsoredPrefix(firstIdIndex = WRITE_SPONSORED_ID_START)

            listOf(organicThenSponsored, threeSponsored).forEachIndexed { index, items ->
                assertFailsWith<IllegalArgumentException> {
                    store.replace(
                        ExploreCacheSnapshot(
                            snapshotKey = "explore:v2:sponsor-write:$index",
                            items = items,
                            nextCursor = null,
                            cachedAtEpochMilliseconds = index + 1L,
                            serverSnapshotAtEpochMicroseconds = (index + 1L) * 1_000,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun v2ReadEvictsSponsoredPlacementsOutsideTheBoundedPrefix() = runTest {
        withDatabase(coroutineContext) { database ->
            val dao = database.exploreCacheDao()
            val store = ExploreCacheStore(dao)
            val invalidSnapshots = listOf(
                listOf(
                    v2ListingSummary(index = 40, isSponsoredPlacement = false),
                    v2ListingSummary(index = 41, isSponsoredPlacement = true),
                ),
                invalidSponsoredPrefix(firstIdIndex = READ_SPONSORED_ID_START),
            )

            invalidSnapshots.forEachIndexed { index, listings ->
                val snapshotKey = "explore:v2:sponsor-read:$index"
                val cachedAt = index + 1L
                dao.replaceSnapshot(
                    snapshot = ExploreCacheSnapshotEntity(
                        snapshotKey = snapshotKey,
                        nextCursor = null,
                        serverSnapshotAtEpochMicroseconds = cachedAt * 1_000,
                        cachedAtEpochMilliseconds = cachedAt,
                        itemCount = listings.size,
                    ),
                    listings = listings.map { listing -> listing.toExploreCachedListingEntity(cachedAt) },
                    items = listings.mapIndexed { position, listing ->
                        listing.toExploreCacheSnapshotItemEntity(snapshotKey, position)
                    },
                    maxSnapshotCount = DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS,
                )

                assertNull(store.read(snapshotKey))
                assertNull(dao.findSnapshot(snapshotKey))
            }
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

            assertFailsWith<IllegalArgumentException> {
                store.replace(
                    invalidSnapshot.copy(
                        items = listOf(listingSummary()),
                        serverSnapshotAtEpochMicroseconds = -1,
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                store.replace(
                    invalidSnapshot.copy(
                        items = listOf(v2ListingSummary().copy(coverImageAlt = null)),
                        serverSnapshotAtEpochMicroseconds = 1_000_000,
                    ),
                )
            }
        }
    }
}

private fun canonicalContentSnapshot(
    snapshotKey: String,
    listing: ListingSummary,
    cachedAtEpochMilliseconds: Long,
): ExploreCacheSnapshot = ExploreCacheSnapshot(
    snapshotKey = snapshotKey,
    items = listOf(listing),
    nextCursor = null,
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
)

private fun canonicalContentListing(
    name: String,
    coverImageAlt: String,
    viewsCount: Long,
    isSponsoredPlacement: Boolean,
): ListingSummary = listingSummary(
    name = name,
    isSponsoredPlacement = isSponsoredPlacement,
    coverImageAlt = coverImageAlt,
    isEventEnded = false,
).copy(viewsCount = viewsCount)

private fun assertCanonicalContentAndSnapshotPlacement(
    recentSnapshot: ExploreCacheSnapshot,
    olderSnapshot: ExploreCacheSnapshot,
) {
    val recentListing = recentSnapshot.items.single()
    val olderListing = olderSnapshot.items.single()
    assertEquals("Nom recent", recentListing.name)
    assertEquals("Nom recent", olderListing.name)
    assertEquals("Façade récente", olderListing.coverImageAlt)
    assertEquals(RECENT_VIEWS_COUNT, olderListing.viewsCount)
    assertEquals(true, recentListing.isSponsoredPlacement)
    assertEquals(false, olderListing.isSponsoredPlacement)
    assertEquals(false, recentListing.isEventEnded)
    assertEquals(false, olderListing.isEventEnded)
}

private fun eventSnapshot(
    snapshotKey: String,
    startAtEpochMilliseconds: Long,
    endAtEpochMilliseconds: Long,
    isEventEnded: Boolean,
    cachedAtEpochMilliseconds: Long,
): ExploreCacheSnapshot = v2Snapshot(
    snapshotKey = snapshotKey,
    listing = v2ListingSummary(
        index = EVENT_LISTING_INDEX,
        isSponsoredPlacement = false,
        isEventEnded = isEventEnded,
    ).copy(
        type = ListingType.Event,
        listingClass = ListingClass.Event,
        eventStartAtEpochMilliseconds = startAtEpochMilliseconds,
        eventEndAtEpochMilliseconds = endAtEpochMilliseconds,
    ),
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    serverSnapshotAtEpochMicroseconds = cachedAtEpochMilliseconds * MICROSECONDS_PER_MILLISECOND,
)

private fun listingsMissingNetworkMetadata(valid: ListingSummary, firstIdIndex: Int): List<ListingSummary> = listOf(
    valid.copy(id = v2ListingId(firstIdIndex), viewsCount = null),
    valid.copy(id = v2ListingId(firstIdIndex + 1), isSponsoredPlacement = null),
    valid.copy(id = v2ListingId(firstIdIndex + 2), isEventEnded = null),
)

private fun listingsWithNonCanonicalNetworkScalars(
    valid: ListingSummary,
    firstGeneratedIdIndex: Int,
): List<ListingSummary> = buildList {
    add(valid.copy(id = "not-a-uuid"))
    add(
        valid.copy(
            id = v2ListingId(firstGeneratedIdIndex),
            name = "a".repeat(MAX_EXPLORE_V2_TEST_NAME_LENGTH + 1),
        ),
    )
    addAll(
        NON_CANONICAL_CACHE_URLS.mapIndexed { index, url ->
            valid.copy(
                id = v2ListingId(firstGeneratedIdIndex + index + 1),
                coverImageUrl = url,
            )
        },
    )
}

private fun invalidSponsoredPrefix(firstIdIndex: Int): List<ListingSummary> =
    (1..INVALID_SPONSORED_PLACEMENT_COUNT).map { offset ->
        v2ListingSummary(
            index = firstIdIndex + offset,
            isSponsoredPlacement = true,
        )
    }

private fun v2ListingSummary(
    index: Int = 1,
    name: String = "Restaurant Kwabor",
    isSponsoredPlacement: Boolean = false,
    isEventEnded: Boolean = false,
): ListingSummary = listingSummary(
    id = v2ListingId(index),
    name = name,
    isSponsoredPlacement = isSponsoredPlacement,
    isEventEnded = isEventEnded,
)

private fun v2ListingId(index: Int): String =
    "00000000-0000-4000-8000-${index.toString().padStart(UUID_SUFFIX_LENGTH, '0')}"

private fun v2Snapshot(
    snapshotKey: String,
    listing: ListingSummary,
    cachedAtEpochMilliseconds: Long = DEFAULT_V2_CACHED_AT_MILLISECONDS,
    serverSnapshotAtEpochMicroseconds: Long? = DEFAULT_V2_SERVER_SNAPSHOT_MICROSECONDS,
): ExploreCacheSnapshot = ExploreCacheSnapshot(
    snapshotKey = snapshotKey,
    items = listOf(listing),
    nextCursor = null,
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    serverSnapshotAtEpochMicroseconds = serverSnapshotAtEpochMicroseconds,
)

private suspend fun persistV2Snapshot(
    dao: ExploreCacheDao,
    snapshotKey: String,
    listing: ListingSummary,
    nextCursor: String? = null,
    serverSnapshotAtEpochMicroseconds: Long? = DEFAULT_V2_SERVER_SNAPSHOT_MICROSECONDS,
) {
    dao.replaceSnapshot(
        snapshot = ExploreCacheSnapshotEntity(
            snapshotKey = snapshotKey,
            nextCursor = nextCursor,
            serverSnapshotAtEpochMicroseconds = serverSnapshotAtEpochMicroseconds,
            cachedAtEpochMilliseconds = DEFAULT_V2_CACHED_AT_MILLISECONDS,
            itemCount = 1,
        ),
        listings = listOf(listing.toExploreCachedListingEntity(DEFAULT_V2_CACHED_AT_MILLISECONDS)),
        items = listOf(listing.toExploreCacheSnapshotItemEntity(snapshotKey, position = 0)),
        maxSnapshotCount = DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS,
    )
}

private fun realV2CacheKey(): String = ExploreFeedQuery(
    filters = ListingFilters(listingType = ListingType.Place),
).toCacheKey()

private const val RECENT_VIEWS_COUNT = 200L
private const val OLDER_VIEWS_COUNT = 100L
private const val RECENT_CACHE_TIMESTAMP = 2_000L
private const val OLDER_CACHE_TIMESTAMP = 1_000L
private const val CANONICAL_EVENT_START = -2_000L
private const val CANONICAL_EVENT_END = 3_000L
private const val STALE_EVENT_START = -3_000L
private const val STALE_EVENT_END = 2_000L
private const val EVENT_LISTING_INDEX = 3
private const val MICROSECONDS_PER_MILLISECOND = 1_000L

private const val WRITE_MISSING_METADATA_ID_START = 10
private const val READ_MISSING_METADATA_ID_START = 20
private const val WRITE_INVALID_SCALAR_ID_START = 52
private const val READ_INVALID_SCALAR_ID_START = 61
private const val WRITE_SPONSORED_ID_START = 31
private const val READ_SPONSORED_ID_START = 41
private const val INVALID_SPONSORED_PLACEMENT_COUNT = 3
private const val V2_KEY_VALIDATION_LISTING_INDEX = 70
private const val OVERSIZED_CACHE_URL_CODE_POINT_COUNT = 600

private const val DEFAULT_V2_CACHED_AT_MILLISECONDS = 1_000L
private const val DEFAULT_V2_SERVER_SNAPSHOT_MICROSECONDS = 1_000_000L
private const val UUID_SUFFIX_LENGTH = 12
private const val MAX_EXPLORE_V2_TEST_NAME_LENGTH = 80
private const val UNICODE_LISTING_NAME_CHARACTER = "🏨"
private val NON_CANONICAL_CACHE_URLS = listOf(
    "http://cdn.kwabor.test/cover.jpg",
    "https://cdn.kwabor.test/bad cover.jpg",
    "https://user@cdn.kwabor.test/cover.jpg",
    "https://CDN.kwabor.test/cover.jpg",
    "https://cdn.kwabor.test/cover.jpg#fragment",
    "https://cdn.kwabor.test/${"🐕".repeat(OVERSIZED_CACHE_URL_CODE_POINT_COUNT)}",
)

private const val THIRD_SNAPSHOT_SEQUENCE = 3L
private const val OLDER_WATERMARK_TIMESTAMP = 1_000L
private const val CANONICAL_LISTING_WATERMARK = 9_000L

private class BarrierExploreCacheDao(
    private val delegate: ExploreCacheDao,
    private val readStarted: CompletableDeferred<Unit>,
    private val continueRead: CompletableDeferred<Unit>,
) : ExploreCacheDao by delegate {
    override suspend fun readSnapshot(snapshotKey: String): ExploreCacheRecord? {
        val record = delegate.readSnapshot(snapshotKey)
        readStarted.complete(Unit)
        continueRead.await()
        return record
    }
}

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
