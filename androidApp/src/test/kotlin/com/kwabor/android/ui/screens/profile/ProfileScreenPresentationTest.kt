package com.kwabor.android.ui.screens.profile

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileScreenPresentationTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun emailValue_preservesAvailableSessionEmail() {
        assertEquals("afi@kwabor.bj", profileEmailValue("afi@kwabor.bj", strings))
        assertEquals("afi@kwabor.bj", profileEmailValue("  afi@kwabor.bj  ", strings))
    }

    @Test
    fun emailValue_replacesMissingOrBlankSessionEmail() {
        assertEquals(strings.settings.emailUnavailable, profileEmailValue(null, strings))
        assertEquals(strings.settings.emailUnavailable, profileEmailValue("", strings))
        assertEquals(strings.settings.emailUnavailable, profileEmailValue("   ", strings))
    }
}
