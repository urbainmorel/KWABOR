package com.kwabor.android.app

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import com.kwabor.android.onboarding.IntroMediaSource
import com.kwabor.android.presentation.onboarding.OnboardingIntent
import com.kwabor.android.presentation.onboarding.OnboardingUiState
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.android.ui.screens.onboarding.IntroScreen
import com.kwabor.android.ui.screens.onboarding.IntroScreenActions
import com.kwabor.android.ui.screens.onboarding.IntroScreenState
import com.kwabor.android.ui.screens.onboarding.OnboardingLandingActions
import com.kwabor.android.ui.screens.onboarding.OnboardingLandingScreen
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun KwaborIntroRoute(
    strings: KwaborStrings,
    mediaSource: IntroMediaSource,
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
) {
    IntroScreen(
        strings = strings,
        state = IntroScreenState(
            mediaSource = mediaSource,
            reducedMotion = !ValueAnimator.areAnimatorsEnabled(),
            isGuestDisclosureVisible = state.isGuestDisclosureVisible,
        ),
        actions = IntroScreenActions(
            onDisplayed = { viewModel.onIntent(OnboardingIntent.IntroDisplayed) },
            onCompleted = { viewModel.onIntent(OnboardingIntent.IntroCompleted) },
            onSkipped = { viewModel.onIntent(OnboardingIntent.IntroSkipped) },
        ),
        landingActions = viewModel.landingActions(),
    )
}

@Composable
internal fun KwaborLandingRoute(strings: KwaborStrings, state: OnboardingUiState, viewModel: OnboardingViewModel) {
    OnboardingLandingScreen(
        strings = strings,
        isGuestDisclosureVisible = state.isGuestDisclosureVisible,
        actions = viewModel.landingActions(),
    )
}

private fun OnboardingViewModel.landingActions(): OnboardingLandingActions = OnboardingLandingActions(
    onSignUp = { onIntent(OnboardingIntent.SignUpSelected) },
    onSignIn = { onIntent(OnboardingIntent.SignInSelected) },
    onGuestSelected = { onIntent(OnboardingIntent.GuestSelected) },
    onGuestConfirmed = { onIntent(OnboardingIntent.GuestConfirmed) },
    onGuestCancelled = { onIntent(OnboardingIntent.GuestCancelled) },
)
