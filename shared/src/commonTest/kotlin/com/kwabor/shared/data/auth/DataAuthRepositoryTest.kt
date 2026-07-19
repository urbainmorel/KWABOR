package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.CompleteOnboardingValues
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataAuthRepositoryTest {
    @Test
    fun getCurrentSession_mapsIncompleteServerStatusWithoutTokens() = runTest {
        val repository = DataAuthRepository(FakeAuthDataSource(session = authSessionDto(onboardingCompleted = false)))

        val result = repository.getCurrentSession()

        val session = assertIs<DomainResult.Success<AuthSession?>>(result).value
        assertEquals("user-1", session?.userId)
        assertEquals(AccountSetupStatus.OnboardingRequired, session?.accountSetupStatus)
    }

    @Test
    fun verifyEmailOtp_requiresExactlySixDigits() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        listOf("12345", "1234567", "12A456").forEach { otp ->
            val result = repository.verifyEmailOtp("user@kwabor.test", otp)
            assertIs<DomainResult.Failure>(result)
        }

        assertEquals(0, dataSource.emailOtpVerifications)
    }

    @Test
    fun verifyEmailOtp_trimsInputAndReturnsIncompleteSession() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.verifyEmailOtp(" user@kwabor.test ", " 123456 ")

        val session = assertIs<DomainResult.Success<AuthSession>>(result).value
        assertEquals("user@kwabor.test", dataSource.lastVerifiedEmail)
        assertEquals("123456", dataSource.lastOtpCode)
        assertEquals(AccountSetupStatus.OnboardingRequired, session.accountSetupStatus)
    }

    @Test
    fun setInitialPassword_rejectsPasswordShorterThanEightCharacters() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.setInitialPassword("short")

        assertIs<DomainResult.Failure>(result)
        assertEquals(null, dataSource.lastInitialPassword)
    }

    @Test
    fun signInWithEmail_acceptsAnyNonEmptyPasswordAndPreservesWhitespace() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.signInWithEmail(" user@kwabor.test ", " a ")

        assertIs<DomainResult.Success<AuthSession>>(result)
        assertEquals("user@kwabor.test", dataSource.lastSignInEmail)
        assertEquals(" a ", dataSource.lastSignInPassword)
    }

    @Test
    fun signInWithEmail_rejectsOnlyEmptyPasswordLocally() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.signInWithEmail("user@kwabor.test", "")

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals("error.auth.password_required", failure.error.messageKey)
        assertEquals(null, dataSource.lastSignInPassword)
    }

    @Test
    fun passwordRecovery_validatesAndMapsRecoverySessionPurpose() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        assertIs<DomainResult.Success<Unit>>(repository.requestPasswordRecovery(" user@kwabor.test "))
        val verified = repository.verifyPasswordRecoveryOtp(" user@kwabor.test ", " 123456 ")
        assertIs<DomainResult.Success<Unit>>(repository.completePasswordRecovery("new-password"))

        val session = assertIs<DomainResult.Success<AuthSession>>(verified).value
        assertEquals(AuthSessionPurpose.PasswordRecovery, session.purpose)
        assertEquals("user@kwabor.test", dataSource.lastRecoveryEmail)
        assertEquals("123456", dataSource.lastRecoveryOtpCode)
        assertEquals("new-password", dataSource.lastRecoveredPassword)
    }

    @Test
    fun listActiveLegalDocuments_returnsTypedNotFoundWhenEnvironmentHasNone() = runTest {
        val repository = DataAuthRepository(FakeAuthDataSource(legalDocuments = emptyList()))

        val result = repository.listActiveLegalDocuments(AppLocale.French)

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.NotFound>(failure.error)
        assertEquals("error.auth.legal_documents_unavailable", failure.error.messageKey)
    }

    @Test
    fun completeOnboarding_mapsCompletedServerSession() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)
        val request = completeRequest()

        val result = repository.completeOnboarding(request)

        val session = assertIs<DomainResult.Success<AuthSession>>(result).value
        assertEquals(AccountSetupStatus.Complete, session.accountSetupStatus)
        assertEquals(request, dataSource.lastCompleteRequest)
    }

    @Test
    fun signInWithSocialProvider_requiresIdToken() = runTest {
        val repository = DataAuthRepository(FakeAuthDataSource())

        val result = repository.signInWithSocialProvider(
            SocialSignInRequest(provider = SocialAuthProvider.Google, idToken = " "),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
    }

    @Test
    fun activatePromoterInvite_isBlockedUntilServerRpcExists() = runTest {
        val repository = DataAuthRepository(FakeAuthDataSource())

        val result = repository.activatePromoterInvite(
            PromoterActivationRequest(
                inviteToken = "invite-token",
                password = "password123",
                socialSignInRequest = null,
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals("error.auth.promoter_activation_requires_server", failure.error.messageKey)
    }
}

private class FakeAuthDataSource(
    private val session: AuthSessionDto? = null,
    private val legalDocuments: List<LegalDocumentRevision> = legalDocuments(),
) : AuthDataSource {
    var emailOtpVerifications: Int = 0
        private set
    var lastVerifiedEmail: String? = null
        private set
    var lastOtpCode: String? = null
        private set
    var lastInitialPassword: String? = null
        private set
    var lastCompleteRequest: CompleteOnboardingRequest? = null
        private set
    var lastSignInEmail: String? = null
        private set
    var lastSignInPassword: String? = null
        private set
    var lastRecoveryEmail: String? = null
        private set
    var lastRecoveryOtpCode: String? = null
        private set
    var lastRecoveredPassword: String? = null
        private set

    override suspend fun getCurrentSession(): AuthSessionDto? = session

    override suspend fun requestEmailOtp(email: String) = Unit

    override suspend fun verifyEmailOtp(email: String, otpCode: String): AuthSessionDto {
        emailOtpVerifications += 1
        lastVerifiedEmail = email
        lastOtpCode = otpCode
        return authSessionDto(onboardingCompleted = false)
    }

    override suspend fun setInitialPassword(password: String) {
        lastInitialPassword = password
    }

    override suspend fun listActiveLegalDocuments(locale: AppLocale): List<LegalDocumentRevision> = legalDocuments

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): AuthSessionDto {
        lastCompleteRequest = request
        return authSessionDto(onboardingCompleted = true)
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthSessionDto {
        lastSignInEmail = email
        lastSignInPassword = password
        return authSessionDto(onboardingCompleted = true)
    }

    override suspend fun requestPasswordRecovery(email: String) {
        lastRecoveryEmail = email
    }

    override suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): AuthSessionDto {
        lastRecoveryEmail = email
        lastRecoveryOtpCode = otpCode
        return authSessionDto(
            onboardingCompleted = true,
            purpose = AuthSessionPurpose.PasswordRecovery,
        )
    }

    override suspend fun completePasswordRecovery(newPassword: String) {
        lastRecoveredPassword = newPassword
    }

    override suspend fun cancelPasswordRecovery() = Unit

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): AuthSessionDto =
        authSessionDto(onboardingCompleted = true)

    override suspend fun signOut() = Unit
}

