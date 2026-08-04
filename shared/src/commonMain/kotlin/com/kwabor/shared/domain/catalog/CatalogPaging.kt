package com.kwabor.shared.domain.catalog

data class ListingPageRequest(
    val cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(cursor == null || cursor.isNotBlank()) { "Listing page cursor must not be blank." }
        require(limit in 1..MAX_LIMIT) { "Listing page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}

data class ListingSummaryPage(
    val items: List<ListingSummary>,
    val nextCursor: String?,
)
