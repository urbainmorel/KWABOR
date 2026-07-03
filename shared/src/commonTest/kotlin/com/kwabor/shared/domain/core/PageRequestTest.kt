package com.kwabor.shared.domain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PageRequestTest {
    @Test
    fun pageRequest_acceptsDefaultPaging() {
        val request = PageRequest()

        assertEquals(0, request.offset)
        assertEquals(PageRequest.DEFAULT_LIMIT, request.limit)
    }

    @Test
    fun pageRequest_rejectsNegativeOffset() {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(offset = -1)
        }
    }

    @Test
    fun pageRequest_rejectsLimitAboveMaximum() {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(limit = PageRequest.MAX_LIMIT + 1)
        }
    }
}