private fun authSessionDto(
    onboardingCompleted: Boolean,
    purpose: AuthSessionPurpose = AuthSessionPurpose.Standard,
): AuthSessionDto = AuthSessionDto(
    userId = "user-1",
    email = "user@kwabor.test",
    expiresAtEpochMilliseconds = 1_783_080_000_000,
    onboardingCompleted = onboardingCompleted,
    purpose = purpose,
)

private fun completeRequest(): CompleteOnboardingRequest = assertIs<DomainResult.Success<CompleteOnboardingRequest>>(
    CompleteOnboardingRequest.create(
        CompleteOnboardingValues(
            firstName = "Afi",
            lastName = "Kwabor",
            cityId = "cotonou",
            preferredLocale = AppLocale.French,
            preferredCurrency = KwaborCurrency.Xof,
            termsDocumentId = "terms-id",
            privacyDocumentId = "privacy-id",
            ugcDocumentId = "ugc-id",
        ),
    ),
).value

private fun legalDocuments(): List<LegalDocumentRevision> = LegalDocumentType.entries.mapIndexed { index, type ->
    LegalDocumentRevision(
        id = "document-$index",
        type = type,
        version = "2026-07-15",
        locale = AppLocale.French,
        url = "https://legal.kwabor.test/$index",
        effectiveAtEpochMilliseconds = 1_768_435_200_000,
    )
}
