package com.kwabor.shared.domain.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ListingPageRequestTest {
    @Test
    fun listingPageRequest_acceptsDefaultPaging() {
        val request = ListingPageRequest()

        assertEquals(null, request.cursor)
        assertEquals(ListingPageRequest.DEFAULT_LIMIT, request.limit)
    }

    @Test
    fun listingPageRequest_rejectsLimitBelowMinimum() {
        assertFailsWith<IllegalArgumentException> {
            ListingPageRequest(limit = 0)
        }
    }

    @Test
    fun listingPageRequest_rejectsLimitAboveMaximum() {
        assertFailsWith<IllegalArgumentException> {
            ListingPageRequest(limit = ListingPageRequest.MAX_LIMIT + 1)
        }
    }

    @Test
    fun listingPageRequest_rejectsBlankCursor() {
        assertFailsWith<IllegalArgumentException> {
            ListingPageRequest(cursor = "   ")
        }
    }
}
