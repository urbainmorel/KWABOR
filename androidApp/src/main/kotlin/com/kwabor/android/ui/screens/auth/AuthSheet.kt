package com.kwabor.android.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.AuthProtectedAction
import com.kwabor.android.presentation.auth.AuthSoftWallContext
import com.kwabor.shared.i18n.KwaborStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthSheet(
    strings: KwaborStrings,
    state: AuthSheetState,
    actions: AuthSheetActions,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss) {
        AuthSheetContent(strings = strings, state = state, actions = actions, modifier = modifier)
    }
}

@Composable
private fun AuthSheetContent(
    strings: KwaborStrings,
    state: AuthSheetState,
    actions: AuthSheetActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        AuthSheetHeader(context = state.context, errorMessage = state.errorMessage)
        AuthSheetButtons(
            strings = strings,
            loading = state.federatedSignInInProgress,
            actions = actions,
        )
    }
}

@Composable
private fun AuthSheetHeader(context: AuthSoftWallContext?, errorMessage: String?) {
    Text(
        text = stringResource(context.softWallTitleResource()),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = stringResource(context.softWallSupportResource()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
    if (errorMessage != null) {
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ColumnScope.AuthSheetButtons(strings: KwaborStrings, loading: Boolean, actions: AuthSheetActions) {
    GoogleSignInButton(loading = loading, enabled = true, onClick = actions.onGoogleSignIn)
    AuthMethodDivider()
    Button(onClick = actions.onSignUp, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.registration_continue_email))
    }
    OutlinedButton(onClick = actions.onSignIn, modifier = Modifier.fillMaxWidth()) {
        Text(strings.onboardingSignIn)
    }
    TextButton(
        onClick = actions.onLater,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(stringResource(R.string.registration_softwall_later))
    }
}

private fun AuthSoftWallContext?.softWallTitleResource(): Int = when (this?.action) {
    AuthProtectedAction.Like -> R.string.registration_softwall_like_title
    AuthProtectedAction.Favorite -> R.string.registration_softwall_favorite_title
    AuthProtectedAction.Other, null -> R.string.registration_softwall_title
}

private fun AuthSoftWallContext?.softWallSupportResource(): Int = when (this?.action) {
    AuthProtectedAction.Like -> R.string.registration_softwall_like_support
    AuthProtectedAction.Favorite -> R.string.registration_softwall_favorite_support
    AuthProtectedAction.Other, null -> R.string.registration_softwall_support
}
