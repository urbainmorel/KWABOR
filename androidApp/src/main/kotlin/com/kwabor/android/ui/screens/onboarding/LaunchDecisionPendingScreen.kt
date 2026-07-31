package com.kwabor.android.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import com.kwabor.android.R
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun LaunchDecisionPendingScreen(strings: KwaborStrings) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.kwabor_wordmark_background)),
    ) {
        Image(
            painter = painterResource(R.drawable.kwabor_launch_wordmark),
            contentDescription = strings.appName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal enum class RestoringLaunchContent {
    Wordmark,
    Progress,
}

internal fun restoringLaunchContent(isLaunchDecisionComplete: Boolean): RestoringLaunchContent =
    if (isLaunchDecisionComplete) RestoringLaunchContent.Progress else RestoringLaunchContent.Wordmark
