package com.kwabor.android.ui.screens.auth

internal data class PromoterActivationScreenActions(
    val onBack: () -> Unit,
    val onRetryLink: () -> Unit,
    val onActivateWithPassword: (String) -> Unit,
    val onActivateWithGoogle: () -> Unit,
    val onFinish: () -> Unit,
)
