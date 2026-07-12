package com.kwabor.shared.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.shared.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.AuthStep
import com.kwabor.shared.presentation.auth.AuthUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthSheet(
    state: AuthUiState,
    strings: KwaborStrings,
    onDismiss: () -> Unit,
    onEmailChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onOtpCodeChange: (String) -> Unit,
    onLegalAcceptedChange: (Boolean) -> Unit,
    onRequestOtp: () -> Unit,
    onVerifyOtp: () -> Unit,
    onContinueAsGuest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
        ) {
            Text(
                text = strings.authTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.authSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            state.noticeMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
                singleLine = true,
                label = { Text(text = strings.authEmail) },
            )

            if (state.step == AuthStep.Otp) {
                OutlinedTextField(
                    value = state.firstName,
                    onValueChange = onFirstNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                    singleLine = true,
                    label = { Text(text = strings.authFirstName) },
                )
                OutlinedTextField(
                    value = state.lastName,
                    onValueChange = onLastNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                    singleLine = true,
                    label = { Text(text = strings.authLastName) },
                )
                OutlinedTextField(
                    value = state.otpCode,
                    onValueChange = onOtpCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                    singleLine = true,
                    label = { Text(text = strings.authOtpCode) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
                ) {
                    Checkbox(
                        checked = state.legalAccepted,
                        onCheckedChange = onLegalAcceptedChange,
                        enabled = !state.isLoading,
                    )
                    Text(
                        text = strings.authLegalAcceptance,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = if (state.step == AuthStep.Email) onRequestOtp else onVerifyOtp,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            ) {
                Text(text = if (state.step == AuthStep.Email) strings.authRequestOtp else strings.authVerifyOtp)
            }

            TextButton(
                onClick = onContinueAsGuest,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enabled = !state.isLoading,
            ) {
                Text(text = strings.authContinueAsGuest)
            }
        }
    }
}
