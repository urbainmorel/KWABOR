package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.explore.ExploreFeedQuery

internal fun ExploreFeedQuery.toCacheKey(): String = toVersionedCacheKey(EXPLORE_FEED_CACHE_KEY_VERSION)

internal fun ExploreFeedQuery.toLegacyCacheKey(): String = toVersionedCacheKey(LEGACY_EXPLORE_FEED_CACHE_KEY_VERSION)

internal fun String.isExploreV2FeedCacheKey(): Boolean = startsWith("$EXPLORE_FEED_CACHE_KEY_VERSION|")

private fun ExploreFeedQuery.toVersionedCacheKey(version: String): String = buildString {
    append(version)
    append("|city=")
    append(filters.cityId.toLengthPrefixedValue())
    append("|category=")
    append(filters.categoryId.toLengthPrefixedValue())
    append("|type=")
    append(filters.listingType.toCacheKeyValue())
    append("|class=")
    append(filters.listingClass.toCacheKeyValue())
    append("|published=")
    append(if (filters.onlyPublished) "1" else "0")
    append("|pageSize=")
    append(pageSize)
}

private fun String?.toLengthPrefixedValue(): String = when (this) {
    null -> "n"
    else -> "v$length:$this"
}

private fun ListingType?.toCacheKeyValue(): String = when (this) {
    null -> "n"
    ListingType.Place -> "place"
    ListingType.Establishment -> "establishment"
    ListingType.Event -> "event"
}

private fun ListingClass?.toCacheKeyValue(): String = when (this) {
    null -> "n"
    ListingClass.Heritage -> "heritage"
    ListingClass.Commercial -> "commercial"
    ListingClass.Event -> "event"
}

private const val EXPLORE_FEED_CACHE_KEY_VERSION = "explore-feed:v2"
private const val LEGACY_EXPLORE_FEED_CACHE_KEY_VERSION = "explore-feed:v1"
