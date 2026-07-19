package com.kwabor.shared.presentation.auth

enum class PasswordRecoveryStep {
    Email,
    Otp,
    NewPassword,
    Completed,
}

data class PasswordRecoveryUiState(
    val step: PasswordRecoveryStep = PasswordRecoveryStep.Email,
    val email: String = "",
    val resendAvailableAtEpochMilliseconds: Long? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
) {
    fun canResendOtp(nowEpochMilliseconds: Long): Boolean =
        resendAvailableAtEpochMilliseconds?.let { availableAt -> nowEpochMilliseconds >= availableAt } ?: true

    override fun toString(): String = "PasswordRecoveryUiState(step=$step, email=<redacted>, " +
        "resendAvailableAtEpochMilliseconds=$resendAvailableAtEpochMilliseconds, isLoading=$isLoading, " +
        "errorMessage=$errorMessage, noticeMessage=$noticeMessage)"
}

fun initialPasswordRecoveryUiState(): PasswordRecoveryUiState = PasswordRecoveryUiState()
