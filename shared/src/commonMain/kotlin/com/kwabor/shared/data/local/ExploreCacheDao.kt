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
    @ColumnInfo(name = "is_event_ended")
    val isEventEnded: Boolean? = null,
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
        SELECT listings.*, items.position, items.is_sponsored_placement, items.is_event_ended
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

    @Query(
        """
        DELETE FROM explore_cache_snapshots
        WHERE snapshot_key = :snapshotKey
            AND cached_at_epoch_milliseconds = :expectedCachedAtEpochMilliseconds
        """,
    )
    suspend fun deleteSnapshotIfTimestampMatches(snapshotKey: String, expectedCachedAtEpochMilliseconds: Long): Int

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
    ): ExplorePersistenceWriteResult = replaceSnapshotIfNewer(snapshot, listings, items, maxSnapshotCount)

    @Transaction
    suspend fun clearSnapshot(snapshotKey: String) {
        val expectedCachedAtEpochMilliseconds = findSnapshot(snapshotKey)?.cachedAtEpochMilliseconds ?: return
        clearSnapshotRowsIfTimestampMatches(snapshotKey, expectedCachedAtEpochMilliseconds)
    }

    @Transaction
    suspend fun clearSnapshotIfTimestampMatches(
        snapshotKey: String,
        expectedCachedAtEpochMilliseconds: Long,
    ): Boolean = clearSnapshotRowsIfTimestampMatches(snapshotKey, expectedCachedAtEpochMilliseconds)
}

private suspend fun ExploreCacheStatements.clearSnapshotRowsIfTimestampMatches(
    snapshotKey: String,
    expectedCachedAtEpochMilliseconds: Long,
): Boolean {
    val wasDeleted = deleteSnapshotIfTimestampMatches(
        snapshotKey = snapshotKey,
        expectedCachedAtEpochMilliseconds = expectedCachedAtEpochMilliseconds,
    ) > 0
    if (wasDeleted) {
        deleteOrphanListings()
    }
    return wasDeleted
}

internal data class ExploreReferenceRecord(
    val snapshot: ExploreReferenceSnapshotEntity,
    val cities: List<ExploreReferenceCityEntity>,
    val categories: List<ExploreReferenceCategoryEntity>,
)

internal interface ExploreReferenceStatements {
    @Query(
        """
        SELECT *
        FROM explore_reference_snapshots
        WHERE snapshot_key = :snapshotKey
        """,
    )
    suspend fun findReferenceSnapshot(snapshotKey: String): ExploreReferenceSnapshotEntity?

    @Query(
        """
        SELECT *
        FROM explore_reference_cities
        WHERE snapshot_key = :snapshotKey
        ORDER BY position ASC
        """,
    )
    suspend fun findReferenceCities(snapshotKey: String): List<ExploreReferenceCityEntity>

    @Query(
        """
        SELECT *
        FROM explore_reference_categories
        WHERE snapshot_key = :snapshotKey
        ORDER BY position ASC
        """,
    )
    suspend fun findReferenceCategories(snapshotKey: String): List<ExploreReferenceCategoryEntity>

    @Insert
    suspend fun insertReferenceSnapshot(snapshot: ExploreReferenceSnapshotEntity)

    @Insert
    suspend fun insertReferenceCities(cities: List<ExploreReferenceCityEntity>)

    @Insert
    suspend fun insertReferenceCategories(categories: List<ExploreReferenceCategoryEntity>)

    @Query("DELETE FROM explore_reference_snapshots WHERE snapshot_key = :snapshotKey")
    suspend fun deleteReferenceSnapshot(snapshotKey: String)

    @Query(
        """
        DELETE FROM explore_reference_snapshots
        WHERE snapshot_key = :snapshotKey
            AND cached_at_epoch_milliseconds = :expectedCachedAtEpochMilliseconds
        """,
    )
    suspend fun deleteReferenceSnapshotIfTimestampMatches(
        snapshotKey: String,
        expectedCachedAtEpochMilliseconds: Long,
    ): Int
}

