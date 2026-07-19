package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession

data class AuthUiState(
    val isVisible: Boolean = false,
    val currentSession: AuthSession? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
) {
    val hasSession: Boolean
        get() = currentSession != null

    val isAuthenticated: Boolean
        get() = currentSession?.accountSetupStatus == AccountSetupStatus.Complete
}

fun initialAuthUiState(): AuthUiState = AuthUiState()
