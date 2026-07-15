package com.kwabor.shared.presentation.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingEntryTest {
    @Test
    fun firstLaunchAlwaysStartsWithIntro() {
        assertEquals(
            OnboardingEntry.Intro,
            resolve(firstLaunchCompleted = false, sessionRestoreCompleted = false, isAuthenticated = false),
        )
        assertEquals(
            OnboardingEntry.Intro,
            resolve(firstLaunchCompleted = false, sessionRestoreCompleted = true, isAuthenticated = true),
        )
    }

    @Test
    fun returningLaunchWaitsForSessionBeforeChoosingDestination() {
        assertEquals(
            OnboardingEntry.RestoringSession,
            resolve(firstLaunchCompleted = true, sessionRestoreCompleted = false, isAuthenticated = false),
        )
        assertEquals(
            OnboardingEntry.Home,
            resolve(firstLaunchCompleted = true, sessionRestoreCompleted = true, isAuthenticated = true),
        )
        assertEquals(
            OnboardingEntry.Authentication,
            resolve(firstLaunchCompleted = true, sessionRestoreCompleted = true, isAuthenticated = false),
        )
    }

    @Test
    fun guestAccessAppliesOnlyToTheCurrentProcess() {
        assertEquals(
            OnboardingEntry.Home,
            resolve(
                firstLaunchCompleted = true,
                sessionRestoreCompleted = true,
                isAuthenticated = false,
                guestAccessGranted = true,
            ),
        )
        assertEquals(
            OnboardingEntry.Authentication,
            resolve(firstLaunchCompleted = true, sessionRestoreCompleted = true, isAuthenticated = false),
        )
    }

    private fun resolve(
        firstLaunchCompleted: Boolean,
        sessionRestoreCompleted: Boolean,
        isAuthenticated: Boolean,
        guestAccessGranted: Boolean = false,
    ): OnboardingEntry = OnboardingEntryResolver.resolve(
        firstLaunchCompleted = firstLaunchCompleted,
        sessionRestoreCompleted = sessionRestoreCompleted,
        isAuthenticated = isAuthenticated,
        guestAccessGranted = guestAccessGranted,
    )
}
