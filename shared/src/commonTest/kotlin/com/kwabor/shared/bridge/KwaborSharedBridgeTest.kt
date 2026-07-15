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
        val onboardingStrings = bridge.onboardingStrings()
        assertEquals("Passer", onboardingStrings.introSkip)
        assertEquals("Découvrez le Bénin", onboardingStrings.title)
        assertEquals("S'inscrire", onboardingStrings.signUp)
        assertEquals("Recevoir le code", onboardingStrings.authRequestOtp)
        assertEquals("authentication", bridge.onboardingEntryKey(true, true, false, false))
        assertFalse(bridge.hasCatalogConfiguration())
    }

    @Test
    fun reflectsCompositionRootAvailabilityForIosHost() {
        val bridge = KwaborSharedBridge(hasCatalogConfiguration = true)

        assertTrue(bridge.hasCatalogConfiguration())
    }
}
