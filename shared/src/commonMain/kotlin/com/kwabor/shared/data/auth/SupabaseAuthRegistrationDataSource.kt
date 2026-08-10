package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.i18n.AppLocale
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc

internal class SupabaseAuthRegistrationDataSource(
    private val auth: Auth,
    private val postgrest: Postgrest,
    private val passwordRecoverySessionStore: PasswordRecoverySessionStore,
    private val accountDeletionSessionGuard: AccountDeletionSessionGuard,
) : AuthRegistrationDataSource {
    override suspend fun requestEmailOtp(email: String): Unit = runAuthRequest {
        accountDeletionSessionGuard.ensureCleanupCompleted()
        auth.signInWith(OTP) {
            this.email = email
            createUser = true
        }
    }

    override suspend fun verifyEmailOtp(email: String, otpCode: String): AuthSessionDto = runAuthRequest {
        accountDeletionSessionGuard.ensureCleanupCompleted()
        val session = verifyEmailOtpForSession(email = email, otpCode = otpCode)
        passwordRecoverySessionStore.clearPasswordRecovery()
        session.toDtoWithServerStatus(
            postgrest = postgrest,
            purpose = AuthSessionPurpose.Standard,
        )
    }

    override suspend fun setInitialPassword(password: String): Unit = runAuthRequest {
        accountDeletionSessionGuard.ensureCleanupCompleted()
        auth.updateUser(updateCurrentUser = true) {
            this.password = password
        }
    }

    override suspend fun listActiveLegalDocuments(locale: AppLocale): List<LegalDocumentRevision> = runAuthRequest {
        postgrest.from(LEGAL_DOCUMENTS)
            .select {
                filter {
                    eq("active", true)
                    eq("locale", locale.tag)
                }
                order("document_type", Order.ASCENDING)
            }
            .decodeList<LegalDocumentRevisionDto>()
            .map(LegalDocumentRevisionDto::toDomain)
    }

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): AuthSessionDto = runAuthRequest {
        accountDeletionSessionGuard.ensureCleanupCompleted()
        val completedProfile = postgrest.rpc(
            function = COMPLETE_ONBOARDING_RPC,
            parameters = request.toRpcDto(),
        ).decodeSingle<OnboardingProfileStatusDto>()
        val session = auth.currentSessionOrNull() ?: throw AuthDataException.AuthenticationRequired()
        if (completedProfile.userId != session.user?.id || completedProfile.onboardingCompletedAt == null) {
            throw AuthDataException.Unexpected()
        }
        session.toDto(
            onboardingCompleted = true,
            purpose = AuthSessionPurpose.Standard,
        )
    }

    private suspend fun verifyEmailOtpForSession(email: String, otpCode: String): UserSession =
        when (val result = auth.verifyEmailOtp(OtpType.Email.EMAIL, email = email, token = otpCode)) {
            is OtpVerifyResult.Authenticated -> result.session
            OtpVerifyResult.VerifiedNoSession -> throw AuthDataException.AuthenticationRequired()
        }
}

private const val LEGAL_DOCUMENTS = "legal_documents"
private const val COMPLETE_ONBOARDING_RPC = "complete_user_onboarding"
