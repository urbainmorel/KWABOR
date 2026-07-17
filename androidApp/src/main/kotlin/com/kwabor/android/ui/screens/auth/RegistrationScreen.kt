package com.kwabor.android.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.kwabor.android.R
import com.kwabor.android.design.KwaborSizing
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.android.presentation.auth.AuthSurface
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.RegistrationStep
import com.kwabor.shared.presentation.auth.RegistrationUiState

@Composable
internal fun RegistrationScreen(
    state: RegistrationScreenState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
) {
    val registration = state.registration
    BackHandler(enabled = registration.step != RegistrationStep.NotificationPriming) { actions.onBack() }
    Scaffold(
        topBar = {
            RegistrationTopBar(
                title = if (state.surface == AuthSurface.SignIn) strings.authTitle else strings.registrationTitle,
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
        RegistrationProgress(step = registration.step)
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
    if (!state.requiresRequirementsRetry()) return
    OutlinedButton(
        onClick = actions.onRetryRequirements,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KwaborSpacing.Xxl),
        enabled = !state.isLoading,
    ) {
        Text(strings.retry)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistrationTopBar(title: String, step: RegistrationStep, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (step != RegistrationStep.NotificationPriming && step != RegistrationStep.Completed) {
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
private fun RegistrationProgress(step: RegistrationStep) {
    if (step == RegistrationStep.Completed) return
    val progress = step.progressPosition()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KwaborSpacing.Xxl, vertical = KwaborSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Xs),
    ) {
        Text(
            text = stringResource(R.string.registration_step_progress, progress, REGISTRATION_PROGRESS_STEPS),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        LinearProgressIndicator(
            progress = { progress.toFloat() / REGISTRATION_PROGRESS_STEPS.toFloat() },
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
private fun RegistrationStepContent(
    screenState: RegistrationScreenState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    val state = screenState.registration
    when (state.step) {
        RegistrationStep.Email -> EmailStep(state, strings, actions, modifier)
        RegistrationStep.Otp -> OtpStep(state, screenState.otpResendSecondsRemaining, strings, actions, modifier)
        RegistrationStep.Password -> PasswordStep(state, strings, actions, modifier)
        RegistrationStep.Identity -> IdentityStep(state, strings, actions, modifier)
        RegistrationStep.City -> CityStep(screenState, strings, actions, modifier)
        RegistrationStep.Currency -> CurrencyStep(state, strings, actions, modifier)
        RegistrationStep.Legal -> LegalStep(state, strings, actions, modifier)
        RegistrationStep.Observability -> ObservabilityStep(
            state,
            strings,
            actions,
            screenState.observabilityConsentPersistenceFailed,
            modifier,
        )
        RegistrationStep.NotificationPriming -> NotificationPrimingStep(
            screenState = screenState,
            strings = strings,
            actions = actions,
            modifier = modifier,
        )
        RegistrationStep.Completed -> CompletedStep(strings, modifier)
    }
}

@Composable
private fun LegalStep(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    RegistrationScrollableColumn(modifier) {
        StepHeading(strings.registrationLegalTitle, stringResource(R.string.registration_legal_support))
        LegalAcceptanceRow(
            document = state.termsDocument,
            accepted = state.termsAccepted,
            label = strings.registrationTermsAcceptance,
            type = LegalDocumentType.Terms,
            actions = actions,
        )
        LegalAcceptanceRow(
            document = state.privacyDocument,
            accepted = state.privacyAccepted,
            label = strings.registrationPrivacyAcceptance,
            type = LegalDocumentType.PrivacyPolicy,
            actions = actions,
        )
        LegalAcceptanceRow(
            document = state.ugcDocument,
            accepted = state.ugcAccepted,
            label = strings.registrationUgcAcceptance,
            type = LegalDocumentType.UgcLicense,
            actions = actions,
        )
        ContinueButton(
            label = strings.registrationContinue,
            loading = state.isLoading,
            enabled = state.termsAccepted && state.privacyAccepted && state.ugcAccepted &&
                state.termsDocument != null && state.privacyDocument != null && state.ugcDocument != null,
            onClick = actions.onContinueFromLegal,
        )
    }
}

@Composable
private fun LegalAcceptanceRow(
    document: LegalDocumentRevision?,
    accepted: Boolean,
    label: String,
    type: LegalDocumentType,
    actions: RegistrationScreenActions,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = accepted,
                onCheckedChange = { updated -> actions.onLegalAcceptanceChanged(type, updated) },
                enabled = document != null,
            )
            Text(label, modifier = Modifier.weight(1f))
        }
        document?.let { revision ->
            TextButton(
                onClick = { actions.onOpenLegalDocument(type) },
                modifier = Modifier.padding(start = KwaborSizing.TouchTarget),
            ) {
                Text(stringResource(R.string.registration_read_document, revision.version))
            }
        }
    }
}

@Composable
private fun ObservabilityStep(
    state: RegistrationUiState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    persistenceFailed: Boolean,
    modifier: Modifier,
) {
    RegistrationScrollableColumn(modifier) {
        StepHeading(
            strings.registrationObservabilityTitle,
            stringResource(R.string.registration_observability_support),
        )
        ConsentSwitchRow(
            label = strings.registrationAnalyticsConsent,
            checked = state.observabilityConsent.analyticsAllowed,
            onCheckedChange = actions.onAnalyticsConsentChanged,
        )
        ConsentSwitchRow(
            label = strings.registrationDiagnosticsConsent,
            checked = state.observabilityConsent.diagnosticsAllowed,
            onCheckedChange = actions.onDiagnosticsConsentChanged,
        )
        ConsentSwitchRow(
            label = strings.registrationRemoteConfigConsent,
            checked = state.observabilityConsent.remoteConfigurationAllowed,
            onCheckedChange = actions.onRemoteConfigurationConsentChanged,
        )
        if (persistenceFailed) {
            Text(
                text = stringResource(R.string.registration_observability_local_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ContinueButton(
            label = strings.registrationContinue,
            loading = state.isLoading,
            enabled = true,
            onClick = actions.onCompleteOnboarding,
        )
    }
}

@Composable
private fun ConsentSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = KwaborSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NotificationPrimingStep(
    screenState: RegistrationScreenState,
    strings: KwaborStrings,
    actions: RegistrationScreenActions,
    modifier: Modifier,
) {
    RegistrationScrollableColumn(modifier, verticalArrangement = Arrangement.Center) {
        StepHeading(
            title = strings.registrationNotificationTitle,
            supportingText = strings.registrationNotificationSupport,
            textAlign = TextAlign.Center,
        )
        if (screenState.notificationPrimingPersistenceFailed) {
            Text(
                text = stringResource(R.string.registration_notification_local_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        ContinueButton(
            label = strings.registrationNotificationEnable,
            loading = screenState.registration.isLoading || screenState.notificationPermissionRequestInFlight,
            enabled = !screenState.notificationPermissionRequestInFlight,
            onClick = actions.onEnableNotifications,
        )
        TextButton(
            onClick = actions.onSkipNotifications,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !screenState.notificationPermissionRequestInFlight,
        ) {
            Text(strings.registrationLater)
        }
    }
}

@Composable
private fun CompletedStep(strings: KwaborStrings, modifier: Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(KwaborSpacing.Lg))
        Text(strings.registrationComplete)
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

private fun RegistrationStep.progressPosition(): Int =
    if (this == RegistrationStep.Completed) REGISTRATION_PROGRESS_STEPS else ordinal + 1

private fun RegistrationUiState.requiresRequirementsRetry(): Boolean = step in REGISTRATION_REQUIREMENTS_STEPS &&
    !isLoading &&
    (cities.isEmpty() || termsDocument == null || privacyDocument == null || ugcDocument == null)

private const val REGISTRATION_PROGRESS_STEPS = 9
private val REGISTRATION_REQUIREMENTS_STEPS = setOf(
    RegistrationStep.Identity,
    RegistrationStep.City,
    RegistrationStep.Currency,
    RegistrationStep.Legal,
    RegistrationStep.Observability,
)
