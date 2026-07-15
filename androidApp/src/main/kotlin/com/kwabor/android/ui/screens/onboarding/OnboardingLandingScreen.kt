package com.kwabor.android.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.kwabor.android.R
import com.kwabor.android.design.KwaborAlpha
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun OnboardingLandingScreen(
    strings: KwaborStrings,
    isGuestDisclosureVisible: Boolean,
    actions: OnboardingLandingActions,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        OnboardingLandingBackground()
        OnboardingLandingContent(strings = strings, actions = actions)
    }

    if (isGuestDisclosureVisible) {
        GuestDisclosureDialog(strings = strings, actions = actions)
    }
}

internal data class OnboardingLandingActions(
    val onSignUp: () -> Unit,
    val onSignIn: () -> Unit,
    val onGuestSelected: () -> Unit,
    val onGuestConfirmed: () -> Unit,
    val onGuestCancelled: () -> Unit,
)

@Composable
private fun OnboardingLandingBackground() {
    Image(
        painter = painterResource(R.drawable.kwabor_intro_fallback),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KwaborColors.Ink950.copy(alpha = KwaborAlpha.SCRIM_HIGH)),
    )
}

@Composable
private fun OnboardingLandingContent(strings: KwaborStrings, actions: OnboardingLandingActions) {
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .padding(KwaborSpacing.Xxl),
    ) {
        LanguageLabel(label = strings.onboardingLanguageLabel)
        LandingActions(strings = strings, actions = actions)
    }
}

@Composable
private fun LanguageLabel(label: String) {
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun LandingActions(strings: KwaborStrings, actions: OnboardingLandingActions) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Lg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = strings.onboardingTitle,
            color = Color.White,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = strings.onboardingSubtitle,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = actions.onSignUp, modifier = Modifier.fillMaxWidth()) {
            Text(strings.onboardingSignUp)
        }
        OutlinedButton(
            onClick = actions.onSignIn,
            border = BorderStroke(KwaborSizing.Hairline, Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = strings.onboardingSignIn, color = Color.White)
        }
        TextButton(onClick = actions.onGuestSelected) {
            Text(text = strings.onboardingContinueWithoutAccount, color = Color.White)
        }
    }
}

@Composable
private fun GuestDisclosureDialog(strings: KwaborStrings, actions: OnboardingLandingActions) {
    AlertDialog(
        onDismissRequest = actions.onGuestCancelled,
        title = { Text(strings.onboardingContinueWithoutAccount) },
        text = { Text(strings.onboardingGuestDisclosure) },
        confirmButton = {
            TextButton(onClick = actions.onGuestConfirmed) {
                Text(strings.onboardingGuestConfirm)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onGuestCancelled) {
                Text(strings.onboardingGuestCancel)
            }
        },
    )
}
