package com.kwabor.shared.i18n

import com.kwabor.shared.domain.auth.AuthenticationMethod
import com.kwabor.shared.domain.i18n.AppLocale
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStringsTest {
    private val strings = stringsFor(AppLocale.French).settings

    @Test
    fun exposesTheDeliveredSettingsCopy() {
        assertEquals("Paramètres", strings.title)
        assertEquals("Compte, confidentialité et suppression du compte", strings.profileEntrySubtitle)
        assertEquals("Adresse e-mail", strings.emailLabel)
        assertEquals("Méthode de connexion", strings.authenticationMethodLabel)
        assertEquals("Confidentialité", strings.privacySectionTitle)
        assertEquals(
            "Ces choix sont facultatifs. Vous pouvez les modifier ou les retirer à tout moment.",
            strings.privacySectionSupport,
        )
        assertEquals(
            "Partager des statistiques d'utilisation pour améliorer Kwabor",
            strings.analyticsConsent,
        )
        assertEquals(
            "Partager des informations sur les pannes et les lenteurs",
            strings.diagnosticsConsent,
        )
        assertEquals(
            "Autoriser certains réglages de l'application sans mise à jour (hors vidéo)",
            strings.remoteConfigurationConsent,
        )
    }

    @Test
    fun mapsEveryAuthenticationMethodWithoutTechnicalFallbackCopy() {
        assertEquals("E-mail et mot de passe", strings.authenticationMethodName(AuthenticationMethod.Email))
        assertEquals("Google", strings.authenticationMethodName(AuthenticationMethod.Google))
        assertEquals("Apple", strings.authenticationMethodName(AuthenticationMethod.Apple))
        assertEquals("Non renseignée", strings.authenticationMethodName(null))
    }

    @Test
    fun normalizesAccountEmailAndUsesReadableFallbacks() {
        assertEquals("afi@kwabor.bj", strings.accountEmail("  afi@kwabor.bj  "))
        assertEquals(strings.emailUnavailable, strings.accountEmail(null))
        assertEquals(strings.emailUnavailable, strings.accountEmail("   "))
    }
}
