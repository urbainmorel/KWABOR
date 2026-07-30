package com.kwabor.android.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.PromoterActivationStage
import com.kwabor.android.presentation.auth.PromoterActivationUiState
import com.kwabor.shared.i18n.KwaborStrings

@Composable
internal fun PromoterActivationScreen(
    state: PromoterActivationUiState,
    strings: KwaborStrings,
    actions: PromoterActivationScreenActions,
) {
    val backEnabled = state.stage != PromoterActivationStage.Activating &&
        state.stage != PromoterActivationStage.Cancelling
    val onBack = if (state.stage == PromoterActivationStage.Completed) actions.onFinish else actions.onBack
    BackHandler { if (backEnabled) onBack() }
    AuthScreenFrame(onBack = onBack, backEnabled = backEnabled) {
        when (state.stage) {
            PromoterActivationStage.Loading -> PromoterActivationLoading()
            PromoterActivationStage.Ready,
            PromoterActivationStage.Activating,
            -> PromoterActivationReady(state, strings, actions)
            PromoterActivationStage.Cancelling -> PromoterActivationClosing()
            PromoterActivationStage.Completed -> PromoterActivationCompleted(state, actions)
            PromoterActivationStage.Error -> PromoterActivationError(state, actions)
        }
    }
}

@Composable
private fun PromoterActivationClosing() {
    CircularProgressIndicator()
    Spacer(Modifier.height(KwaborSpacing.Lg))
    Text(stringResource(R.string.promoter_activation_closing))
}

@Composable
private fun PromoterActivationLoading() {
    CircularProgressIndicator()
    Spacer(Modifier.height(KwaborSpacing.Lg))
    Text(stringResource(R.string.promoter_activation_loading))
}

@Composable
private fun PromoterActivationReady(
    state: PromoterActivationUiState,
    strings: KwaborStrings,
    actions: PromoterActivationScreenActions,
) {
    var password by remember(state.businessName) { mutableStateOf("") }
    val loading = state.stage == PromoterActivationStage.Activating
    AuthHeading(
        title = stringResource(R.string.promoter_activation_title, state.businessName),
        supportingText = stringResource(R.string.promoter_activation_support),
    )
    Spacer(Modifier.height(KwaborSpacing.Lg))
    AuthInlineMessage(state.errorMessage, isError = true)
    AuthPasswordField(
        value = password,
        onValueChange = { password = it },
        label = strings.registrationPassword,
        options = AuthPasswordFieldOptions(enabled = !loading),
    )
    Spacer(Modifier.height(KwaborSpacing.Md))
    AuthPrimaryButton(
        label = stringResource(R.string.promoter_activation_password),
        loading = loading,
        enabled = password.length >= MINIMUM_PASSWORD_LENGTH,
        onClick = { actions.onActivateWithPassword(password) },
    )
    Spacer(Modifier.height(KwaborSpacing.Lg))
    AuthMethodDivider()
    Spacer(Modifier.height(KwaborSpacing.Lg))
    GoogleSignInButton(
        loading = loading,
        enabled = !loading,
        onClick = actions.onActivateWithGoogle,
    )
}

@Composable
private fun PromoterActivationCompleted(state: PromoterActivationUiState, actions: PromoterActivationScreenActions) {
    AuthHeading(
        title = stringResource(R.string.promoter_activation_success_title, state.businessName),
        supportingText = stringResource(R.string.promoter_activation_success_support),
    )
    Spacer(Modifier.height(KwaborSpacing.Xl))
    AuthPrimaryButton(
        label = stringResource(R.string.promoter_activation_home),
        loading = false,
        enabled = true,
        onClick = actions.onFinish,
    )
}

@Composable
private fun PromoterActivationError(state: PromoterActivationUiState, actions: PromoterActivationScreenActions) {
    AuthHeading(
        title = stringResource(R.string.promoter_activation_error_title),
        supportingText = state.errorMessage.orEmpty(),
    )
    Spacer(Modifier.height(KwaborSpacing.Lg))
    if (state.retryAvailable) {
        AuthPrimaryButton(
            label = stringResource(R.string.promoter_activation_retry),
            loading = false,
            enabled = true,
            onClick = actions.onRetryLink,
        )
    }
    TextButton(
        onClick = actions.onBack,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.common_close))
    }
}

private const val MINIMUM_PASSWORD_LENGTH = 8
