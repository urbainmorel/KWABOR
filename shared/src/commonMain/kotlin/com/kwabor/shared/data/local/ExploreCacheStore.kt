package com.kwabor.shared.data.local

import com.kwabor.shared.data.explore.isExploreV2FeedCacheKey
import com.kwabor.shared.data.explore.isValidExploreCursorValue
import com.kwabor.shared.domain.catalog.ListingSummary

internal data class ExploreCacheSnapshot(
    val snapshotKey: String,
    val items: List<ListingSummary>,
    val nextCursor: String?,
    val cachedAtEpochMilliseconds: Long,
    val itemCachedAtEpochMilliseconds: Map<String, Long> = emptyMap(),
    val serverSnapshotAtEpochMicroseconds: Long? = null,
)

internal data class ExploreCacheWrite(
    val snapshot: ExploreCacheSnapshotEntity,
    val listings: List<ExploreCachedListingEntity>,
    val items: List<ExploreCacheSnapshotItemEntity>,
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
            val items = record.listings.map(ExploreCachedListingRecord::toDomain)
            if (record.requiresExploreV2Validation) {
                val invalidV2Field = record.invalidExploreV2FieldOrNull(items)
                if (invalidV2Field != null) {
                    throw CorruptExploreCacheException(invalidV2Field)
                }
            }
            ExploreCacheSnapshot(
                snapshotKey = record.snapshot.snapshotKey,
                items = items,
                nextCursor = record.snapshot.nextCursor,
                cachedAtEpochMilliseconds = record.snapshot.cachedAtEpochMilliseconds,
                itemCachedAtEpochMilliseconds = record.listings
                    .filter { listing ->
                        listing.listing.contentCachedAtEpochMilliseconds !=
                            record.snapshot.cachedAtEpochMilliseconds
                    }
                    .associate { listing ->
                        listing.listing.listingId to listing.listing.contentCachedAtEpochMilliseconds
                    },
                serverSnapshotAtEpochMicroseconds = record.snapshot.serverSnapshotAtEpochMicroseconds,
            )
        } catch (_: CorruptExploreCacheException) {
            dao.clearSnapshotIfTimestampMatches(
                snapshotKey = snapshotKey,
                expectedCachedAtEpochMilliseconds = record.snapshot.cachedAtEpochMilliseconds,
            )
            null
        }
    }

    suspend fun replace(snapshot: ExploreCacheSnapshot): ExplorePersistenceWriteResult {
        val write = snapshot.toCacheWrite()
        return dao.replaceSnapshot(
            snapshot = write.snapshot,
            listings = write.listings,
            items = write.items,
            maxSnapshotCount = maxSnapshotCount,
        )
    }

    suspend fun clear(snapshotKey: String) {
        snapshotKey.requireValidSnapshotKey()
        dao.clearSnapshot(snapshotKey)
    }

    suspend fun clear(snapshotKey: String, expectedCachedAtEpochMilliseconds: Long): Boolean {
        snapshotKey.requireValidSnapshotKey()
        return dao.clearSnapshotIfTimestampMatches(
            snapshotKey = snapshotKey,
            expectedCachedAtEpochMilliseconds = expectedCachedAtEpochMilliseconds,
        )
    }
}

private fun ExploreCacheRecord.invalidExploreV2FieldOrNull(items: List<ListingSummary>): String? = when {
    snapshot.snapshotKey.isExploreV2FeedCacheKey() &&
        (items.isEmpty() != (snapshot.serverSnapshotAtEpochMicroseconds == null)) ->
        "server_snapshot_at_epoch_microseconds"
    items.isEmpty() && snapshot.nextCursor != null -> "next_cursor"
    snapshot.nextCursor != null && !snapshot.nextCursor.isValidExploreCursorValue() -> "next_cursor"
    else -> items.firstNotNullOfOrNull { listing -> listing.invalidExploreV2CacheFieldOrNull }
        ?: items.invalidExploreV2SponsorPlacementFieldOrNull()
}

private val ExploreCacheRecord.requiresExploreV2Validation: Boolean
    get() = snapshot.snapshotKey.isExploreV2FeedCacheKey() ||
        snapshot.serverSnapshotAtEpochMicroseconds != null

internal fun ExploreCacheSnapshot.toCacheWrite(): ExploreCacheWrite {
    requireValid()
    val listings = items.map { listing ->
        listing.toExploreCachedListingEntity(
            cachedAtEpochMilliseconds = itemCachedAtEpochMilliseconds[listing.id]
                ?: cachedAtEpochMilliseconds,
        )
    }
    return ExploreCacheWrite(
        snapshot = ExploreCacheSnapshotEntity(
            snapshotKey = snapshotKey,
            nextCursor = nextCursor,
            serverSnapshotAtEpochMicroseconds = serverSnapshotAtEpochMicroseconds,
            cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
            itemCount = listings.size,
        ),
        listings = listings,
        items = items.mapIndexed { position, listing ->
            listing.toExploreCacheSnapshotItemEntity(
                snapshotKey = snapshotKey,
                position = position,
            )
        },
    )
}

