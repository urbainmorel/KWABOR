package com.kwabor.shared.data.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogDetailTextValidationTest {
    @Test
    fun tags_enforceCountAndUnicodeCodePointBounds() {
        val exactUnicodeBoundary = "🐕".repeat(24)

        assertEquals(listOf(exactUnicodeBoundary), listOf(exactUnicodeBoundary).requireCatalogTags("tags"))
        assertFailsWith<CatalogDataException.Unexpected> { listOf("🐕".repeat(25)).requireCatalogTags("tags") }
        assertFailsWith<CatalogDataException.Unexpected> {
            List(11) { index -> "tag-$index" }.requireCatalogTags("tags")
        }
    }

    @Test
    fun typedTextValues_enforceCountLengthAndControlCharacterBounds() {
        val exactUnicodeBoundary = "🐕".repeat(80)

        assertEquals(
            listOf(exactUnicodeBoundary),
            listOf(exactUnicodeBoundary).requireCatalogTypedTextValues("detail.languages"),
        )
        assertFailsWith<CatalogDataException.Unexpected> {
            listOf("🐕".repeat(81)).requireCatalogTypedTextValues("detail.languages")
        }
        assertFailsWith<CatalogDataException.Unexpected> {
            List(21) { index -> "language-$index" }.requireCatalogTypedTextValues("detail.languages")
        }
        assertFailsWith<CatalogDataException.Unexpected> {
            listOf("fran\nçais").requireCatalogTypedTextValues("detail.languages")
        }
    }

    @Test
    fun shortLabels_enforceUnicodeCodePointAndControlCharacterBounds() {
        val exactUnicodeBoundary = "🐕".repeat(80)

        assertEquals(exactUnicodeBoundary, exactUnicodeBoundary.requireCatalogShortText("room.name"))
        assertFailsWith<CatalogDataException.Unexpected> {
            "🐕".repeat(81).requireCatalogShortText("room.name")
        }
        assertFailsWith<CatalogDataException.Unexpected> {
            "Suite\nVIP".requireCatalogShortText("room.name")
        }
    }

    @Test
    fun nestedCollections_enforceTwentyItemsAndZeroBasedDisplayOrders() {
        val exactBoundary = List(20) { index -> index }

        assertEquals(exactBoundary, exactBoundary.requireCatalogNestedItemCount("room_types"))
        assertEquals(19, 19.requireCatalogNestedDisplayOrder("room_types.display_order"))
        assertFailsWith<CatalogDataException.Unexpected> {
            List(21) { index -> index }.requireCatalogNestedItemCount("room_types")
        }
        assertFailsWith<CatalogDataException.Unexpected> {
            20.requireCatalogNestedDisplayOrder("room_types.display_order")
        }
    }
}
