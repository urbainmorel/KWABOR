package com.kwabor.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kwabor.android.presentation.auth.AuthAccessUiState
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.auth.PromoterActivationUiState
import com.kwabor.android.ui.screens.auth.PasswordRecoveryScreen
import com.kwabor.android.ui.screens.auth.PromoterActivationScreen
import com.kwabor.android.ui.screens.auth.RegistrationScreen
import com.kwabor.android.ui.screens.auth.RegistrationScreenState
import com.kwabor.android.ui.screens.auth.SessionRestoreFailureScreen
import com.kwabor.android.ui.screens.auth.SignInScreen
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.PasswordRecoveryUiState

@Composable
internal fun RegistrationSurface(
    state: RegistrationScreenState,
    strings: KwaborStrings,
    authViewModel: AuthViewModel,
) {
    RegistrationScreen(
        state = state,
        strings = strings,
        actions = remember(authViewModel) { authViewModel.registrationActions() },
    )
}

@Composable
internal fun SignInSurface(
    state: AuthAccessUiState,
    federatedSignInInProgress: Boolean,
    strings: KwaborStrings,
    authViewModel: AuthViewModel,
) {
    SignInScreen(
        state = state,
        federatedSignInInProgress = federatedSignInInProgress,
        strings = strings,
        actions = remember(authViewModel) { authViewModel.signInActions() },
    )
}

@Composable
internal fun PasswordRecoverySurface(
    state: PasswordRecoveryUiState,
    resendSecondsRemaining: Int,
    strings: KwaborStrings,
    authViewModel: AuthViewModel,
) {
    PasswordRecoveryScreen(
        state = state,
        resendSecondsRemaining = resendSecondsRemaining,
        strings = strings,
        actions = remember(authViewModel) { authViewModel.passwordRecoveryActions() },
    )
}

@Composable
internal fun PromoterActivationSurface(
    state: PromoterActivationUiState,
    strings: KwaborStrings,
    authViewModel: AuthViewModel,
) {
    PromoterActivationScreen(
        state = state,
        strings = strings,
        actions = remember(authViewModel) { authViewModel.promoterActivationActions() },
    )
}

@Composable
internal fun SessionRestoreFailureSurface(strings: KwaborStrings, authViewModel: AuthViewModel) {
    SessionRestoreFailureScreen(
        strings = strings,
        onRetry = { authViewModel.onIntent(AuthIntent.RetrySessionRestore) },
    )
}
