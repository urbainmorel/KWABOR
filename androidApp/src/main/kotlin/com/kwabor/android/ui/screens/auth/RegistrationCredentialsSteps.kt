package com.kwabor.android.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.kwabor.android.R
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSizing
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.RegistrationUiState

@Composable
internal fun EmailStep(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    RegistrationScrollableColumn(modifier) {
        StepHeading(
            title = stringResource(R.string.registration_email_title),
            supportingText = stringResource(R.string.registration_email_support),
        )
        OutlinedTextField(
            value = state.email,
            onValueChange = actions.onEmailChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
            singleLine = true,
            label = { Text(strings.authEmail) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.email.looksLikeEmail()) actions.onRequestOtp() }),
        )
        ContinueButton(
            label = strings.registrationContinue,
            loading = state.isLoading,
            enabled = state.email.looksLikeEmail(),
            onClick = actions.onRequestOtp,
        )
    }
}

@Composable
internal fun OtpStep(
    state: RegistrationUiState,
    resendSeconds: Int,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    var code by remember(state.email) { mutableStateOf("") }
    RegistrationScrollableColumn(modifier) {
        StepHeading(
            title = stringResource(R.string.registration_otp_title),
            supportingText = stringResource(R.string.registration_otp_support, state.email),
        )
        OtpInput(code = code, onCodeChange = { updated -> code = updated })
        ContinueButton(
            label = strings.authVerifyOtp,
            loading = state.isLoading,
            enabled = code.length == OTP_LENGTH,
            onClick = { actions.onSubmitOtp(code) },
        )
        TextButton(
            onClick = actions.onResendOtp,
            enabled = resendSeconds == 0 && !state.isLoading,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                if (resendSeconds == 0) {
                    stringResource(R.string.registration_resend)
                } else {
                    stringResource(R.string.registration_resend_countdown, resendSeconds)
                },
            )
        }
    }
}

@Composable
private fun OtpInput(code: String, onCodeChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val accessibilityLabel = stringResource(R.string.registration_otp_accessibility)
    val accessibilityState = stringResource(R.string.registration_otp_digits_entered, code.length)
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    BasicTextField(
        value = code,
        onValueChange = { updated -> onCodeChange(updated.filter(Char::isDigit).take(OTP_LENGTH)) },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .semantics {
                contentDescription = accessibilityLabel
                stateDescription = accessibilityState
            },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.surface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.surface),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    repeat(OTP_LENGTH) { index ->
                        OtpDigitBox(code.getOrNull(index)?.toString().orEmpty())
                    }
                }
                Box(modifier = Modifier.size(KwaborSizing.TouchTarget).alpha(0f)) { innerTextField() }
            }
        },
    )
}

@Composable
private fun OtpDigitBox(digit: String) {
    Card(
        modifier = Modifier.size(KwaborSizing.TouchTarget),
        border = BorderStroke(KwaborSizing.Hairline, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(KwaborRadius.Control),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(digit, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
internal fun PasswordStep(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    RegistrationScrollableColumn(modifier) {
        StepHeading(strings.registrationPassword, stringResource(R.string.registration_password_support))
        PasswordField(
            state = PasswordFieldState(password, strings.registrationPassword, passwordVisible, !state.isLoading),
            onValueChange = { updated -> password = updated },
            onVisibilityChange = { passwordVisible = !passwordVisible },
        )
        PasswordField(
            state = PasswordFieldState(
                confirmation,
                strings.registrationPasswordConfirmation,
                passwordVisible,
                !state.isLoading,
            ),
            onValueChange = { updated -> confirmation = updated },
            onVisibilityChange = { passwordVisible = !passwordVisible },
        )
        ContinueButton(
            label = strings.registrationContinue,
            loading = state.isLoading,
            enabled = password.length >= MINIMUM_PASSWORD_LENGTH && password == confirmation,
            onClick = { actions.onSubmitPassword(password, confirmation) },
        )
    }
}

private data class PasswordFieldState(
    val value: String,
    val label: String,
    val visible: Boolean,
    val enabled: Boolean,
)

@Composable
private fun PasswordField(state: PasswordFieldState, onValueChange: (String) -> Unit, onVisibilityChange: () -> Unit) {
    OutlinedTextField(
        value = state.value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.enabled,
        singleLine = true,
        label = { Text(state.label) },
        visualTransformation = if (state.visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onVisibilityChange) {
                Icon(
                    imageVector = if (state.visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (state.visible) R.string.registration_password_hide else R.string.registration_password_show,
                    ),
                )
            }
        },
    )
}

@Composable
internal fun IdentityStep(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    RegistrationScrollableColumn(modifier) {
        StepHeading(strings.registrationIdentityTitle, stringResource(R.string.registration_identity_support))
        OutlinedTextField(
            value = state.firstName,
            onValueChange = actions.onFirstNameChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
            singleLine = true,
            label = { Text(strings.authFirstName) },
        )
        OutlinedTextField(
            value = state.lastName,
            onValueChange = actions.onLastNameChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
            singleLine = true,
            label = { Text(strings.authLastName) },
        )
        ContinueButton(
            label = strings.registrationContinue,
            loading = state.isLoading,
            enabled = state.firstName.isNotBlank() && state.lastName.isNotBlank(),
            onClick = actions.onContinueFromIdentity,
        )
    }
}

private fun String.looksLikeEmail(): Boolean = isNotBlank() && '@' in this && none(Char::isWhitespace)

private const val OTP_LENGTH = 6
private const val MINIMUM_PASSWORD_LENGTH = 8
