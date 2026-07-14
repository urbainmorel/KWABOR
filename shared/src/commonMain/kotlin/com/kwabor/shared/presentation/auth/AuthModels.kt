package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AuthSession

enum class AuthStep {
    Email,
    Otp,
}

data class AuthUiState(
    val isVisible: Boolean = false,
    val email: String = "",
    val otpCode: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val legalAccepted: Boolean = false,
    val step: AuthStep = AuthStep.Email,
    val currentSession: AuthSession? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
) {
    val isAuthenticated: Boolean
        get() = currentSession != null
}

fun initialAuthUiState(): AuthUiState = AuthUiState()
