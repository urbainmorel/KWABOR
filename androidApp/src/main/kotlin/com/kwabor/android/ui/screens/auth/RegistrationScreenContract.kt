package com.kwabor.android.ui.screens.auth

import com.kwabor.android.presentation.auth.AuthSoftWallContext
import com.kwabor.android.presentation.auth.AuthSurface
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.presentation.auth.RegistrationUiState

internal data class RegistrationScreenState(
    val registration: RegistrationUiState,
    val surface: AuthSurface,
    val softWallContext: AuthSoftWallContext?,
    val otpResendSecondsRemaining: Int,
    val legalDocumentOpenFailed: Boolean,
    val federatedSignInInProgress: Boolean,
)

internal data class RegistrationScreenActions(
    val onBack: () -> Unit,
    val onEmailChange: (String) -> Unit,
    val onRequestOtp: () -> Unit,
    val onSubmitOtp: (String) -> Unit,
    val onResendOtp: () -> Unit,
    val onSubmitPassword: (String) -> Unit,
    val onGoogleSignIn: () -> Unit,
    val onFirstNameChange: (String) -> Unit,
    val onLastNameChange: (String) -> Unit,
    val onRetryRequirements: () -> Unit,
    val onCitySelected: (String) -> Unit,
    val onCurrencySelected: (KwaborCurrency) -> Unit,
    val onLegalAcceptanceChanged: (LegalDocumentType, Boolean) -> Unit,
    val onOpenLegalDocument: (LegalDocumentType) -> Unit,
    val onCompleteProfile: () -> Unit,
)
