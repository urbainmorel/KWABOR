package com.kwabor.shared.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

internal data class ExploreCacheRecord(
    val snapshot: ExploreCacheSnapshotEntity,
    val listings: List<ExploreCachedListingRecord>,
)

internal data class ExploreCachedListingRecord(
    @Embedded
    val listing: ExploreCachedListingEntity,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "is_sponsored_placement")
    val isSponsoredPlacement: Boolean?,
)

internal data class ExploreCachedListingTimestamp(
    @ColumnInfo(name = "listing_id")
    val listingId: String,
    @ColumnInfo(name = "content_cached_at_epoch_milliseconds")
    val contentCachedAtEpochMilliseconds: Long,
)

internal interface ExploreCacheStatements {
    @Query(
        """
        SELECT *
        FROM explore_cache_snapshots
        WHERE snapshot_key = :snapshotKey
        """,
    )
    suspend fun findSnapshot(snapshotKey: String): ExploreCacheSnapshotEntity?

    @Query(
        """
        SELECT listings.*, items.position, items.is_sponsored_placement
        FROM explore_cache_snapshot_items AS items
        INNER JOIN explore_cached_listings AS listings
            ON listings.listing_id = items.listing_id
        WHERE items.snapshot_key = :snapshotKey
        ORDER BY items.position ASC
        """,
    )
    suspend fun findListings(snapshotKey: String): List<ExploreCachedListingRecord>

    @Query(
        """
        SELECT listing_id, content_cached_at_epoch_milliseconds
        FROM explore_cached_listings
        WHERE listing_id IN (:listingIds)
        """,
    )
    suspend fun findListingTimestamps(listingIds: List<String>): List<ExploreCachedListingTimestamp>

    @Upsert
    suspend fun upsertSnapshot(snapshot: ExploreCacheSnapshotEntity)

    @Upsert
    suspend fun upsertListings(listings: List<ExploreCachedListingEntity>)

    @Insert
    suspend fun insertSnapshotItems(items: List<ExploreCacheSnapshotItemEntity>)

    @Query("DELETE FROM explore_cache_snapshot_items WHERE snapshot_key = :snapshotKey")
    suspend fun deleteSnapshotItems(snapshotKey: String)

    @Query("DELETE FROM explore_cache_snapshots WHERE snapshot_key = :snapshotKey")
    suspend fun deleteSnapshot(snapshotKey: String)

    @Query(
        """
        DELETE FROM explore_cache_snapshots
        WHERE snapshot_key NOT IN (
            SELECT snapshot_key
            FROM explore_cache_snapshots
            ORDER BY cached_at_epoch_milliseconds DESC, snapshot_key ASC
            LIMIT :maxSnapshotCount
        )
        """,
    )
    suspend fun deleteSnapshotsBeyondLimit(maxSnapshotCount: Int)

    @Query(
        """
        DELETE FROM explore_cached_listings
        WHERE listing_id NOT IN (
            SELECT listing_id
            FROM explore_cache_snapshot_items
        )
        """,
    )
    suspend fun deleteOrphanListings()
}

@Dao
internal interface ExploreCacheDao : ExploreCacheStatements {
    @Transaction
    suspend fun readSnapshot(snapshotKey: String): ExploreCacheRecord? {
        val snapshot = findSnapshot(snapshotKey) ?: return null
        return ExploreCacheRecord(
            snapshot = snapshot,
            listings = findListings(snapshotKey),
        )
    }

    @Transaction
    suspend fun replaceSnapshot(
        snapshot: ExploreCacheSnapshotEntity,
        listings: List<ExploreCachedListingEntity>,
        items: List<ExploreCacheSnapshotItemEntity>,
        maxSnapshotCount: Int,
    ) {
        require(maxSnapshotCount > 0) { "Explore cache snapshot limit must be positive." }
        val timestampsByListingId = if (listings.isEmpty()) {
            emptyMap()
        } else {
            findListingTimestamps(listings.map { listing -> listing.listingId })
                .associate { cached -> cached.listingId to cached.contentCachedAtEpochMilliseconds }
        }
        val listingsToUpdate = listings.filter { listing ->
            val currentTimestamp = timestampsByListingId[listing.listingId]
            currentTimestamp == null || listing.contentCachedAtEpochMilliseconds > currentTimestamp
        }

        deleteSnapshotItems(snapshot.snapshotKey)
        upsertSnapshot(snapshot)
        if (listingsToUpdate.isNotEmpty()) {
            upsertListings(listingsToUpdate)
        }
        if (items.isNotEmpty()) {
            insertSnapshotItems(items)
        }
        deleteSnapshotsBeyondLimit(maxSnapshotCount)
        deleteOrphanListings()
    }

    @Transaction
    suspend fun clearSnapshot(snapshotKey: String) {
        deleteSnapshot(snapshotKey)
        deleteOrphanListings()
    }
}
