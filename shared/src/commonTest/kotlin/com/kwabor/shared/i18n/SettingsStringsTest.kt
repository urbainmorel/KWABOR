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
        assertEquals("Compte, connexion et suppression du compte", strings.profileEntrySubtitle)
        assertEquals("Adresse e-mail", strings.emailLabel)
        assertEquals("Méthode de connexion", strings.authenticationMethodLabel)
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
