package com.kwabor.shared.data.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SupabaseCatalogDataSourceTest {
    @Test
    fun toSummaryPage_usesLastRetainedRowCursorWhenSentinelExists() {
        val rows = listOf(
            summaryRow(id = "listing-1", rowCursor = "cursor-1"),
            summaryRow(id = "listing-2", rowCursor = "cursor-2"),
            summaryRow(id = "listing-3", rowCursor = "cursor-3"),
        )

        val page = rows.toSummaryPage(limit = 2)

        assertEquals(listOf("listing-1", "listing-2"), page.items.map { item -> item.id })
        assertEquals("cursor-2", page.nextCursor)
    }

    @Test
    fun toSummaryPage_returnsNoCursorWhenPageExactlyFillsLimit() {
        val rows = listOf(
            summaryRow(id = "listing-1", rowCursor = "cursor-1"),
            summaryRow(id = "listing-2", rowCursor = "cursor-2"),
        )

        val page = rows.toSummaryPage(limit = 2)

        assertEquals(rows, page.items)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun toSummaryPage_rejectsBlankContinuationCursor() {
        val rows = listOf(
            summaryRow(id = "listing-1", rowCursor = "cursor-1"),
            summaryRow(id = "listing-2", rowCursor = "   "),
            summaryRow(id = "listing-3", rowCursor = "cursor-3"),
        )

        assertFailsWith<CatalogDataException.Unexpected> {
            rows.toSummaryPage(limit = 2)
        }
    }
}

private fun summaryRow(id: String, rowCursor: String): ListingSummaryDto = ListingSummaryDto(
    id = id,
    type = "etablissement",
    listingClass = "commercial",
    status = "publie",
    name = "Restaurant Kwabor",
    cityId = "cotonou",
    categoryId = "restaurants",
    coverImageUrl = "https://cdn.kwabor.test/$id.jpg",
    priceFromXof = 5_000,
    ratingAverage = 4.5,
    likesCount = 12,
    verified = true,
    sponsoredUntil = null,
    isSponsoredPlacement = false,
    rowCursor = rowCursor,
)
