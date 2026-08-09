package com.kwabor.shared.data.explore

import com.kwabor.shared.data.local.ExploreReferenceSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ExploreFeedLocalCacheV2Test {
    @Test
    fun cacheConversionPreservesServerSnapshotSeparatelyFromLocalFreshness() {
        val networkSnapshot = ExploreFeedSnapshot(
            cities = emptyList(),
            categories = emptyList(),
            items = emptyList(),
            nextCursor = null,
            cachedAtEpochMilliseconds = LOCAL_CACHED_AT_MILLISECONDS,
            source = ExploreFeedSource.Network,
            serverSnapshotAtEpochMicroseconds = SERVER_SNAPSHOT_AT_MICROSECONDS,
        )

        val cachedSnapshot = networkSnapshot.toCacheSnapshot(CACHE_KEY)
        val restored = cachedSnapshot.toDomain(
            ExploreReferenceSnapshot(
                cities = emptyList(),
                categories = emptyList(),
                cachedAtEpochMilliseconds = LOCAL_CACHED_AT_MILLISECONDS,
            ),
        )

        assertEquals(LOCAL_CACHED_AT_MILLISECONDS, restored.cachedAtEpochMilliseconds)
        assertEquals(SERVER_SNAPSHOT_AT_MICROSECONDS, restored.serverSnapshotAtEpochMicroseconds)
        assertEquals(ExploreFeedSource.Cache, restored.source)
    }
}

private const val CACHE_KEY = "explore-feed:v2:test"
private const val LOCAL_CACHED_AT_MILLISECONDS = 1_783_073_730_000
private const val SERVER_SNAPSHOT_AT_MICROSECONDS = 1_783_073_730_000_123
