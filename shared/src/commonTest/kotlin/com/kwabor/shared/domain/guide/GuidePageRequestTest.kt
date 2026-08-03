package com.kwabor.shared.domain.guide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GuidePageRequestTest {
    @Test
    fun defaults_matchThePublicPageContract() {
        val request = GuidePageRequest()

        assertEquals(null, request.cursor)
        assertEquals(20, request.limit)
    }

    @Test
    fun limit_acceptsTheInclusiveContractBounds() {
        assertEquals(1, GuidePageRequest(limit = 1).limit)
        assertEquals(50, GuidePageRequest(limit = 50).limit)
    }

    @Test
    fun limit_rejectsValuesOutsideTheContract() {
        assertFailsWith<IllegalArgumentException> { GuidePageRequest(limit = 0) }
        assertFailsWith<IllegalArgumentException> { GuidePageRequest(limit = 51) }
    }

    @Test
    fun cursor_rejectsBlankValues() {
        assertFailsWith<IllegalArgumentException> { GuidePageRequest(cursor = "") }
        assertFailsWith<IllegalArgumentException> { GuidePageRequest(cursor = "   ") }
    }
}
