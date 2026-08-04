package com.kwabor.shared.data.local

internal class ExploreFeedPersistenceStore(
    private val dao: ExploreFeedPersistenceDao,
    private val maxSnapshotCount: Int = DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS,
) {
    init {
        require(maxSnapshotCount > 0) { "Explore cache snapshot limit must be positive." }
    }

    suspend fun replace(
        wall: ExploreCacheSnapshot,
        references: ExploreReferenceSnapshot,
    ): ExplorePersistenceWriteResult {
        val wallWrite = wall.toCacheWrite()
        val referenceWrite = references.toReferenceWrite()
        return dao.replaceFeed(
            wall = wallWrite,
            references = referenceWrite,
            maxSnapshotCount = maxSnapshotCount,
        )
    }
}
