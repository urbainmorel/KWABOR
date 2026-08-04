package com.kwabor.android.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.RegistrationMethod
import com.kwabor.shared.presentation.auth.RegistrationRequirementsStatus
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.RegistrationUiState

@Composable
internal fun RegistrationScreen(
    state: RegistrationScreenState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
) {
    val registration = state.registration
    BackHandler(enabled = registration.step != RegistrationStep.Completed) { actions.onBack() }
    Scaffold(
        topBar = {
            RegistrationTopBar(
                title = strings.registrationTitle,
                step = registration.step,
                onBack = actions.onBack,
            )
        },
    ) { paddingValues ->
        RegistrationBody(
            state = state,
            strings = strings,
            actions = actions,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

@Composable
private fun RegistrationBody(
    state: RegistrationScreenState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    val registration = state.registration
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        RegistrationProgress(state = registration)
        RegistrationMessages(state = registration)
        RegistrationLegalOpenError(visible = state.legalDocumentOpenFailed)
        RegistrationRequirementsRetry(state = registration, strings = strings, actions = actions)
        RegistrationStepContent(
            screenState = state,
            strings = strings,
            actions = actions,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = KwaborSpacing.Xxl),
        )
    }
}

@Composable
private fun RegistrationProgress(state: RegistrationUiState) {
    val progress = state.progress ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
    ) {
        Text(
            text = if (state.method == RegistrationMethod.Federated) {
                stringResource(R.string.registration_final_step)
            } else {
                stringResource(R.string.registration_step_progress, progress.current, progress.total)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        LinearProgressIndicator(
            progress = { progress.current.toFloat() / progress.total.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RegistrationMessages(state: RegistrationUiState) {
    val message = state.errorMessage ?: state.noticeMessage ?: return
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = if (state.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun RegistrationLegalOpenError(visible: Boolean) {
    if (!visible) return
    Text(
        text = stringResource(R.string.registration_legal_open_failed),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun RegistrationRequirementsRetry(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
) {
    if (
        state.step != RegistrationStep.Profile ||
        state.requirementsStatus != RegistrationRequirementsStatus.Failed
    ) {
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KwaborSpacing.Xxl),
    ) {
        state.requirementsErrorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(
            onClick = actions.onRetryRequirements,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.retry)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistrationTopBar(title: String, step: RegistrationStep, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (step != RegistrationStep.Completed) {
                IconButton(onClick = onBack, modifier = Modifier.size(KwaborSizing.TouchTarget)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.registration_back_accessibility),
                    )
                }
            }
        },
    )
}

@Composable
private fun RegistrationStepContent(
    screenState: RegistrationScreenState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    when (screenState.registration.step) {
        RegistrationStep.Email -> EmailStep(
            state = screenState.registration,
            federatedSignInInProgress = screenState.federatedSignInInProgress,
            strings = strings,
            actions = actions,
            modifier = modifier,
        )
        RegistrationStep.Otp -> OtpStep(
            state = screenState.registration,
            resendSeconds = screenState.otpResendSecondsRemaining,
            strings = strings,
            actions = actions,
            modifier = modifier,
        )
        RegistrationStep.Password -> PasswordStep(screenState.registration, strings, actions, modifier)
        RegistrationStep.Profile -> ProfileStep(screenState, strings, actions, modifier)
        RegistrationStep.Completed -> Spacer(modifier = modifier)
    }
}

@Composable
internal fun RegistrationScrollableColumn(
    modifier: Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(KwaborSpacing.Lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
internal fun StepHeading(title: String, supportingText: String, textAlign: TextAlign = TextAlign.Start) {
    Spacer(Modifier.height(KwaborSpacing.Md))
    Text(
        text = title,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = textAlign,
    )
    Text(
        text = supportingText,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
        textAlign = textAlign,
    )
}

@Composable
internal fun ContinueButton(label: String, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && !loading,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(KwaborSpacing.Xl),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = KwaborSizing.Hairline,
            )
            Spacer(Modifier.width(KwaborSpacing.Sm))
        }
        Text(label)
    }
}
