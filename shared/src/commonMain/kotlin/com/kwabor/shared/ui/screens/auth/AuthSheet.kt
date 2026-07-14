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
fun AuthSheet(state: AuthUiState, strings: KwaborStrings, actions: AuthSheetActions, modifier: Modifier = Modifier) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss) {
        AuthSheetContent(state = state, strings = strings, actions = actions, modifier = modifier)
    }
}

@Composable
private fun AuthSheetContent(
    state: AuthUiState,
    strings: KwaborStrings,
    actions: AuthSheetActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        AuthSheetHeader(strings)
        AuthMessages(state)
        AuthEmailField(state = state, strings = strings, onEmailChange = actions.onEmailChange)
        if (state.step == AuthStep.Otp) {
            AuthOtpFields(state = state, strings = strings, actions = actions)
        }
        AuthSubmitButton(state = state, strings = strings, actions = actions)
        TextButton(
            onClick = actions.onContinueAsGuest,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !state.isLoading,
        ) {
            Text(text = strings.authContinueAsGuest)
        }
    }
}

@Composable
private fun AuthSheetHeader(strings: KwaborStrings) {
    Text(text = strings.authTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        text = strings.authSubtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun AuthMessages(state: AuthUiState) {
    state.noticeMessage?.let { message ->
        Text(text = message, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    state.errorMessage?.let { message ->
        Text(text = message, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun AuthEmailField(state: AuthUiState, strings: KwaborStrings, onEmailChange: (String) -> Unit) {
    OutlinedTextField(
        value = state.email,
        onValueChange = onEmailChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isLoading,
        singleLine = true,
        label = { Text(text = strings.authEmail) },
    )
}

@Composable
private fun AuthOtpFields(state: AuthUiState, strings: KwaborStrings, actions: AuthSheetActions) {
    AuthOtpTextFields(state = state, strings = strings, actions = actions)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KwaborSpacing.Sm),
    ) {
        Checkbox(
            checked = state.legalAccepted,
            onCheckedChange = actions.onLegalAcceptedChange,
            enabled = !state.isLoading,
        )
        Text(
            text = strings.authLegalAcceptance,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AuthOtpTextFields(state: AuthUiState, strings: KwaborStrings, actions: AuthSheetActions) {
    AuthTextField(state.firstName, strings.authFirstName, !state.isLoading, actions.onFirstNameChange)
    AuthTextField(state.lastName, strings.authLastName, !state.isLoading, actions.onLastNameChange)
    AuthTextField(state.otpCode, strings.authOtpCode, !state.isLoading, actions.onOtpCodeChange)
}

@Composable
private fun AuthTextField(value: String, label: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(text = label) },
    )
}

@Composable
private fun AuthSubmitButton(state: AuthUiState, strings: KwaborStrings, actions: AuthSheetActions) {
    val action = when (state.step) {
        AuthStep.Email -> actions.onRequestOtp
        AuthStep.Otp -> actions.onVerifyOtp
    }
    val label = when (state.step) {
        AuthStep.Email -> strings.authRequestOtp
        AuthStep.Otp -> strings.authVerifyOtp
    }
    Button(onClick = action, modifier = Modifier.fillMaxWidth(), enabled = !state.isLoading) {
        Text(text = label)
    }
}
