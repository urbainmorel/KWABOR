package com.kwabor.android.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.kwabor.android.R
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing

@Composable
internal fun GoogleSignInButton(
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accessibilityLabel = stringResource(R.string.auth_google_continue)
    val loadingLabel = stringResource(R.string.auth_google_loading)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(KwaborSizing.MinimumAccessibleTouchTarget)
            .semantics {
                contentDescription = accessibilityLabel
                if (loading) stateDescription = loadingLabel
            },
        enabled = enabled && !loading,
        shape = RoundedCornerShape(KwaborRadius.Pill),
        border = BorderStroke(KwaborSizing.Hairline, KwaborColors.GoogleButtonStroke),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = KwaborColors.GoogleButtonSurface,
            contentColor = KwaborColors.GoogleButtonText,
            disabledContainerColor = KwaborColors.GoogleButtonSurface,
            disabledContentColor = KwaborColors.GoogleButtonText,
        ),
    ) {
        GoogleSignInButtonContent(loading)
    }
}

@Composable
private fun GoogleSignInButtonContent(loading: Boolean) {
    Image(
        painter = painterResource(R.drawable.google_g_logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(KwaborSizing.GoogleLogo),
    )
    Spacer(Modifier.size(KwaborSpacing.Md))
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(KwaborSizing.GoogleLogo),
            color = KwaborColors.GoogleButtonText,
            strokeWidth = KwaborSizing.Hairline,
        )
    } else {
        Text(text = stringResource(R.string.auth_google_continue))
    }
}

@Composable
internal fun AuthMethodDivider(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.auth_method_separator),
            modifier = Modifier.padding(horizontal = KwaborSpacing.Md),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
