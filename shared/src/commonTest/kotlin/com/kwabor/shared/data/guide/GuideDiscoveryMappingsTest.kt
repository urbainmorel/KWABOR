package com.kwabor.shared.data.guide

import com.kwabor.shared.domain.guide.GuideFacetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GuideDiscoveryMappingsTest {
    @Test
    fun facetRows_mapAllPublicFacetTypes() {
        val facets = listOf(
            validGuideFacetRow(type = "city", id = "cotonou", label = "Cotonou"),
            validGuideFacetRow(type = "language", id = "francais", label = "Français"),
            validGuideFacetRow(type = "specialty", id = "histoire", label = "Histoire"),
        ).toDomainFacets()

        assertEquals(
            listOf(GuideFacetType.City, GuideFacetType.Language, GuideFacetType.Specialty),
            facets.map { facet -> facet.type },
        )
    }

    @Test
    fun facetRows_rejectUnknownSchemaTypeAndDuplicates() {
        val invalidRows = listOf(
            listOf(validGuideFacetRow().copy(schemaVersion = 2)),
            listOf(validGuideFacetRow().copy(facetType = "unsupported")),
            listOf(validGuideFacetRow(), validGuideFacetRow()),
        )

        invalidRows.forEach { rows ->
            assertFailsWith<GuideDiscoveryDataException.Unexpected> { rows.toDomainFacets() }
        }
    }

    @Test
    fun summaryRow_mapsTheStrictDomainContractAndInjectsFacetTypes() {
        val summary = validGuideSummaryRow().toDomain()

        assertEquals(GUIDE_ID_ONE, summary.id)
        assertEquals(15_000, summary.indicativePriceXof.amount)
        assertEquals(GuideFacetType.Language, summary.languages.single().type)
        assertEquals(GuideFacetType.City, summary.coverageCities.single().type)
        assertEquals(GuideFacetType.Specialty, summary.specialties.single().type)
        assertEquals(4.75, summary.ratingAverage)
        assertEquals(12, summary.ratingCount)
    }

    @Test
    fun summaryRow_rejectsInvalidSchemaUuidTextHttpsMoneyAndCursor() {
        val invalidRows = listOf(
            validGuideSummaryRow().copy(schemaVersion = 2),
            validGuideSummaryRow().copy(id = "not-a-uuid"),
            validGuideSummaryRow().copy(name = " Guide Kwabor"),
            validGuideSummaryRow().copy(baseCityId = "Cotonou"),
            validGuideSummaryRow().copy(coverImageUrl = "http://cdn.kwabor.test/guide.jpg"),
            validGuideSummaryRow().copy(coverImageAlt = "Portrait\ndu guide"),
            validGuideSummaryRow().copy(indicativePriceXof = -1),
            validGuideSummaryRow().copy(rowCursor = "cursor with spaces"),
        )

        invalidRows.forEach { row ->
            assertFailsWith<GuideDiscoveryDataException.Unexpected> { row.toDomain() }
        }
    }

    @Test
    fun summaryRow_rejectsInvalidRatingAndCountCombinations() {
        val invalidRows = listOf(
            validGuideSummaryRow().copy(ratingAverage = Double.NaN),
            validGuideSummaryRow().copy(ratingAverage = 5.01),
            validGuideSummaryRow().copy(ratingCount = -1),
            validGuideSummaryRow().copy(ratingAverage = null, ratingCount = 1),
            validGuideSummaryRow().copy(ratingAverage = 4.0, ratingCount = 0),
        )

        invalidRows.forEach { row ->
            assertFailsWith<GuideDiscoveryDataException.Unexpected> { row.toDomain() }
        }
    }

    @Test
    fun summaryRow_acceptsTheUnratedContract() {
        val summary = validGuideSummaryRow().copy(ratingAverage = null, ratingCount = 0).toDomain()

        assertEquals(null, summary.ratingAverage)
        assertEquals(0, summary.ratingCount)
    }

    @Test
    fun summaryRow_rejectsMissingAndDuplicateNestedFacets() {
        val language = GuideFacetReferenceDto(id = "francais", label = "Français")
        val invalidRows = listOf(
            validGuideSummaryRow().copy(languages = emptyList()),
            validGuideSummaryRow().copy(languages = listOf(language, language)),
            validGuideSummaryRow().copy(coverageCities = emptyList()),
            validGuideSummaryRow().copy(specialties = emptyList()),
        )

        invalidRows.forEach { row ->
            assertFailsWith<GuideDiscoveryDataException.Unexpected> { row.toDomain() }
        }
    }

    @Test
    fun page_usesTheLastRetainedCursorAndValidatesTheSentinel() {
        val page = listOf(
            validGuideSummaryRow(id = GUIDE_ID_ONE, cursor = "cursor-one"),
            validGuideSummaryRow(id = GUIDE_ID_TWO, cursor = "cursor-two"),
            validGuideSummaryRow(id = GUIDE_ID_THREE, cursor = "cursor-three"),
        ).toGuideSummaryPage(limit = 2)

        assertEquals(listOf(GUIDE_ID_ONE, GUIDE_ID_TWO), page.items.map { item -> item.id })
        assertEquals("cursor-two", page.nextCursor)

        assertFailsWith<GuideDiscoveryDataException.Unexpected> {
            listOf(
                validGuideSummaryRow(id = GUIDE_ID_ONE),
                validGuideSummaryRow(id = GUIDE_ID_TWO),
                validGuideSummaryRow(id = GUIDE_ID_THREE).copy(coverImageUrl = "invalid"),
            ).toGuideSummaryPage(limit = 2)
        }
    }

    @Test
    fun page_rejectsMoreThanOneSentinelRow() {
        assertFailsWith<GuideDiscoveryDataException.Unexpected> {
            listOf(
                validGuideSummaryRow(id = GUIDE_ID_ONE),
                validGuideSummaryRow(id = GUIDE_ID_TWO),
                validGuideSummaryRow(id = GUIDE_ID_THREE),
            ).toGuideSummaryPage(limit = 1)
        }
    }

    @Test
    fun page_rejectsDuplicateGuideIds() {
        assertFailsWith<GuideDiscoveryDataException.Unexpected> {
            listOf(
                validGuideSummaryRow(id = GUIDE_ID_ONE, cursor = "cursor-one"),
                validGuideSummaryRow(id = GUIDE_ID_ONE, cursor = "cursor-two"),
            ).toGuideSummaryPage(limit = 2)
        }
    }
}
