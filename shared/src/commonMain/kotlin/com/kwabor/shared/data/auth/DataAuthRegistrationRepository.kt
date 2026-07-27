package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthRegistrationRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale

internal class DataAuthRegistrationRepository(
    private val dataSource: AuthRegistrationDataSource,
) : AuthRegistrationRepository {
    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> = runAuthCall {
        requireValidEmail(email)
        dataSource.requestEmailOtp(email.trim())
    }

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession> = runAuthCall {
        requireValidEmail(email)
        requireOtpCode(otpCode)
        dataSource.verifyEmailOtp(email = email.trim(), otpCode = otpCode.trim()).toDomain()
    }

    override suspend fun setInitialPassword(password: String): DomainResult<Unit> = runAuthCall {
        requirePassword(password)
        dataSource.setInitialPassword(password)
    }

    override suspend fun listActiveLegalDocuments(locale: AppLocale): DomainResult<List<LegalDocumentRevision>> =
        runAuthCall {
            val revisions = dataSource.listActiveLegalDocuments(locale)
            if (revisions.isEmpty()) {
                throw AuthDataException.LegalDocumentsUnavailable()
            }
            revisions
        }

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): DomainResult<AuthSession> =
        runAuthCall {
            dataSource.completeOnboarding(request).toDomain()
        }
}
