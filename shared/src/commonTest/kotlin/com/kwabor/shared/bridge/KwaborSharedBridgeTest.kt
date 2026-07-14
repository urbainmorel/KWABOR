package com.kwabor.shared.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KwaborSharedBridgeTest {
    @Test
    fun exposesFrenchFoundationCopyForIosHost() {
        val bridge = KwaborSharedBridge()

        assertEquals("Kwabor", bridge.appName())
        assertEquals("Découvrez le Bénin", bridge.homeTitle())
        assertEquals("Socle applicatif en place", bridge.foundationStatus())
        assertFalse(bridge.hasCatalogConfiguration())
    }

    @Test
    fun reflectsCompositionRootAvailabilityForIosHost() {
        val bridge = KwaborSharedBridge(hasCatalogConfiguration = true)

        assertTrue(bridge.hasCatalogConfiguration())
    }
}
