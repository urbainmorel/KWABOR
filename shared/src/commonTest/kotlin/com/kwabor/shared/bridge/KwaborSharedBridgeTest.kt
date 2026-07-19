package com.kwabor.shared.bridge

import com.kwabor.shared.domain.observability.DiagnosticCode
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
        assertEquals("Kwabor est indisponible pour le moment. Réessayez plus tard.", onboardingStrings.authUnavailable)
        assertEquals(
            "Ce code a expiré. Demandez-en un nouveau.",
            onboardingStrings.registrationOtpExpired,
        )
        assertEquals(
            "Recevez les nouveautés utiles près de votre ville. Vous gardez le contrôle dans les paramètres.",
            onboardingStrings.registrationNotificationSupport,
        )
        assertEquals("authentication", bridge.onboardingEntryKey(true, true, false, false))
        val telemetry = bridge.onboardingTelemetry()
        assertEquals("intro_video_shown", telemetry.shownEvent.name.wireName)
        assertEquals("intro_video_skipped", telemetry.skippedEvent.name.wireName)
        assertEquals(DiagnosticCode.IntroVideoIntegrityFailed, telemetry.integrityDiagnosticCode)
        assertFalse(bridge.hasCatalogConfiguration())
    }

    @Test
    fun reflectsCompositionRootAvailabilityForIosHost() {
        val bridge = KwaborSharedBridge(hasCatalogConfiguration = true)

        assertTrue(bridge.hasCatalogConfiguration())
    }
}
