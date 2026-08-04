package com.kwabor.shared.data.guide

import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.guide.GuideFacet
import com.kwabor.shared.domain.guide.GuideFacetType
import com.kwabor.shared.domain.guide.GuideSummary
import com.kwabor.shared.domain.guide.GuideSummaryPage
import com.kwabor.shared.domain.money.MoneyXof

private const val GUIDE_DISCOVERY_SCHEMA_VERSION = 1
private const val MAXIMUM_IDENTIFIER_CODE_POINTS = 80
private const val MAXIMUM_LABEL_CODE_POINTS = 80
private const val MAXIMUM_NAME_CODE_POINTS = 80
private const val MAXIMUM_ALT_CODE_POINTS = 280
private const val MAXIMUM_NESTED_FACET_COUNT = 20
private const val MAXIMUM_RATING = 5.0
private const val MAXIMUM_CURSOR_UTF8_BYTES = 4_096

internal fun List<GuideFacetRowDto>.toDomainFacets(): List<GuideFacet> {
    val facets = map(GuideFacetRowDto::toDomain)
    if (facets.distinctBy { facet -> facet.type to facet.id }.size != facets.size) {
        invalidGuideDiscoveryValue("facets", "duplicate")
    }
    return facets
}

internal fun GuideFacetRowDto.toDomain(): GuideFacet {
    requireSchemaVersion(schemaVersion)
    return GuideFacet(
        type = facetType.toFacetType(),
        id = facetId.requireCanonicalIdentifier("facet_id"),
        label = label.requireGuideText("label", MAXIMUM_LABEL_CODE_POINTS),
    )
}

internal fun GuideSummaryRowDto.toDomain(): GuideSummary {
    requireSchemaVersion(schemaVersion)
    val mappedRatingCount = ratingCount.requireNonNegativeCount("rating_count")
    val mappedRatingAverage = ratingAverage.requireRating("rating_avg")
    if ((mappedRatingCount == 0) != (mappedRatingAverage == null)) {
        invalidGuideDiscoveryValue("rating", "$mappedRatingAverage/$mappedRatingCount")
    }
    return GuideSummary(
        id = id.requireUuid("id"),
        name = name.requireGuideText("name", MAXIMUM_NAME_CODE_POINTS),
        baseCityId = baseCityId.requireCanonicalIdentifier("base_city_id"),
        baseCityName = baseCityName.requireGuideText("base_city_name", MAXIMUM_LABEL_CODE_POINTS),
        coverImageUrl = coverImageUrl.requireGuideHttpsUrl("cover_image_url"),
        coverImageAlt = coverImageAlt.requireGuideText("cover_image_alt", MAXIMUM_ALT_CODE_POINTS),
        languages = languages.toDomainFacets(GuideFacetType.Language, "languages"),
        coverageCities = coverageCities.toDomainFacets(GuideFacetType.City, "coverage_cities"),
        specialties = specialties.toDomainFacets(GuideFacetType.Specialty, "specialties"),
        indicativePriceXof = indicativePriceXof.requireMoney("indicative_price_xof"),
        ratingAverage = mappedRatingAverage,
        ratingCount = mappedRatingCount,
        verified = verified,
    ).also {
        rowCursor.requireGuideCursor("row_cursor")
    }
}

internal fun GuideSummaryPageDto.toDomain(): GuideSummaryPage = GuideSummaryPage(
    items = items.map(GuideSummaryRowDto::toDomain),
    nextCursor = nextCursor?.requireGuideCursor("next_cursor"),
)

internal fun String.requireGuideCursor(fieldName: String): String {
    if (!isValidGuideCursor()) {
        invalidGuideDiscoveryValue(fieldName, "invalid cursor")
    }
    return this
}

internal fun String.isValidGuideCursor(): Boolean = isNotEmpty() &&
    encodeToByteArray().size <= MAXIMUM_CURSOR_UTF8_BYTES &&
    trim() == this &&
    none(Char::isWhitespace) &&
    none(Char::isISOControl)

internal fun String.requireCanonicalIdentifier(fieldName: String): String {
    requireGuideText(fieldName, MAXIMUM_IDENTIFIER_CODE_POINTS)
    if (!isCanonicalGuideIdentifier()) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    return this
}

internal fun String.isCanonicalGuideIdentifier(): Boolean =
    !hasMoreThanCodePoints(MAXIMUM_IDENTIFIER_CODE_POINTS) && CANONICAL_IDENTIFIER.matches(this)

private fun String.toFacetType(): GuideFacetType = when (this) {
    "city" -> GuideFacetType.City
    "language" -> GuideFacetType.Language
    "specialty" -> GuideFacetType.Specialty
    else -> invalidGuideDiscoveryValue("facet_type", this)
}

private fun List<GuideFacetReferenceDto>.toDomainFacets(type: GuideFacetType, fieldName: String): List<GuideFacet> {
    if (isEmpty() || size > MAXIMUM_NESTED_FACET_COUNT) {
        invalidGuideDiscoveryValue(fieldName, "invalid count")
    }
    val facets = mapIndexed { index, reference ->
        GuideFacet(
            type = type,
            id = reference.id.requireCanonicalIdentifier("$fieldName[$index].id"),
            label = reference.label.requireGuideText("$fieldName[$index].label", MAXIMUM_LABEL_CODE_POINTS),
        )
    }
    if (facets.distinctBy(GuideFacet::id).size != facets.size) {
        invalidGuideDiscoveryValue(fieldName, "duplicate")
    }
    return facets
}

private fun requireSchemaVersion(schemaVersion: Int) {
    if (schemaVersion != GUIDE_DISCOVERY_SCHEMA_VERSION) {
        invalidGuideDiscoveryValue("schema_version", schemaVersion.toString())
    }
}

private fun String.requireUuid(fieldName: String): String {
    if (!isValidUuid()) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    return this
}

private fun String.requireGuideText(fieldName: String, maximumCodePoints: Int): String {
    if (isBlank()) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    if (trim() != this) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    if (any(Char::isISOControl)) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    if (hasMoreThanCodePoints(maximumCodePoints)) {
        invalidGuideDiscoveryValue(fieldName, this)
    }
    return this
}

private fun Double?.requireRating(fieldName: String): Double? {
    if (this != null && (!isFinite() || this !in 0.0..MAXIMUM_RATING)) {
        invalidGuideDiscoveryValue(fieldName, toString())
    }
    return this
}

private fun Int.requireNonNegativeCount(fieldName: String): Int {
    if (this < 0) {
        invalidGuideDiscoveryValue(fieldName, toString())
    }
    return this
}

private fun Long.requireMoney(fieldName: String): MoneyXof = when (val result = MoneyXof.fromAmount(this)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> invalidGuideDiscoveryValue(fieldName, toString())
}

internal fun invalidGuideDiscoveryValue(fieldName: String, value: String, cause: Throwable? = null): Nothing =
    throw GuideDiscoveryDataException.Unexpected(
        IllegalStateException("Invalid guide discovery value for $fieldName: $value", cause),
    )

private val CANONICAL_IDENTIFIER = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