private fun ExploreCacheSnapshot.requireValid() {
    snapshotKey.requireValidSnapshotKey()
    require(nextCursor == null || nextCursor.isNotBlank()) { "Explore cache cursor must not be blank." }
    require(nextCursor == null || nextCursor.length <= MAX_EXPLORE_CACHE_CURSOR_LENGTH) {
        "Explore cache cursor is too long."
    }
    require(cachedAtEpochMilliseconds >= 0) { "Explore cache timestamp must not be negative." }
    require(serverSnapshotAtEpochMicroseconds == null || serverSnapshotAtEpochMicroseconds >= 0) {
        "Explore cache server snapshot timestamp must not be negative."
    }
    val itemIds = items.mapTo(mutableSetOf(), ListingSummary::id)
    require(itemCachedAtEpochMilliseconds.keys.all(itemIds::contains)) {
        "Explore cache item timestamps must reference snapshot listing ids."
    }
    require(itemCachedAtEpochMilliseconds.values.all { timestamp -> timestamp >= 0 }) {
        "Explore cache item timestamps must not be negative."
    }
    require(items.size <= MAX_EXPLORE_CACHE_ITEMS) {
        "Explore cache snapshots must contain at most $MAX_EXPLORE_CACHE_ITEMS items."
    }
    items.forEach(ListingSummary::requireValidForCacheWrite)
    requireValidV2IfApplicable()
    require(items.map(ListingSummary::id).distinct().size == items.size) {
        "Explore cache snapshot must not contain duplicate listing ids."
    }
}

private fun ExploreCacheSnapshot.requireValidV2IfApplicable() {
    val isV2Key = snapshotKey.isExploreV2FeedCacheKey()
    if (!isV2Key && serverSnapshotAtEpochMicroseconds == null) {
        return
    }
    require(!isV2Key || (items.isEmpty() == (serverSnapshotAtEpochMicroseconds == null))) {
        "Invalid Explore v2 cache snapshot field: server_snapshot_at_epoch_microseconds"
    }
    require(items.isNotEmpty() || nextCursor == null) {
        "Invalid Explore v2 cache snapshot field: next_cursor"
    }
    require(nextCursor == null || nextCursor.isValidExploreCursorValue()) {
        "Invalid Explore v2 cache snapshot field: next_cursor"
    }
    items.forEach(ListingSummary::requireValidForV2CacheWrite)
    val invalidSponsorPlacement = items.invalidExploreV2SponsorPlacementFieldOrNull()
    require(invalidSponsorPlacement == null) {
        "Invalid Explore v2 cache snapshot field: $invalidSponsorPlacement"
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

private fun ExploreCacheRecord.invalidPersistedFieldOrNull(): String? =
    snapshot.invalidPersistedFieldOrNull ?: listings.invalidPersistedFieldOrNull(snapshot.itemCount)

private val ExploreCacheSnapshotEntity.invalidPersistedFieldOrNull: String?
    get() = when {
        cachedAtEpochMilliseconds < 0 -> "cached_at_epoch_milliseconds"
        serverSnapshotAtEpochMicroseconds != null && serverSnapshotAtEpochMicroseconds < 0 ->
            "server_snapshot_at_epoch_microseconds"
        nextCursor != null && nextCursor.isBlank() -> "next_cursor"
        nextCursor != null && nextCursor.length > MAX_EXPLORE_CACHE_CURSOR_LENGTH -> "next_cursor"
        itemCount !in 0..MAX_EXPLORE_CACHE_ITEMS -> "item_count"
        else -> null
    }

private fun List<ExploreCachedListingRecord>.invalidPersistedFieldOrNull(expectedItemCount: Int): String? = when {
    expectedItemCount != size -> "item_count"
    map(ExploreCachedListingRecord::position) != indices.toList() -> "position"
    any { record -> record.listing.contentCachedAtEpochMilliseconds < 0 } ->
        "content_cached_at_epoch_milliseconds"
    else -> null
}

private fun ListingSummary.requireValidForCacheWrite() {
    val invalidField = invalidExploreCacheFieldOrNull()
    require(invalidField == null) { "Invalid Explore cache listing field: $invalidField" }
}

private fun ListingSummary.requireValidForV2CacheWrite() {
    val invalidField = invalidExploreV2CacheFieldOrNull
    require(invalidField == null) { "Invalid Explore v2 cache listing field: $invalidField" }
}

private fun List<ListingSummary>.invalidExploreV2SponsorPlacementFieldOrNull(): String? {
    val sponsoredCount = count { listing -> listing.isSponsoredPlacement == true }
    if (sponsoredCount > MAX_EXPLORE_V2_SPONSORED_PLACEMENTS) {
        return "is_sponsored_placement"
    }
    val firstOrganicIndex = indexOfFirst { listing -> listing.isSponsoredPlacement == false }
    val hasSponsoredAfterOrganic = firstOrganicIndex >= 0 &&
        drop(firstOrganicIndex + 1).any { listing -> listing.isSponsoredPlacement == true }
    return if (hasSponsoredAfterOrganic) "is_sponsored_placement" else null
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
private const val MAX_EXPLORE_V2_SPONSORED_PLACEMENTS = 2
