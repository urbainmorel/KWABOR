package com.kwabor.android.ui.screens.auth

import com.kwabor.android.presentation.auth.AuthSurface
import com.kwabor.android.presentation.auth.RegistrationLocationStatus
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.presentation.auth.RegistrationUiState

internal data class RegistrationScreenState(
    val registration: RegistrationUiState,
    val surface: AuthSurface,
    val locationStatus: RegistrationLocationStatus,
    val locationPermissionRequestInFlight: Boolean,
    val otpResendSecondsRemaining: Int,
    val legalDocumentOpenFailed: Boolean,
    val observabilityConsentPersistenceFailed: Boolean,
    val notificationPermissionRequestInFlight: Boolean,
    val notificationPrimingPersistenceFailed: Boolean,
)

internal data class RegistrationScreenActions(
    val onBack: () -> Unit,
    val onEmailChange: (String) -> Unit,
    val onRequestOtp: () -> Unit,
    val onSubmitOtp: (String) -> Unit,
    val onResendOtp: () -> Unit,
    val onSubmitPassword: (String, String) -> Unit,
    val onFirstNameChange: (String) -> Unit,
    val onLastNameChange: (String) -> Unit,
    val onContinueFromIdentity: () -> Unit,
    val onRetryRequirements: () -> Unit,
    val onCitySelected: (String) -> Unit,
    val onUseLocation: () -> Unit,
    val onContinueFromCity: () -> Unit,
    val onCurrencySelected: (KwaborCurrency) -> Unit,
    val onContinueFromCurrency: () -> Unit,
    val onLegalAcceptanceChanged: (LegalDocumentType, Boolean) -> Unit,
    val onOpenLegalDocument: (LegalDocumentType) -> Unit,
    val onContinueFromLegal: () -> Unit,
    val onAnalyticsConsentChanged: (Boolean) -> Unit,
    val onDiagnosticsConsentChanged: (Boolean) -> Unit,
    val onRemoteConfigurationConsentChanged: (Boolean) -> Unit,
    val onCompleteOnboarding: () -> Unit,
    val onEnableNotifications: () -> Unit,
    val onSkipNotifications: () -> Unit,
)
