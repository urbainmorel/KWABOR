package com.kwabor.android.app

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import com.kwabor.android.onboarding.IntroMediaSource
import com.kwabor.android.presentation.onboarding.OnboardingIntent
import com.kwabor.android.presentation.onboarding.OnboardingUiState
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.android.ui.screens.onboarding.IntroScreen
import com.kwabor.android.ui.screens.onboarding.IntroScreenActions
import com.kwabor.android.ui.screens.onboarding.OnboardingLandingActions
import com.kwabor.android.ui.screens.onboarding.OnboardingLandingScreen
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun KwaborIntroRoute(strings: KwaborStrings, mediaSource: IntroMediaSource, viewModel: OnboardingViewModel) {
    IntroScreen(
        strings = strings,
        mediaSource = mediaSource,
        reducedMotion = !ValueAnimator.areAnimatorsEnabled(),
        actions = IntroScreenActions(
            onDisplayed = { viewModel.onIntent(OnboardingIntent.IntroDisplayed) },
            onCompleted = { viewModel.onIntent(OnboardingIntent.IntroCompleted) },
            onSkipped = { viewModel.onIntent(OnboardingIntent.IntroSkipped) },
        ),
    )
}

@Composable
internal fun KwaborLandingRoute(strings: KwaborStrings, state: OnboardingUiState, viewModel: OnboardingViewModel) {
    OnboardingLandingScreen(
        strings = strings,
        isGuestDisclosureVisible = state.isGuestDisclosureVisible,
        actions = OnboardingLandingActions(
            onSignUp = { viewModel.onIntent(OnboardingIntent.SignUpSelected) },
            onSignIn = { viewModel.onIntent(OnboardingIntent.SignInSelected) },
            onGuestSelected = { viewModel.onIntent(OnboardingIntent.GuestSelected) },
            onGuestConfirmed = { viewModel.onIntent(OnboardingIntent.GuestConfirmed) },
            onGuestCancelled = { viewModel.onIntent(OnboardingIntent.GuestCancelled) },
        ),
    )
}
