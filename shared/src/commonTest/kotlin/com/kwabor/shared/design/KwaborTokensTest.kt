package com.kwabor.shared.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KwaborTokensTest {
    @Test
    fun spacing_usesFourPointScale() {
        assertEquals(4f, KwaborSpacing.Xs.value)
        assertEquals(8f, KwaborSpacing.Sm.value)
        assertEquals(16f, KwaborSpacing.Lg.value)
        assertEquals(32f, KwaborSpacing.Xxxl.value)
    }

    @Test
    fun sizing_respectsMinimumTouchTarget() {
        assertTrue(KwaborSizing.TouchTarget.value >= 44f)
        assertEquals(76f, KwaborSizing.BottomNavigationHeight.value)
    }

    @Test
    fun radius_matchesDesignFoundation() {
        assertEquals(16f, KwaborRadius.Card.value)
        assertEquals(28f, KwaborRadius.Sheet.value)
    }
}
