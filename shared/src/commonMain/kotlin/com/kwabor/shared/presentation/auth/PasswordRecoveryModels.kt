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
    val isEmailStep: Boolean
        get() = step == PasswordRecoveryStep.Email

    val isOtpStep: Boolean
        get() = step == PasswordRecoveryStep.Otp

    val isNewPasswordStep: Boolean
        get() = step == PasswordRecoveryStep.NewPassword

    val isCompletedStep: Boolean
        get() = step == PasswordRecoveryStep.Completed

    fun canResendOtp(nowEpochMilliseconds: Long): Boolean =
        resendAvailableAtEpochMilliseconds?.let { availableAt -> nowEpochMilliseconds >= availableAt } ?: true

    override fun toString(): String = "PasswordRecoveryUiState(step=$step, email=<redacted>, " +
        "resendAvailableAtEpochMilliseconds=$resendAvailableAtEpochMilliseconds, isLoading=$isLoading, " +
        "errorMessage=$errorMessage, noticeMessage=$noticeMessage)"
}

fun initialPasswordRecoveryUiState(): PasswordRecoveryUiState = PasswordRecoveryUiState()
