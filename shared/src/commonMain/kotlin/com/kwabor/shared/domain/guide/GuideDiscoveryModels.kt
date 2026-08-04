package com.kwabor.shared.domain.guide

import com.kwabor.shared.domain.money.MoneyXof

enum class GuideFacetType {
    City,
    Language,
    Specialty,
}

data class GuideFacet(
    val type: GuideFacetType,
    val id: String,
    val label: String,
)

data class GuideDiscoveryFilters(
    val cityId: String? = null,
    val languageId: String? = null,
    val specialtyId: String? = null,
)

data class GuidePageRequest(
    val cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(cursor == null || cursor.isNotBlank()) { "Guide page cursor must not be blank." }
        require(limit in 1..MAX_LIMIT) { "Guide page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}

data class GuideSummary(
    val id: String,
    val name: String,
    val baseCityId: String,
    val baseCityName: String,
    val coverImageUrl: String,
    val coverImageAlt: String,
    val languages: List<GuideFacet>,
    val coverageCities: List<GuideFacet>,
    val specialties: List<GuideFacet>,
    val indicativePriceXof: MoneyXof,
    val ratingAverage: Double?,
    val ratingCount: Int,
    val verified: Boolean,
)

data class GuideSummaryPage(
    val items: List<GuideSummary>,
    val nextCursor: String?,
)
