package com.kwabor.shared.domain.auth

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale

interface AuthRepository : PasswordRecoveryRepository {
    suspend fun getCurrentSession(): DomainResult<AuthSession?>

    suspend fun requestEmailOtp(email: String): DomainResult<Unit>

    suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession>

    suspend fun setInitialPassword(password: String): DomainResult<Unit>

    suspend fun listActiveLegalDocuments(locale: AppLocale): DomainResult<List<LegalDocumentRevision>>

    suspend fun completeOnboarding(request: CompleteOnboardingRequest): DomainResult<AuthSession>

    suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession>

    suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession>

    suspend fun activatePromoterInvite(request: PromoterActivationRequest): DomainResult<AuthSession>

    suspend fun signOut(): DomainResult<Unit>
}

interface PasswordRecoveryRepository {
    suspend fun requestPasswordRecovery(email: String): DomainResult<Unit>

    suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): DomainResult<AuthSession>

    suspend fun completePasswordRecovery(newPassword: String): DomainResult<Unit>

    suspend fun cancelPasswordRecovery(): DomainResult<Unit>
}
