package com.kwabor.shared.presentation.onboarding

enum class OnboardingEntry(val routeKey: String) {
    RestoringSession(routeKey = "restoring_session"),
    Intro(routeKey = "intro"),
    Authentication(routeKey = "authentication"),
    Home(routeKey = "home"),
}

object OnboardingEntryResolver {
    fun resolve(
        firstLaunchCompleted: Boolean,
        sessionRestoreCompleted: Boolean,
        isAuthenticated: Boolean,
        guestAccessGranted: Boolean,
    ): OnboardingEntry = when {
        !firstLaunchCompleted -> OnboardingEntry.Intro
        !sessionRestoreCompleted -> OnboardingEntry.RestoringSession
        isAuthenticated || guestAccessGranted -> OnboardingEntry.Home
        else -> OnboardingEntry.Authentication
    }
}
