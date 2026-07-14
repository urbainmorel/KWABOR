package com.kwabor.shared.ui.screens.auth

data class AuthSheetActions(
    val onDismiss: () -> Unit,
    val onEmailChange: (String) -> Unit,
    val onFirstNameChange: (String) -> Unit,
    val onLastNameChange: (String) -> Unit,
    val onOtpCodeChange: (String) -> Unit,
    val onLegalAcceptedChange: (Boolean) -> Unit,
    val onRequestOtp: () -> Unit,
    val onVerifyOtp: () -> Unit,
    val onContinueAsGuest: () -> Unit,
)
