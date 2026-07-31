package com.kwabor.android.ui.screens.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class RestoringLaunchContentTest {
    @Test
    fun pendingIntroDecisionKeepsTheLaunchWordmarkVisible() {
        assertEquals(
            RestoringLaunchContent.Wordmark,
            restoringLaunchContent(isLaunchDecisionComplete = false),
        )
    }

    @Test
    fun completedIntroDecisionAllowsSessionRestoreProgress() {
        assertEquals(
            RestoringLaunchContent.Progress,
            restoringLaunchContent(isLaunchDecisionComplete = true),
        )
    }
}
