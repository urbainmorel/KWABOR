package com.kwabor.android.ui.screens.settings

import com.kwabor.shared.domain.auth.AuthenticationMethod
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsScreenPresentationTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun accountPresentation_exposesOnlyRealSessionData() {
        val presentation = settingsAccountPresentation(
            email = "afi@kwabor.bj",
            authenticationMethod = AuthenticationMethod.Google,
            strings = strings,
        )

        assertEquals("afi@kwabor.bj", presentation.email)
        assertEquals(strings.settings.authenticationMethodGoogle, presentation.authenticationMethod)
        assertEquals(true, presentation.accountDeletionAvailable)
    }

    @Test
    fun accountPresentation_usesSafeFallbacksForUnavailableSessionData() {
        val presentation = settingsAccountPresentation(
            email = " ",
            authenticationMethod = null,
            strings = strings,
        )

        assertEquals(strings.settings.emailUnavailable, presentation.email)
        assertEquals(strings.settings.authenticationMethodUnavailable, presentation.authenticationMethod)
        assertEquals(false, presentation.accountDeletionAvailable)
    }
}
