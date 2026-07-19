package com.kwabor.android.ui.screens.auth

internal data class AuthSheetActions(
    val onDismiss: () -> Unit,
    val onSignUp: () -> Unit,
    val onSignIn: () -> Unit,
    val onLater: () -> Unit,
)
