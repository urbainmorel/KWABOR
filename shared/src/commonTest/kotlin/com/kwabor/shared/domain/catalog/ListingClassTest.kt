package com.kwabor.shared.domain.catalog

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListingClassTest {
    @Test
    fun canBeClaimed_returnsTrueOnlyForCommercialAndEventListings() {
        assertFalse(ListingClass.Heritage.canBeClaimed)
        assertTrue(ListingClass.Commercial.canBeClaimed)
        assertTrue(ListingClass.Event.canBeClaimed)
    }
}
