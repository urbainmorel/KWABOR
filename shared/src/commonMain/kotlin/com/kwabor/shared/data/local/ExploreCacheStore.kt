package com.kwabor.shared.data.local

import com.kwabor.shared.domain.catalog.ListingSummary

internal data class ExploreCacheSnapshot(
    val snapshotKey: String,
    val items: List<ListingSummary>,
    val nextCursor: String?,
    val cachedAtEpochMilliseconds: Long,
)

internal class ExploreCacheStore(
    private val dao: ExploreCacheDao,
    private val maxSnapshotCount: Int = DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS,
) {
    init {
        require(maxSnapshotCount > 0) { "Explore cache snapshot limit must be positive." }
    }

    suspend fun read(snapshotKey: String): ExploreCacheSnapshot? {
        snapshotKey.requireValidSnapshotKey()
        val record = dao.readSnapshot(snapshotKey) ?: return null
        return try {
            record.requireConsistent()
            ExploreCacheSnapshot(
                snapshotKey = record.snapshot.snapshotKey,
                items = record.listings.map(ExploreCachedListingRecord::toDomain),
                nextCursor = record.snapshot.nextCursor,
                cachedAtEpochMilliseconds = record.snapshot.cachedAtEpochMilliseconds,
            )
        } catch (_: CorruptExploreCacheException) {
            dao.clearSnapshot(snapshotKey)
            null
        }
    }

    suspend fun replace(snapshot: ExploreCacheSnapshot) {
        snapshot.requireValid()
        val listings = snapshot.items.map { listing ->
            listing.toExploreCachedListingEntity(
                cachedAtEpochMilliseconds = snapshot.cachedAtEpochMilliseconds,
            )
        }
        val items = snapshot.items.mapIndexed { position, listing ->
            listing.toExploreCacheSnapshotItemEntity(
                snapshotKey = snapshot.snapshotKey,
                position = position,
            )
        }
        dao.replaceSnapshot(
            snapshot = ExploreCacheSnapshotEntity(
                snapshotKey = snapshot.snapshotKey,
                nextCursor = snapshot.nextCursor,
                cachedAtEpochMilliseconds = snapshot.cachedAtEpochMilliseconds,
                itemCount = listings.size,
            ),
            listings = listings,
            items = items,
            maxSnapshotCount = maxSnapshotCount,
        )
    }

    suspend fun clear(snapshotKey: String) {
        snapshotKey.requireValidSnapshotKey()
        dao.clearSnapshot(snapshotKey)
    }
}

private fun ExploreCacheSnapshot.requireValid() {
    snapshotKey.requireValidSnapshotKey()
    require(nextCursor == null || nextCursor.isNotBlank()) { "Explore cache cursor must not be blank." }
    require(nextCursor == null || nextCursor.length <= MAX_EXPLORE_CACHE_CURSOR_LENGTH) {
        "Explore cache cursor is too long."
    }
    require(cachedAtEpochMilliseconds >= 0) { "Explore cache timestamp must not be negative." }
    require(items.size <= MAX_EXPLORE_CACHE_ITEMS) {
        "Explore cache snapshots must contain at most $MAX_EXPLORE_CACHE_ITEMS items."
    }
    items.forEach(ListingSummary::requireValidForCacheWrite)
    require(items.map(ListingSummary::id).distinct().size == items.size) {
        "Explore cache snapshot must not contain duplicate listing ids."
    }
}

private fun String.requireValidSnapshotKey() {
    require(isNotBlank()) { "Explore cache snapshot key must not be blank." }
    require(length <= MAX_EXPLORE_CACHE_SNAPSHOT_KEY_LENGTH) { "Explore cache snapshot key is too long." }
}

private fun ExploreCacheRecord.requireConsistent() {
    val invalidField = invalidPersistedFieldOrNull() ?: return
    throw CorruptExploreCacheException(invalidField)
}

private fun ExploreCacheRecord.invalidPersistedFieldOrNull(): String? = when {
    snapshot.cachedAtEpochMilliseconds < 0 -> "cached_at_epoch_milliseconds"
    snapshot.nextCursor != null && snapshot.nextCursor.isBlank() -> "next_cursor"
    snapshot.nextCursor != null && snapshot.nextCursor.length > MAX_EXPLORE_CACHE_CURSOR_LENGTH -> "next_cursor"
    snapshot.itemCount !in 0..MAX_EXPLORE_CACHE_ITEMS -> "item_count"
    snapshot.itemCount != listings.size -> "item_count"
    listings.map(ExploreCachedListingRecord::position) != listings.indices.toList() -> "position"
    listings.any { record -> record.listing.contentCachedAtEpochMilliseconds < 0 } ->
        "content_cached_at_epoch_milliseconds"
    else -> null
}

private fun ListingSummary.requireValidForCacheWrite() {
    val invalidField = invalidExploreCacheFieldOrNull()
    require(invalidField == null) { "Invalid Explore cache listing field: $invalidField" }
}

internal const val DEFAULT_MAX_EXPLORE_CACHE_SNAPSHOTS = 64
internal const val MAX_EXPLORE_CACHE_ITEMS = 50
internal const val MAX_EXPLORE_CACHE_SNAPSHOT_KEY_LENGTH = 512
internal const val MAX_EXPLORE_CACHE_CURSOR_LENGTH = 4_096
internal const val MAX_EXPLORE_CACHE_ID_LENGTH = 128
internal const val MIN_EXPLORE_CACHE_NAME_LENGTH = 3
internal const val MAX_EXPLORE_CACHE_NAME_LENGTH = 120
internal const val MAX_EXPLORE_CACHE_URL_LENGTH = 2_048
internal const val MIN_EXPLORE_CACHE_RATING = 0.0
internal const val MAX_EXPLORE_CACHE_RATING = 5.0
