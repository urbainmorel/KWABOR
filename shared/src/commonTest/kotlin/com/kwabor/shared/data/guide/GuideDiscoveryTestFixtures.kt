package com.kwabor.shared.data.guide

internal const val GUIDE_ID_ONE = "11111111-1111-4111-8111-111111111111"
internal const val GUIDE_ID_TWO = "22222222-2222-4222-8222-222222222222"
internal const val GUIDE_ID_THREE = "33333333-3333-4333-8333-333333333333"

internal fun validGuideFacetRow(
    type: String = "city",
    id: String = "cotonou",
    label: String = "Cotonou",
): GuideFacetRowDto = GuideFacetRowDto(
    schemaVersion = 1,
    facetType = type,
    facetId = id,
    label = label,
)

internal fun validGuideSummaryRow(id: String = GUIDE_ID_ONE, cursor: String = "cursor-$id"): GuideSummaryRowDto =
    GuideSummaryRowDto(
        schemaVersion = 1,
        id = id,
        name = "Guide Kwabor",
        baseCityId = "cotonou",
        baseCityName = "Cotonou",
        coverImageUrl = "https://cdn.kwabor.test/guides/$id.jpg",
        coverImageAlt = "Portrait du guide Kwabor",
        languages = listOf(GuideFacetReferenceDto(id = "francais", label = "Français")),
        coverageCities = listOf(GuideFacetReferenceDto(id = "ouidah", label = "Ouidah")),
        specialties = listOf(GuideFacetReferenceDto(id = "histoire", label = "Histoire")),
        indicativePriceXof = 15_000,
        ratingAverage = 4.75,
        ratingCount = 12,
        verified = true,
        rowCursor = cursor,
    )
