package com.kwabor.shared.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "explore_cache_snapshots",
    indices = [Index(value = ["cached_at_epoch_milliseconds"])],
)
internal data class ExploreCacheSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "snapshot_key")
    val snapshotKey: String,
    @ColumnInfo(name = "next_cursor")
    val nextCursor: String?,
    @ColumnInfo(name = "server_snapshot_at_epoch_microseconds")
    val serverSnapshotAtEpochMicroseconds: Long? = null,
    @ColumnInfo(name = "cached_at_epoch_milliseconds")
    val cachedAtEpochMilliseconds: Long,
    @ColumnInfo(name = "item_count")
    val itemCount: Int,
)

@Entity(tableName = "explore_cached_listings")
internal data class ExploreCachedListingEntity(
    @PrimaryKey
    @ColumnInfo(name = "listing_id")
    val listingId: String,
    @ColumnInfo(name = "listing_type")
    val listingType: String,
    @ColumnInfo(name = "listing_class")
    val listingClass: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "city_id")
    val cityId: String,
    @ColumnInfo(name = "category_id")
    val categoryId: String,
    @ColumnInfo(name = "cover_image_url")
    val coverImageUrl: String?,
    @ColumnInfo(name = "cover_image_alt")
    val coverImageAlt: String?,
    @ColumnInfo(name = "price_from_xof")
    val priceFromXof: Long?,
    @ColumnInfo(name = "rating_average")
    val ratingAverage: Double?,
    @ColumnInfo(name = "likes_count")
    val likesCount: Int,
    @ColumnInfo(name = "views_count")
    val viewsCount: Long?,
    @ColumnInfo(name = "verified")
    val verified: Boolean,
    @ColumnInfo(name = "sponsored_until_epoch_milliseconds")
    val sponsoredUntilEpochMilliseconds: Long?,
    @ColumnInfo(name = "event_start_at_epoch_milliseconds")
    val eventStartAtEpochMilliseconds: Long?,
    @ColumnInfo(name = "event_end_at_epoch_milliseconds")
    val eventEndAtEpochMilliseconds: Long?,
    @ColumnInfo(name = "content_cached_at_epoch_milliseconds")
    val contentCachedAtEpochMilliseconds: Long,
)

@Entity(
    tableName = "explore_cache_snapshot_items",
    primaryKeys = ["snapshot_key", "listing_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExploreCacheSnapshotEntity::class,
            parentColumns = ["snapshot_key"],
            childColumns = ["snapshot_key"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExploreCachedListingEntity::class,
            parentColumns = ["listing_id"],
            childColumns = ["listing_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["listing_id"]),
        Index(
            value = ["snapshot_key", "position"],
            unique = true,
        ),
    ],
)
internal data class ExploreCacheSnapshotItemEntity(
    @ColumnInfo(name = "snapshot_key")
    val snapshotKey: String,
    @ColumnInfo(name = "listing_id")
    val listingId: String,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "is_sponsored_placement")
    val isSponsoredPlacement: Boolean?,
    @ColumnInfo(name = "is_event_ended")
    val isEventEnded: Boolean? = null,
)

@Entity(tableName = "explore_reference_snapshots")
internal data class ExploreReferenceSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "snapshot_key")
    val snapshotKey: String,
    @ColumnInfo(name = "cached_at_epoch_milliseconds")
    val cachedAtEpochMilliseconds: Long,
    @ColumnInfo(name = "city_count")
    val cityCount: Int,
    @ColumnInfo(name = "category_count")
    val categoryCount: Int,
)

@Entity(
    tableName = "explore_reference_cities",
    primaryKeys = ["snapshot_key", "city_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExploreReferenceSnapshotEntity::class,
            parentColumns = ["snapshot_key"],
            childColumns = ["snapshot_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["snapshot_key", "position"],
            unique = true,
        ),
    ],
)
internal data class ExploreReferenceCityEntity(
    @ColumnInfo(name = "snapshot_key")
    val snapshotKey: String,
    @ColumnInfo(name = "city_id")
    val cityId: String,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "country_code")
    val countryCode: String,
    @ColumnInfo(name = "latitude")
    val latitude: Double?,
    @ColumnInfo(name = "longitude")
    val longitude: Double?,
)

@Entity(
    tableName = "explore_reference_categories",
    primaryKeys = ["snapshot_key", "category_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExploreReferenceSnapshotEntity::class,
            parentColumns = ["snapshot_key"],
            childColumns = ["snapshot_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["snapshot_key", "position"],
            unique = true,
        ),
    ],
)
internal data class ExploreReferenceCategoryEntity(
    @ColumnInfo(name = "snapshot_key")
    val snapshotKey: String,
    @ColumnInfo(name = "category_id")
    val categoryId: String,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "name_key")
    val nameKey: String,
    @ColumnInfo(name = "listing_type")
    val listingType: String,
    @ColumnInfo(name = "default_listing_class")
    val defaultListingClass: String,
)
