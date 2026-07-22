package com.kwabor.android.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.looksLikeEmail
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.PasswordRecoveryStep
import com.kwabor.shared.presentation.auth.PasswordRecoveryUiState

internal data class PasswordRecoveryScreenActions(
    val onBack: () -> Unit,
    val onEmailChange: (String) -> Unit,
    val onRequestCode: () -> Unit,
    val onSubmitOtp: (String) -> Unit,
    val onResendCode: () -> Unit,
    val onSubmitPassword: (String, String) -> Unit,
)

internal object PasswordRecoveryScreen {
    @Composable
    operator fun invoke(
        state: PasswordRecoveryUiState,
        resendSecondsRemaining: Int,
        strings: KwaborStrings,
        actions: PasswordRecoveryScreenActions,
    ) {
        BackHandler(onBack = actions.onBack)
        AuthScreenFrame(onBack = actions.onBack) {
            AuthInlineMessage(state.errorMessage ?: state.noticeMessage, state.errorMessage != null)
            when (state.step) {
                PasswordRecoveryStep.Email -> RecoveryEmailStep(state, strings, actions)
                PasswordRecoveryStep.Otp -> key(state.email) {
                    RecoveryOtpStep(state, resendSecondsRemaining, strings, actions)
                }
                PasswordRecoveryStep.NewPassword -> key(state.email) {
                    RecoveryNewPasswordStep(state, strings, actions)
                }
                PasswordRecoveryStep.Completed -> Unit
            }
        }
    }
}

@Composable
private fun RecoveryEmailStep(
    state: PasswordRecoveryUiState,
    strings: KwaborStrings,
    actions: PasswordRecoveryScreenActions,
) {
    AuthHeading(strings.passwordRecoveryTitle, strings.passwordRecoverySubtitle)
    Spacer(Modifier.height(KwaborSpacing.Xl))
    OutlinedTextField(
        value = state.email,
        onValueChange = actions.onEmailChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isLoading,
        singleLine = true,
        label = { Text(strings.authEmail) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = { if (state.email.looksLikeEmail()) actions.onRequestCode() },
        ),
    )
    Spacer(Modifier.height(KwaborSpacing.Lg))
    AuthPrimaryButton(
        label = strings.passwordRecoverySendCode,
        loading = state.isLoading,
        enabled = state.email.looksLikeEmail(),
        onClick = actions.onRequestCode,
    )
}

@Composable
private fun ColumnScope.RecoveryOtpStep(
    state: PasswordRecoveryUiState,
    resendSecondsRemaining: Int,
    strings: KwaborStrings,
    actions: PasswordRecoveryScreenActions,
) {
    var code by remember { mutableStateOf("") }
    AuthHeading(
        title = stringResource(R.string.auth_recovery_otp_title),
        supportingText = stringResource(R.string.auth_recovery_otp_support, state.email),
    )
    Spacer(Modifier.height(KwaborSpacing.Xl))
    AuthOtpInput(code = code, onCodeChange = { updated -> code = updated })
    Spacer(Modifier.height(KwaborSpacing.Lg))
    AuthPrimaryButton(
        label = strings.authVerifyOtp,
        loading = state.isLoading,
        enabled = code.length == OTP_LENGTH,
        onClick = { actions.onSubmitOtp(code) },
    )
    TextButton(
        onClick = actions.onResendCode,
        modifier = Modifier.align(Alignment.CenterHorizontally),
        enabled = resendSecondsRemaining == 0 && !state.isLoading,
    ) {
        Text(
            if (resendSecondsRemaining == 0) {
                strings.passwordRecoveryResendCode
            } else {
                stringResource(R.string.registration_resend_countdown, resendSecondsRemaining)
            },
        )
    }
}

@Composable
private fun RecoveryNewPasswordStep(
    state: PasswordRecoveryUiState,
    strings: KwaborStrings,
    actions: PasswordRecoveryScreenActions,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Lg),
    ) {
        AuthHeading(
            title = stringResource(R.string.auth_recovery_new_password_title),
            supportingText = stringResource(R.string.auth_recovery_new_password_support),
        )
        AuthPasswordField(
            value = password,
            onValueChange = { updated -> password = updated },
            label = strings.passwordRecoveryNewPassword,
            enabled = !state.isLoading,
        )
        AuthPasswordField(
            value = confirmation,
            onValueChange = { updated -> confirmation = updated },
            label = strings.passwordRecoveryConfirmation,
            enabled = !state.isLoading,
            onDone = {
                if (password.length >= MINIMUM_PASSWORD_LENGTH && password == confirmation) {
                    actions.onSubmitPassword(password, confirmation)
                }
            },
        )
        AuthPrimaryButton(
            label = stringResource(R.string.auth_recovery_complete),
            loading = state.isLoading,
            enabled = password.length >= MINIMUM_PASSWORD_LENGTH && password == confirmation,
            onClick = { actions.onSubmitPassword(password, confirmation) },
        )
    }
}

private const val OTP_LENGTH = 6
private const val MINIMUM_PASSWORD_LENGTH = 8
