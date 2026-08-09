package com.kwabor.shared.domain.explore

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import kotlin.time.Instant

enum class ExploreSort {
    Popularity,
    TemporalProximity,
}

data class ExploreEventWindow(
    val startAtEpochMilliseconds: Long,
    val endExclusiveAtEpochMilliseconds: Long,
) {
    init {
        val mobileTimestampRange =
            MINIMUM_MOBILE_EPOCH_MILLISECONDS until EXCLUSIVE_MAXIMUM_MOBILE_EPOCH_MILLISECONDS
        require(startAtEpochMilliseconds in mobileTimestampRange) {
            "Explore event window start is outside the mobile timestamp range."
        }
        require(endExclusiveAtEpochMilliseconds in mobileTimestampRange) {
            "Explore event window exclusive end is outside the mobile timestamp range."
        }
        require(endExclusiveAtEpochMilliseconds > startAtEpochMilliseconds) {
            "Explore event window exclusive end must be after its start."
        }
    }
}

data class ExploreCatalogRequest(
    val listingType: ListingType,
    val cityId: String? = null,
    val categoryId: String? = null,
    val listingClass: ListingClass? = null,
    val sort: ExploreSort,
    val priceMinXof: Long? = null,
    val priceMaxXof: Long? = null,
    val eventWindow: ExploreEventWindow? = null,
    val cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(cityId.isValidExploreFilterId()) { "Explore city id is invalid." }
        require(categoryId.isValidExploreFilterId()) { "Explore category id is invalid." }
        require(cursor.isValidExploreCursor()) { "Explore cursor is invalid." }
        require(limit in 1..MAX_LIMIT) { "Explore limit must be between 1 and $MAX_LIMIT." }
        require(priceMinXof.isValidExplorePrice()) { "Explore minimum price is invalid." }
        require(priceMaxXof.isValidExplorePrice()) { "Explore maximum price is invalid." }
        require(priceMinXof == null || priceMaxXof == null || priceMinXof <= priceMaxXof) {
            "Explore price range is invalid."
        }
        require(
            (priceMinXof == null && priceMaxXof == null) || listingType == ListingType.Establishment,
        ) { "Explore price filters require establishment listings." }
        require(eventWindow == null || listingType == ListingType.Event) {
            "Explore event window requires event listings."
        }
        require(sort != ExploreSort.TemporalProximity || listingType == ListingType.Event) {
            "Explore temporal proximity sort requires event listings."
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}

data class ExploreCatalogPage(
    val items: List<ListingSummary>,
    val nextCursor: String?,
    val snapshotAtEpochMicroseconds: Long?,
) {
    init {
        require(items.map(ListingSummary::id).distinct().size == items.size) {
            "Explore page must not contain duplicate listing ids."
        }
        require(nextCursor.isValidExploreCursor()) { "Explore next cursor is invalid." }
        if (items.isEmpty()) {
            require(nextCursor == null && snapshotAtEpochMicroseconds == null) {
                "An empty Explore page must be terminal and must not expose a snapshot."
            }
        } else {
            require(snapshotAtEpochMicroseconds != null && snapshotAtEpochMicroseconds >= 0) {
                "A non-empty Explore page requires a non-negative server snapshot."
            }
        }
    }
}

interface ExploreCatalogRepository {
    suspend fun listCatalog(request: ExploreCatalogRequest): DomainResult<ExploreCatalogPage>
}

private fun String?.isValidExploreFilterId(): Boolean = this == null || (
    length in 1..MAX_EXPLORE_FILTER_ID_LENGTH &&
        trim() == this &&
        EXPLORE_FILTER_ID_PATTERN.matches(this)
    )

private fun String?.isValidExploreCursor(): Boolean = this == null || (
    length in 1..MAX_EXPLORE_CURSOR_LENGTH &&
        none(Char::isWhitespace)
    )

private fun Long?.isValidExplorePrice(): Boolean = this == null || this in 0..Int.MAX_VALUE.toLong()

private val EXPLORE_FILTER_ID_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
private val MINIMUM_MOBILE_EPOCH_MILLISECONDS = Instant.parse("0001-01-01T00:00:00Z").toEpochMilliseconds()
private val EXCLUSIVE_MAXIMUM_MOBILE_EPOCH_MILLISECONDS =
    Instant.parse("+10000-01-01T00:00:00Z").toEpochMilliseconds()
private const val MAX_EXPLORE_FILTER_ID_LENGTH = 100
private const val MAX_EXPLORE_CURSOR_LENGTH = 4_096
