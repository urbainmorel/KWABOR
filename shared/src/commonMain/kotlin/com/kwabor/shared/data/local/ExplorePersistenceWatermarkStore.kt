package com.kwabor.shared.data.local

import androidx.room.Dao
import androidx.room.Query

@Dao
internal interface ExplorePersistenceWatermarkDao {
    @Query(
        """
        SELECT MAX(persisted_timestamp)
        FROM (
            SELECT cached_at_epoch_milliseconds AS persisted_timestamp
            FROM explore_cache_snapshots
            UNION ALL
            SELECT cached_at_epoch_milliseconds AS persisted_timestamp
            FROM explore_reference_snapshots
            UNION ALL
            SELECT content_cached_at_epoch_milliseconds AS persisted_timestamp
            FROM explore_cached_listings
        )
        """,
    )
    suspend fun findWatermark(): Long?
}

internal class ExplorePersistenceWatermarkStore(
    private val dao: ExplorePersistenceWatermarkDao,
) {
    suspend fun read(): Long? = dao.findWatermark()
}

internal enum class ExplorePersistenceWriteResult {
    Applied,
    Rejected,
}
