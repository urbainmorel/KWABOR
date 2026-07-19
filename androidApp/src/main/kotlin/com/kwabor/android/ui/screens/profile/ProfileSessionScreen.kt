package com.kwabor.android.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.AuthAccessUiState
import com.kwabor.shared.i18n.KwaborStrings

internal data class ProfileSessionScreenActions(
    val onRequestSignOut: () -> Unit,
    val onCancelSignOut: () -> Unit,
    val onConfirmSignOut: () -> Unit,
)

internal object ProfileSessionScreen {
    @Composable
    operator fun invoke(
        email: String,
        authAccessState: AuthAccessUiState,
        strings: KwaborStrings,
        actions: ProfileSessionScreenActions,
        modifier: Modifier = Modifier,
    ) {
        Surface(modifier = modifier.fillMaxSize()) {
            ProfileSessionContent(email, authAccessState, strings, actions)
        }
        if (authAccessState.signOutConfirmationVisible) {
            SignOutConfirmationDialog(
                loading = authAccessState.signOutInProgress,
                strings = strings,
                actions = actions,
            )
        }
    }
}

@Composable
private fun ProfileSessionContent(
    email: String,
    authAccessState: AuthAccessUiState,
    strings: KwaborStrings,
    actions: ProfileSessionScreenActions,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(KwaborSpacing.Xxl),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = strings.authAccount, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(KwaborSpacing.Sm))
        Text(
            text = stringResource(R.string.profile_connected_as, email),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        authAccessState.signOutErrorMessage?.let { error ->
            Spacer(Modifier.height(KwaborSpacing.Lg))
            Text(
                text = error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(KwaborSpacing.Xxl))
        OutlinedButton(
            onClick = actions.onRequestSignOut,
            modifier = Modifier.fillMaxWidth(),
            enabled = !authAccessState.signOutInProgress,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(strings.authSignOut)
        }
    }
}

@Composable
private fun SignOutConfirmationDialog(loading: Boolean, strings: KwaborStrings, actions: ProfileSessionScreenActions) {
    AlertDialog(
        onDismissRequest = { if (!loading) actions.onCancelSignOut() },
        title = { Text(strings.authSignOutTitle) },
        text = { Text(strings.authSignOutConfirmation) },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmSignOut,
                enabled = !loading,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = KwaborSpacing.Sm).size(KwaborSpacing.Xl),
                        strokeWidth = KwaborSizing.Hairline,
                    )
                }
                Text(strings.authConfirm)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelSignOut, enabled = !loading) {
                Text(strings.authCancel)
            }
        },
    )
}
