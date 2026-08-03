package com.kwabor.shared.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertEquals("Accueil", onboardingStrings.home)
        assertEquals("Réessayer", onboardingStrings.retry)
        assertEquals("Chargement", onboardingStrings.loading)
        val settingsStrings = onboardingStrings.settings
        assertEquals("Paramètres", settingsStrings.title)
        assertEquals("Adresse e-mail indisponible", settingsStrings.emailUnavailable)
        assertEquals("Continuer avec Google", onboardingStrings.authSignInWithGoogle)
        assertEquals("Continuer avec Apple", onboardingStrings.authSignInWithApple)
        assertEquals("ou", onboardingStrings.authOrSeparator)
        assertEquals(
            "Cette méthode de connexion est indisponible pour le moment.",
            onboardingStrings.authFederatedUnavailable,
        )
        assertEquals(
            "La vérification de votre identité a échoué.",
            onboardingStrings.authReauthenticationFailed,
        )
        assertEquals("Zone sensible", onboardingStrings.dangerZoneTitle)
        assertEquals("Supprimer mon compte", onboardingStrings.authDeleteAccount)
        assertEquals("SUPPRIMER", onboardingStrings.authDeleteAccountConfirmationPhrase)
        assertEquals(
            "La suppression du compte a échoué. Réessayez sans fermer cet écran.",
            onboardingStrings.authAccountDeletionFailed,
        )
        assertEquals("Activer mon espace Promoteur", onboardingStrings.promoterActivationTitle)
        assertEquals("Commerce invité", onboardingStrings.promoterActivationBusinessName)
        assertEquals("Votre espace Promoteur est prêt.", onboardingStrings.promoterActivationSuccess)
        assertEquals("Ce lien d'activation est invalide.", onboardingStrings.authPromoterInviteInvalid)
        assertEquals(
            "Ce code a expiré. Demandez-en un nouveau.",
            onboardingStrings.registrationOtpExpired,
        )
        assertEquals(
            "Recevez les nouveautés utiles près de votre ville. Vous gardez le contrôle dans les paramètres.",
            onboardingStrings.registrationNotificationSupport,
        )
        assertEquals(
            "Autoriser les ajustements de configuration à distance",
            onboardingStrings.registrationRemoteConfigConsent,
        )
        assertEquals("authentication", bridge.onboardingEntryKey(true, true, false, false))
        val telemetry = bridge.onboardingTelemetry()
        assertEquals("intro_video_shown", telemetry.shownEvent.name.wireName)
        assertEquals("intro_video_skipped", telemetry.skippedEvent.name.wireName)
        assertFalse(bridge.hasCatalogConfiguration())
    }

    @Test
    fun reflectsCompositionRootAvailabilityForIosHost() {
        val bridge = KwaborSharedBridge(hasCatalogConfiguration = true)

        assertTrue(bridge.hasCatalogConfiguration())
    }

    @Test
    fun exposesOnlyAcceptedCatalogDetailDeepLinksToNativeHosts() {
        val bridge = KwaborSharedBridge()
        val listingId = "123e4567-e89b-42d3-a456-426614174000"

        assertEquals(
            listingId,
            bridge.catalogDetailListingIdForDeepLink("KWABOR://LISTING/${listingId.uppercase()}"),
        )
        assertNull(bridge.catalogDetailListingIdForDeepLink("kwabor://listing/$listingId?source=share"))
        assertNull(bridge.catalogDetailListingIdForDeepLink("kwabor://listing/not-a-uuid"))
    }
}
