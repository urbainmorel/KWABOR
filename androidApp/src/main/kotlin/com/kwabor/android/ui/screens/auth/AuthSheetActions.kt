package com.kwabor.android.ui.screens.auth

import com.kwabor.android.presentation.auth.AuthSoftWallContext

internal data class AuthSheetState(
    val context: AuthSoftWallContext?,
    val errorMessage: String?,
    val federatedSignInInProgress: Boolean,
)

internal data class AuthSheetActions(
    val onDismiss: () -> Unit,
    val onGoogleSignIn: () -> Unit,
    val onSignUp: () -> Unit,
    val onSignIn: () -> Unit,
    val onLater: () -> Unit,
)
