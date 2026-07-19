package com.kwabor.android.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.kwabor.shared.i18n.KwaborStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthSheet(strings: KwaborStrings, actions: AuthSheetActions, modifier: Modifier = Modifier) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
        ) {
            Text(
                text = stringResource(R.string.registration_softwall_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.registration_softwall_support),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Button(onClick = actions.onSignUp, modifier = Modifier.fillMaxWidth()) {
                Text(strings.onboardingSignUp)
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
    }
}
