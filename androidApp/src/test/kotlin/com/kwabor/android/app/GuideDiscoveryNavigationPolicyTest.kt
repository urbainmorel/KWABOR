package com.kwabor.android.app

import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class GuideDiscoveryNavigationPolicyTest {
    @Test
    fun guideDiscovery_doesNotAddASixthRootDestination() {
        assertEquals(5, RootNavigationDestination.entries.size)
    }
}
