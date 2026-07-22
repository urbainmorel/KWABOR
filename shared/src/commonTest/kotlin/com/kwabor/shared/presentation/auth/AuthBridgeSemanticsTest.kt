package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthBridgeSemanticsTest {
    @Test
    fun passwordRecoveryStateExposesStableStepSemantics() {
        val email = PasswordRecoveryUiState(step = PasswordRecoveryStep.Email)
        val otp = PasswordRecoveryUiState(step = PasswordRecoveryStep.Otp)
        val newPassword = PasswordRecoveryUiState(step = PasswordRecoveryStep.NewPassword)
        val completed = PasswordRecoveryUiState(step = PasswordRecoveryStep.Completed)

        assertTrue(email.isEmailStep)
        assertTrue(otp.isOtpStep)
        assertTrue(newPassword.isNewPasswordStep)
        assertTrue(completed.isCompletedStep)
        assertFalse(email.isCompletedStep)
        assertFalse(completed.isEmailStep)
    }

    @Test
    fun authSessionExposesStableAccountSetupSemantics() {
        val onboarding = authSession(AccountSetupStatus.OnboardingRequired)
        val complete = authSession(AccountSetupStatus.Complete)

        assertTrue(onboarding.requiresAccountSetup)
        assertFalse(onboarding.isAccountSetupComplete)
        assertFalse(complete.requiresAccountSetup)
        assertTrue(complete.isAccountSetupComplete)
    }
}

private fun authSession(status: AccountSetupStatus): AuthSession = AuthSession(
    userId = "user-1",
    email = "user@kwabor.test",
    expiresAtEpochMilliseconds = 1_783_080_000_000,
    accountSetupStatus = status,
)
