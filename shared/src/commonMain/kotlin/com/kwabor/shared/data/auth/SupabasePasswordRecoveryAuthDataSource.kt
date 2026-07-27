package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_PASSWORD_RECOVERY_REQUIRED_ERROR_KEY
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest

internal class SupabasePasswordRecoveryAuthDataSource(
    private val auth: Auth,
    private val postgrest: Postgrest,
    passwordRecoverySessionStore: PasswordRecoverySessionStore,
) : PasswordRecoveryAuthDataSource {
    private val passwordRecoverySessionCoordinator =
        PasswordRecoverySessionCoordinator(passwordRecoverySessionStore)

    override suspend fun requestPasswordRecovery(email: String): Unit = runAuthRequest {
        try {
            auth.resetPasswordForEmail(email = email, redirectUrl = null)
        } catch (exception: AuthRestException) {
            if (!exception.isUnknownAccountError()) throw exception
        }
    }

    override suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): AuthSessionDto = runAuthRequest {
        val session = passwordRecoverySessionCoordinator.establishRecoverySession(
            clearCurrentSession = auth::clearSession,
        ) {
            verifyPasswordRecoveryOtpForSession(email = email, otpCode = otpCode)
        }
        session.toDtoWithServerStatus(
            postgrest = postgrest,
            purpose = AuthSessionPurpose.PasswordRecovery,
        )
    }

    override suspend fun completePasswordRecovery(newPassword: String): Unit = runAuthRequest {
        passwordRecoverySessionCoordinator.completeRecoverySession(
            hasCurrentSession = { auth.currentSessionOrNull() != null },
            missingSessionError = {
                AuthDataException.AuthenticationRequired(AUTH_PASSWORD_RECOVERY_REQUIRED_ERROR_KEY)
            },
            updatePassword = {
                auth.updateUser(updateCurrentUser = true, redirectUrl = null) {
                    password = newPassword
                }
            },
            clearCurrentSession = auth::clearSession,
        )
    }

    override suspend fun cancelPasswordRecovery(): Unit = runAuthRequest {
        passwordRecoverySessionCoordinator.cancelRecoverySession(auth::clearSession)
    }

    private suspend fun verifyPasswordRecoveryOtpForSession(email: String, otpCode: String): UserSession =
        when (val result = auth.verifyEmailOtp(OtpType.Email.RECOVERY, email = email, token = otpCode)) {
            is OtpVerifyResult.Authenticated -> result.session
            OtpVerifyResult.VerifiedNoSession -> throw AuthDataException.AuthenticationRequired()
        }
}
