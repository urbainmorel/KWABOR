package com.kwabor.shared.data.guide

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GuideFacetRowDto(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("facet_type")
    val facetType: String,
    @SerialName("facet_id")
    val facetId: String,
    @SerialName("label")
    val label: String,
)

@Serializable
internal data class GuideFacetReferenceDto(
    @SerialName("id")
    val id: String,
    @SerialName("label")
    val label: String,
)

@Serializable
internal data class GuideSummaryRowDto(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("base_city_id")
    val baseCityId: String,
    @SerialName("base_city_name")
    val baseCityName: String,
    @SerialName("cover_image_url")
    val coverImageUrl: String,
    @SerialName("cover_image_alt")
    val coverImageAlt: String,
    @SerialName("languages")
    val languages: List<GuideFacetReferenceDto>,
    @SerialName("coverage_cities")
    val coverageCities: List<GuideFacetReferenceDto>,
    @SerialName("specialties")
    val specialties: List<GuideFacetReferenceDto>,
    @SerialName("indicative_price_xof")
    val indicativePriceXof: Long,
    @SerialName("rating_avg")
    val ratingAverage: Double? = null,
    @SerialName("rating_count")
    val ratingCount: Int,
    @SerialName("verified")
    val verified: Boolean,
    @SerialName("row_cursor")
    val rowCursor: String,
)

internal data class GuideSummaryPageDto(
    val items: List<GuideSummaryRowDto>,
    val nextCursor: String?,
)

@Serializable
internal data class GuideServicesRpcParametersDto(
    @SerialName("p_city_id")
    val cityId: String?,
    @SerialName("p_language_id")
    val languageId: String?,
    @SerialName("p_specialty_id")
    val specialtyId: String?,
    @SerialName("p_cursor")
    val cursor: String?,
    @SerialName("p_limit")
    val limit: Int,
)
