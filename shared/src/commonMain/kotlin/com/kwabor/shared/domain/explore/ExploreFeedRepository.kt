package com.kwabor.shared.domain.explore

import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.core.DomainResult

data class ExploreFeedQuery(
    val filters: ListingFilters = ListingFilters(),
    val pageSize: Int = ListingPageRequest.DEFAULT_LIMIT,
) {
    init {
        filters.cityId.requireValidOptionalFilterId("city")
        filters.categoryId.requireValidOptionalFilterId("category")
        require(pageSize in 1..MAX_EXPLORE_FEED_PAGE_SIZE) {
            "Explore page size must be between 1 and $MAX_EXPLORE_FEED_PAGE_SIZE."
        }
    }
}

data class ExploreFeedSnapshot(
    val cities: List<City>,
    val categories: List<Category>,
    val items: List<ListingSummary>,
    val nextCursor: String?,
    val cachedAtEpochMilliseconds: Long,
    val source: ExploreFeedSource,
    val warning: ExploreFeedWarning? = null,
    val itemContentCapturedAtEpochMilliseconds: Map<String, Long> = emptyMap(),
    val referencesCapturedAtEpochMilliseconds: Long = cachedAtEpochMilliseconds,
) {
    init {
        require(nextCursor == null || nextCursor.isNotBlank()) { "Explore feed cursor must not be blank." }
        require(cachedAtEpochMilliseconds >= 0) { "Explore feed timestamp must not be negative." }
        val itemIds = items.map(ListingSummary::id)
        val itemIdSet = itemIds.toSet()
        require(itemIds.distinct().size == items.size) {
            "Explore feed items must not contain duplicate listing ids."
        }
        require(itemContentCapturedAtEpochMilliseconds.keys.all(itemIdSet::contains)) {
            "Explore feed item timestamps must reference visible listing ids."
        }
        require(itemContentCapturedAtEpochMilliseconds.values.all { timestamp -> timestamp >= 0 }) {
            "Explore feed item timestamps must not be negative."
        }
        require(referencesCapturedAtEpochMilliseconds >= 0) {
            "Explore feed reference timestamp must not be negative."
        }
    }
}

enum class ExploreFeedSource {
    Network,
    Cache,
}

sealed interface ExploreFeedWarning {
    data class LocalPersistenceUnavailable(
        val failedOperations: Set<ExploreFeedCacheOperation>,
    ) : ExploreFeedWarning {
        init {
            require(failedOperations.isNotEmpty()) { "At least one failed cache operation is required." }
        }
    }
}

enum class ExploreFeedCacheOperation {
    ReadWatermark,
    WriteWall,
    WriteReferences,
}

interface ExploreFeedRepository {
    suspend fun readCached(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot?>

    suspend fun refresh(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot>

    suspend fun append(
        query: ExploreFeedQuery,
        currentSnapshot: ExploreFeedSnapshot,
    ): DomainResult<ExploreFeedSnapshot>
}

private fun String?.requireValidOptionalFilterId(fieldName: String) {
    require(this == null || isNotBlank()) { "Explore $fieldName filter id must not be blank." }
    require(this == null || length <= MAX_EXPLORE_FEED_FILTER_ID_LENGTH) {
        "Explore $fieldName filter id is too long."
    }
}

const val MAX_EXPLORE_FEED_PAGE_SIZE = 20
private const val MAX_EXPLORE_FEED_FILTER_ID_LENGTH = 128
