package com.kwabor.shared.data.explore

import androidx.sqlite.SQLiteException
import com.kwabor.shared.data.local.ExploreCacheSnapshot
import com.kwabor.shared.data.local.ExploreCacheStore
import com.kwabor.shared.data.local.ExploreFeedPersistenceStore
import com.kwabor.shared.data.local.ExplorePersistenceWatermarkStore
import com.kwabor.shared.data.local.ExplorePersistenceWriteResult
import com.kwabor.shared.data.local.ExploreReferenceSnapshot
import com.kwabor.shared.data.local.ExploreReferenceStore
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import kotlinx.coroutines.CancellationException

internal interface ExploreWallCache {
    suspend fun read(snapshotKey: String): ExploreCacheSnapshot?

    suspend fun replace(snapshot: ExploreCacheSnapshot): ExplorePersistenceWriteResult

    suspend fun clear(snapshotKey: String, expectedCachedAtEpochMilliseconds: Long): Boolean
}

internal interface ExploreReferenceCache {
    suspend fun read(): ExploreReferenceSnapshot?

    suspend fun replace(snapshot: ExploreReferenceSnapshot)
}

internal interface ExploreFeedPersistenceCache {
    suspend fun replace(
        wall: ExploreCacheSnapshot,
        references: ExploreReferenceSnapshot,
    ): ExplorePersistenceWriteResult
}

internal data class ExploreFeedCacheDependencies(
    val wall: ExploreWallCache? = null,
    val references: ExploreReferenceCache? = null,
    val persistence: ExploreFeedPersistenceCache? = null,
    val watermarkProvider: ExplorePersistenceWatermarkProvider =
        EMPTY_EXPLORE_PERSISTENCE_WATERMARK_PROVIDER,
)

internal class StoredExploreWallCache(
    private val store: Lazy<ExploreCacheStore>,
) : ExploreWallCache {
    override suspend fun read(snapshotKey: String): ExploreCacheSnapshot? = store.value.read(snapshotKey)

    override suspend fun replace(snapshot: ExploreCacheSnapshot): ExplorePersistenceWriteResult =
        store.value.replace(snapshot)

    override suspend fun clear(snapshotKey: String, expectedCachedAtEpochMilliseconds: Long): Boolean =
        store.value.clear(snapshotKey, expectedCachedAtEpochMilliseconds)
}

internal class StoredExploreReferenceCache(
    private val store: Lazy<ExploreReferenceStore>,
) : ExploreReferenceCache {
    override suspend fun read(): ExploreReferenceSnapshot? = store.value.read()

    override suspend fun replace(snapshot: ExploreReferenceSnapshot) = store.value.replace(snapshot)
}

internal class StoredExploreFeedPersistenceCache(
    private val store: Lazy<ExploreFeedPersistenceStore>,
) : ExploreFeedPersistenceCache {
    override suspend fun replace(
        wall: ExploreCacheSnapshot,
        references: ExploreReferenceSnapshot,
    ): ExplorePersistenceWriteResult = store.value.replace(wall, references)
}

internal class StoredExplorePersistenceWatermarkProvider(
    private val store: Lazy<ExplorePersistenceWatermarkStore>,
) : ExplorePersistenceWatermarkProvider {
    override suspend fun read(): ExplorePersistenceWatermarkRead = try {
        ExplorePersistenceWatermarkRead.Available(store.value.read())
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: SQLiteException) {
        ExplorePersistenceWatermarkRead.Unavailable
    }
}

internal fun ExploreCacheSnapshot.toDomain(references: ExploreReferenceSnapshot): ExploreFeedSnapshot =
    ExploreFeedSnapshot(
        cities = references.cities,
        categories = references.categories,
        items = items,
        nextCursor = nextCursor,
        cachedAtEpochMilliseconds = minOf(
            cachedAtEpochMilliseconds,
            references.cachedAtEpochMilliseconds,
        ),
        source = ExploreFeedSource.Cache,
        itemContentCapturedAtEpochMilliseconds = items.associate { listing ->
            listing.id to (itemCachedAtEpochMilliseconds[listing.id] ?: cachedAtEpochMilliseconds)
        },
        referencesCapturedAtEpochMilliseconds = references.cachedAtEpochMilliseconds,
        serverSnapshotAtEpochMicroseconds = serverSnapshotAtEpochMicroseconds,
    )

internal fun ExploreFeedSnapshot.toCacheSnapshot(cacheKey: String): ExploreCacheSnapshot = ExploreCacheSnapshot(
    snapshotKey = cacheKey,
    items = items,
    nextCursor = nextCursor,
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    itemCachedAtEpochMilliseconds = itemContentCapturedAtEpochMilliseconds,
    serverSnapshotAtEpochMicroseconds = serverSnapshotAtEpochMicroseconds,
)

internal fun ExploreFeedSnapshot.toReferenceSnapshot(): ExploreReferenceSnapshot = ExploreReferenceSnapshot(
    cities = cities,
    categories = categories,
    cachedAtEpochMilliseconds = referencesCapturedAtEpochMilliseconds,
)
