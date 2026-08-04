package com.kwabor.shared.domain.search

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SearchRepositoryTest {
    @Test
    fun queryCanonicalizesTextAndKeepsValidatedFilters() {
        val filters = ListingFilters(cityId = "cotonou", listingType = ListingType.Establishment)

        val result = SearchQuery.from("  Restaurant Kwabor  ", filters)

        val query = assertIs<DomainResult.Success<SearchQuery>>(result).value
        assertEquals("Restaurant Kwabor", query.text)
        assertEquals(filters, query.filters)
        assertFalse(query.toString().contains("Restaurant Kwabor"))
        assertIs<DomainResult.Success<SearchQuery>>(
            SearchQuery.from("kwabor", ListingFilters(categoryId = "a".repeat(100))),
        )
    }

    @Test
    fun canonicalTextUsesOneValidatedAndRedactedRepresentation() {
        val canonicalText = assertNotNull(CanonicalSearchText.from("  Restaurant secret  "))

        assertEquals("Restaurant secret", canonicalText.value)
        assertEquals("CanonicalSearchText(value=<redacted>)", canonicalText.toString())
        assertFalse(canonicalText.toString().contains(canonicalText.value))
    }

    @Test
    fun queryRejectsInvalidTextAndUnsupportedFilters() {
        val invalidInputs = listOf("   ", "a".repeat(121), "restaurant\nkwabor")

        invalidInputs.forEach { text ->
            assertEquals(
                DomainError.Validation("error.search.query_invalid"),
                assertIs<DomainResult.Failure>(SearchQuery.from(text)).error,
            )
        }
        val invalidFilters = listOf(
            ListingFilters(onlyPublished = false),
            ListingFilters(cityId = " "),
            ListingFilters(categoryId = "a".repeat(101)),
        )
        invalidFilters.forEach { filters ->
            assertEquals(
                DomainError.Validation("error.search.filters_invalid"),
                assertIs<DomainResult.Failure>(SearchQuery.from("kwabor", filters)).error,
            )
        }
    }

    @Test
    fun pageRequestRejectsUnsafePaginationInputs() {
        assertFailsWith<IllegalArgumentException> { SearchPageRequest(cursor = " ") }
        assertFailsWith<IllegalArgumentException> { SearchPageRequest(cursor = "cursor with spaces") }
        assertFailsWith<IllegalArgumentException> { SearchPageRequest(limit = 0) }
        assertFailsWith<IllegalArgumentException> { SearchPageRequest(limit = 51) }
        assertFailsWith<IllegalArgumentException> {
            SearchPageRequest(excludedListingIds = setOf(""))
        }
    }

    @Test
    fun resultRejectsDuplicateItemsAndInvalidCursor() {
        val listing = listingSummary()

        assertFailsWith<IllegalArgumentException> {
            SearchResult(
                items = listOf(listing, listing.copy(name = "Même identifiant")),
                nextCursor = null,
                source = SearchResultSource.Network,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SearchResult(
                items = listOf(listing),
                nextCursor = " ",
                source = SearchResultSource.LocalCache,
            )
        }
    }
}

private fun listingSummary(): ListingSummary = ListingSummary(
    id = "listing-1",
    type = ListingType.Establishment,
    listingClass = ListingClass.Commercial,
    status = ListingStatus.Published,
    name = "Restaurant Kwabor",
    cityId = "cotonou",
    categoryId = "restaurants",
    coverImageUrl = null,
    priceFromXof = null,
    ratingAverage = null,
    likesCount = 0,
    verified = false,
    sponsoredUntilEpochMilliseconds = null,
)