@Dao
internal interface ExploreReferenceDao : ExploreReferenceStatements {
    @Transaction
    suspend fun readReference(snapshotKey: String): ExploreReferenceRecord? {
        val snapshot = findReferenceSnapshot(snapshotKey) ?: return null
        return ExploreReferenceRecord(
            snapshot = snapshot,
            cities = findReferenceCities(snapshotKey),
            categories = findReferenceCategories(snapshotKey),
        )
    }

    @Transaction
    suspend fun replaceReference(
        snapshot: ExploreReferenceSnapshotEntity,
        cities: List<ExploreReferenceCityEntity>,
        categories: List<ExploreReferenceCategoryEntity>,
    ) = replaceReferenceIfNewer(snapshot, cities, categories)

    @Transaction
    suspend fun clearReference(snapshotKey: String) {
        deleteReferenceSnapshot(snapshotKey)
    }

    @Transaction
    suspend fun clearReferenceIfTimestampMatches(
        snapshotKey: String,
        expectedCachedAtEpochMilliseconds: Long,
    ): Boolean = deleteReferenceSnapshotIfTimestampMatches(
        snapshotKey = snapshotKey,
        expectedCachedAtEpochMilliseconds = expectedCachedAtEpochMilliseconds,
    ) > 0
}

@Dao
internal interface ExploreFeedPersistenceDao : ExploreCacheStatements, ExploreReferenceStatements {
    @Transaction
    suspend fun replaceFeed(
        wall: ExploreCacheWrite,
        references: ExploreReferenceWrite,
        maxSnapshotCount: Int,
    ): ExplorePersistenceWriteResult {
        val wallResult = replaceSnapshotIfNewer(wall.snapshot, wall.listings, wall.items, maxSnapshotCount)
        val referenceResult = replaceReferenceIfNewer(
            references.snapshot,
            references.cities,
            references.categories,
        )
        return if (
            wallResult == ExplorePersistenceWriteResult.Applied ||
            referenceResult == ExplorePersistenceWriteResult.Applied
        ) {
            ExplorePersistenceWriteResult.Applied
        } else {
            ExplorePersistenceWriteResult.Rejected
        }
    }
}

private suspend fun ExploreCacheStatements.replaceSnapshotIfNewer(
    snapshot: ExploreCacheSnapshotEntity,
    listings: List<ExploreCachedListingEntity>,
    items: List<ExploreCacheSnapshotItemEntity>,
    maxSnapshotCount: Int,
): ExplorePersistenceWriteResult {
    require(maxSnapshotCount > 0) { "Explore cache snapshot limit must be positive." }
    val currentSnapshot = findSnapshot(snapshot.snapshotKey)
    if (currentSnapshot != null && snapshot.cachedAtEpochMilliseconds <= currentSnapshot.cachedAtEpochMilliseconds) {
        return ExplorePersistenceWriteResult.Rejected
    }
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
    return ExplorePersistenceWriteResult.Applied
}

private suspend fun ExploreReferenceStatements.replaceReferenceIfNewer(
    snapshot: ExploreReferenceSnapshotEntity,
    cities: List<ExploreReferenceCityEntity>,
    categories: List<ExploreReferenceCategoryEntity>,
): ExplorePersistenceWriteResult {
    val currentSnapshot = findReferenceSnapshot(snapshot.snapshotKey)
    if (currentSnapshot != null && snapshot.cachedAtEpochMilliseconds <= currentSnapshot.cachedAtEpochMilliseconds) {
        return ExplorePersistenceWriteResult.Rejected
    }
    deleteReferenceSnapshot(snapshot.snapshotKey)
    insertReferenceSnapshot(snapshot)
    if (cities.isNotEmpty()) {
        insertReferenceCities(cities)
    }
    if (categories.isNotEmpty()) {
        insertReferenceCategories(categories)
    }
    return ExplorePersistenceWriteResult.Applied
}
