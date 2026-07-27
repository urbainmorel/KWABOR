package com.kwabor.android.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun SessionRestoreFailureScreen(strings: KwaborStrings, onRetry: () -> Unit) {
    BackHandler(enabled = true) {}
    val title = stringResource(R.string.auth_session_restore_error_title)
    AuthScreenFrame(
        onBack = {},
        backEnabled = false,
        showBackButton = SessionRestoreFailureAccessibilityPolicy.SHOW_BACK_BUTTON,
        modifier = Modifier.semantics {
            paneTitle = title
            liveRegion = SessionRestoreFailureAccessibilityPolicy.LIVE_REGION
        },
    ) {
        AuthHeading(
            title = title,
            supportingText = stringResource(R.string.auth_session_restore_error_support),
        )
        Spacer(Modifier.height(KwaborSpacing.Xl))
        AuthPrimaryButton(
            label = strings.retry,
            loading = false,
            enabled = true,
            onClick = onRetry,
        )
    }
}

internal object SessionRestoreFailureAccessibilityPolicy {
    const val SHOW_BACK_BUTTON: Boolean = false
    val LIVE_REGION: LiveRegionMode = LiveRegionMode.Assertive
}
