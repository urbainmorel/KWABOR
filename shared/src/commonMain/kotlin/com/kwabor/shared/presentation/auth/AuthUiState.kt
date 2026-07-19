package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionPurpose

data class AuthUiState(
    val isVisible: Boolean = false,
    val currentSession: AuthSession? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
) {
    val hasSession: Boolean
        get() = currentSession != null

    val hasPasswordRecoverySession: Boolean
        get() = currentSession?.purpose == AuthSessionPurpose.PasswordRecovery

    val isAuthenticated: Boolean
        get() = currentSession?.accountSetupStatus == AccountSetupStatus.Complete &&
            currentSession.purpose == AuthSessionPurpose.Standard
}

fun initialAuthUiState(): AuthUiState = AuthUiState()
