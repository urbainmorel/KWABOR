package com.kwabor.android.ui.screens.auth

import androidx.compose.ui.semantics.LiveRegionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionRestoreFailureAccessibilityPolicyTest {
    @Test
    fun failureSurfaceHidesBackNavigationAndAnnouncesImmediately() {
        assertFalse(SessionRestoreFailureAccessibilityPolicy.SHOW_BACK_BUTTON)
        assertEquals(
            LiveRegionMode.Assertive,
            SessionRestoreFailureAccessibilityPolicy.LIVE_REGION,
        )
    }
}
