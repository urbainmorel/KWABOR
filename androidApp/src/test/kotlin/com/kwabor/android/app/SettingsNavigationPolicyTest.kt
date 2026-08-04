package com.kwabor.android.app

import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsNavigationPolicyTest {
    @Test
    fun settings_isTypedChildRouteOfProfileRoot() {
        assertEquals(RootNavigationDestination.Profile, SettingsRoute.rootDestination)
    }

    @Test
    fun settings_doesNotAddARootDestination() {
        assertEquals(5, RootNavigationDestination.entries.size)
    }
}
