package com.kwabor.shared.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query

internal data class SearchCacheCandidateRecord(
    @Embedded
    val listing: ExploreCachedListingEntity,
    @ColumnInfo(name = "city_name")
    val cityName: String?,
    @ColumnInfo(name = "category_name_key")
    val categoryNameKey: String?,
)

@Dao
internal interface SearchCacheDao {
    @Query(
        """
        SELECT listings.*, cities.name AS city_name, categories.name_key AS category_name_key
        FROM explore_cached_listings AS listings
        LEFT JOIN explore_reference_cities AS cities
            ON cities.snapshot_key = 'explore'
            AND cities.city_id = listings.city_id
        LEFT JOIN explore_reference_categories AS categories
            ON categories.snapshot_key = 'explore'
            AND categories.category_id = listings.category_id
        WHERE listings.status = 'published'
            AND (:cityId IS NULL OR listings.city_id = :cityId)
            AND (:categoryId IS NULL OR listings.category_id = :categoryId)
            AND (:listingType IS NULL OR listings.listing_type = :listingType)
            AND (:listingClass IS NULL OR listings.listing_class = :listingClass)
        ORDER BY listings.name COLLATE NOCASE ASC, listings.listing_id ASC
        LIMIT :candidateLimit
        """,
    )
    suspend fun findCandidates(
        cityId: String?,
        categoryId: String?,
        listingType: String?,
        listingClass: String?,
        candidateLimit: Int,
    ): List<SearchCacheCandidateRecord>
}
