package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_INVALID_CREDENTIALS_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionOutcome
import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCancellation
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.CompleteOnboardingValues
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.PromoterActivationSessionProof
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val dataSource = FakeAuthDataSource().apply { activeLegalDocuments = emptyList() }
        val repository = DataAuthRepository(dataSource)

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
            SocialSignInRequest(
                provider = SocialAuthProvider.Google,
                idToken = " ",
                rawNonce = VALID_NONCE,
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertIs<DomainError.Validation>(failure.error)
    }

    @Test
    fun signInWithSocialProvider_requiresRawNonce() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.signInWithSocialProvider(
            SocialSignInRequest(
                provider = SocialAuthProvider.Google,
                idToken = VALID_ID_TOKEN,
                rawNonce = " ",
            ),
        )

        assertIs<DomainResult.Failure>(result)
        assertEquals(0, dataSource.socialSignIns)
    }

    @Test
    fun handlePromoterActivationCallback_rejectsMalformedInputBeforeSessionMutation() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.handlePromoterActivationCallback(
            "kwabor://auth/promoter-activate?token=invalid&unexpected=value",
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals(AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY, failure.error.messageKey)
        assertNull(dataSource.lastActivationSessionProof)
    }

    @Test
    fun handlePromoterActivationCallback_establishesPkceSessionBeforePreview() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.handlePromoterActivationCallback(
            "kwabor://auth/promoter-activate?token=$VALID_INVITE_TOKEN&code=$VALID_PKCE_CODE",
        )

        val context = assertIs<DomainResult.Success<PromoterActivationContext>>(result).value
        val proof = assertIs<PromoterActivationSessionProof.PkceCode>(
            dataSource.lastActivationSessionProof,
        )
        assertEquals(VALID_PKCE_CODE, proof.code)
        assertEquals(VALID_INVITE_TOKEN, context.inviteToken)
        assertTrue(context.sessionImportedForActivation)
        assertEquals(0, dataSource.temporarySessionDiscards)
    }

    @Test
    fun handlePromoterActivationCallback_neverReplacesOrClearsPreexistingSession() = runTest {
        val dataSource = FakeAuthDataSource(
            session = authSessionDto(onboardingCompleted = true),
            previewException = AuthDataException.Validation(AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY),
        )
        val repository = DataAuthRepository(dataSource)

        val result = repository.handlePromoterActivationCallback(
            "kwabor://auth/promoter-activate?token=$VALID_INVITE_TOKEN&code=$VALID_PKCE_CODE",
        )

        assertIs<DomainResult.Failure>(result)
        assertNull(dataSource.lastActivationSessionProof)
        assertEquals(0, dataSource.temporarySessionDiscards)
    }

    @Test
    fun handlePromoterActivationCallback_cleansNewlyImportedSessionWhenPreviewFails() = runTest {
        val dataSource = FakeAuthDataSource(
            previewException = AuthDataException.Validation(AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY),
        )
        val repository = DataAuthRepository(dataSource)

        val result = repository.handlePromoterActivationCallback(
            "kwabor://auth/promoter-activate?token=$VALID_INVITE_TOKEN&code=$VALID_PKCE_CODE",
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals(AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY, failure.error.messageKey)
        assertIs<PromoterActivationSessionProof.PkceCode>(dataSource.lastActivationSessionProof)
        assertEquals(1, dataSource.temporarySessionDiscards)
    }

    @Test
    fun handlePromoterActivationCallback_cleansImportedSessionWhenPreviewIsCancelled() = runTest {
        val dataSource = FakeAuthDataSource(
            previewException = CancellationException("cancelled"),
        )
        val repository = DataAuthRepository(dataSource)

        assertFailsWith<CancellationException> {
            repository.handlePromoterActivationCallback(
                "kwabor://auth/promoter-activate?token=$VALID_INVITE_TOKEN&code=$VALID_PKCE_CODE",
            )
        }

        assertEquals(1, dataSource.temporarySessionDiscards)
    }

    @Test
    fun handlePromoterActivationCallback_marksPreexistingSessionContextAsNotImported() = runTest {
        val dataSource = FakeAuthDataSource(
            session = authSessionDto(onboardingCompleted = true),
        )
        val repository = DataAuthRepository(dataSource)

        val result = repository.handlePromoterActivationCallback(
            "kwabor://auth/promoter-activate?token=$VALID_INVITE_TOKEN",
        )

        val context = assertIs<DomainResult.Success<PromoterActivationContext>>(result).value
        assertFalse(context.sessionImportedForActivation)
        assertNull(dataSource.lastActivationSessionProof)
    }

    @Test
    fun activatePromoterInvite_reauthenticatesSameSocialIdentityBeforeServerActivation() = runTest {
        val dataSource = FakeAuthDataSource(
            session = authSessionDto(onboardingCompleted = true),
            activationException = AuthDataException.Validation(AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY),
        )
        val repository = DataAuthRepository(dataSource)

        val result = repository.activatePromoterInvite(
            PromoterActivationRequest(
                inviteToken = VALID_INVITE_TOKEN,
                password = null,
                socialSignInRequest = validSocialRequest(),
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals(AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY, failure.error.messageKey)
        assertEquals(1, dataSource.socialSignIns)
        assertEquals(0, dataSource.temporarySessionDiscards)
    }

    @Test
    fun activatePromoterInvite_keepsVerifiedSocialSessionAndMarkerWhenServerCallIsCancelled() = runTest {
        val dataSource = FakeAuthDataSource(
            session = authSessionDto(onboardingCompleted = true),
            activationException = CancellationException("cancelled after social authentication"),
        )
        val repository = DataAuthRepository(dataSource)

        assertFailsWith<CancellationException> {
            repository.activatePromoterInvite(
                PromoterActivationRequest(
                    inviteToken = VALID_INVITE_TOKEN,
                    password = null,
                    socialSignInRequest = validSocialRequest(),
                ),
            )
        }

        assertEquals(1, dataSource.socialSignIns)
        assertEquals(0, dataSource.temporarySessionDiscards)
    }

    @Test
    fun activatePromoterInvite_rejectsAndCleansMismatchedSocialIdentity() = runTest {
        val dataSource = FakeAuthDataSource(
            session = authSessionDto(onboardingCompleted = true, userId = "invited-user"),
            promoterSignInSessions = PromoterSignInSessions(
                social = authSessionDto(onboardingCompleted = true, userId = "other-user"),
            ),
        )
        val repository = DataAuthRepository(dataSource)

        val result = repository.activatePromoterInvite(
            PromoterActivationRequest(
                inviteToken = VALID_INVITE_TOKEN,
                password = null,
                socialSignInRequest = validSocialRequest(),
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals(AUTH_INVALID_CREDENTIALS_ERROR_KEY, failure.error.messageKey)
        assertEquals(1, dataSource.temporarySessionDiscards)
    }

    @Test
    fun activatePromoterInvite_reauthenticatesPasswordWithoutReplacingIt() = runTest {
        val dataSource = FakeAuthDataSource(
            session = authSessionDto(onboardingCompleted = true),
            activationException = AuthDataException.Validation(AUTH_PROMOTER_INVITE_INVALID_ERROR_KEY),
        )
        val repository = DataAuthRepository(dataSource)

        val result = repository.activatePromoterInvite(
            PromoterActivationRequest(
                inviteToken = VALID_INVITE_TOKEN,
                password = "password123",
                socialSignInRequest = null,
            ),
        )

        assertIs<DomainResult.Failure>(result)
        assertEquals("user@kwabor.test", dataSource.lastSignInEmail)
        assertEquals("password123", dataSource.lastSignInPassword)
        assertNull(dataSource.lastInitialPassword)
        assertEquals(0, dataSource.temporarySessionDiscards)
    }

    @Test
    fun activatePromoterInvite_rejectsAndCleansMismatchedPasswordIdentity() = runTest {
        val dataSource = FakeAuthDataSource(
            session = authSessionDto(onboardingCompleted = true, userId = "invited-user"),
            promoterSignInSessions = PromoterSignInSessions(
                email = authSessionDto(onboardingCompleted = true, userId = "other-user"),
            ),
        )
        val repository = DataAuthRepository(dataSource)

        val result = repository.activatePromoterInvite(
            PromoterActivationRequest(
                inviteToken = VALID_INVITE_TOKEN,
                password = "password123",
                socialSignInRequest = null,
            ),
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals(AUTH_INVALID_CREDENTIALS_ERROR_KEY, failure.error.messageKey)
        assertEquals(1, dataSource.temporarySessionDiscards)
        assertNull(dataSource.lastInitialPassword)
    }

    @Test
    fun deleteAccount_rejectsInvalidIdempotencyKeyBeforeDataCall() = runTest {
        val dataSource = FakeAuthDataSource()
        val repository = DataAuthRepository(dataSource)

        val result = repository.deleteAccount(
            AccountDeletionRequest(
                expectedAccountId = "user-1",
                idempotencyKey = "not-a-uuid",
                credential = AccountDeletionCredential.Password("password123"),
            ),
        )

        assertIs<DomainResult.Failure>(result)
        assertNull(dataSource.lastDeletionRequest)
    }

    @Test
    fun deleteAccount_typesRawDataSourceCancellationAsPreTransport() = runTest {
        val repository = DataAuthRepository(
            FakeAuthDataSource(deletionException = CancellationException("cancelled before data boundary")),
        )

        assertFailsWith<AccountDeletionPreTransportCancellation> {
            repository.deleteAccount(validDeletionRequest())
        }
    }

    @Test
    fun deleteAccount_preservesOutcomeUnknownCancellationFromDataSource() = runTest {
        val expected = AccountDeletionOutcomeUnknownCancellation(CancellationException("cancelled after invoke"))
        val repository = DataAuthRepository(FakeAuthDataSource(deletionException = expected))

        val actual = assertFailsWith<AccountDeletionOutcomeUnknownCancellation> {
            repository.deleteAccount(validDeletionRequest())
        }

        assertEquals(expected, actual)
    }

    @Test
    fun deleteAccount_mapsExplicitRejectionWhoseCleanupRemainsPending() = runTest {
        val rejection = AuthDataException.Validation("error.auth.account_deletion_reauthentication_failed")
        val dataSource = FakeAuthDataSource().apply {
            deletionOutcome = AccountDeletionDataOutcome.RejectedCleanupPending(rejection)
        }
        val repository = DataAuthRepository(dataSource)

        val outcome = assertIs<DomainResult.Success<AccountDeletionOutcome>>(
            repository.deleteAccount(validDeletionRequest()),
        ).value

        assertEquals(
            rejection.domainError,
            assertIs<AccountDeletionOutcome.RejectedCleanupPending>(outcome).error,
        )
    }
}

private class FakeAuthDataSource(
    private val session: AuthSessionDto? = null,
    private val activationException: Throwable? = null,
    private val previewException: Throwable? = null,
    private val discardException: Throwable? = null,
    private val deletionException: Throwable? = null,
    private val promoterSignInSessions: PromoterSignInSessions = PromoterSignInSessions(),
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
    var socialSignIns: Int = 0
        private set
    var temporarySessionDiscards: Int = 0
        private set
    var lastActivationSessionProof: PromoterActivationSessionProof? = null
        private set
    var lastDeletionRequest: AccountDeletionRequest? = null
        private set
    var deletionOutcome: AccountDeletionDataOutcome = AccountDeletionDataOutcome.Deleted
    var activeLegalDocuments: List<LegalDocumentRevision> = legalDocuments()

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

    override suspend fun listActiveLegalDocuments(locale: AppLocale): List<LegalDocumentRevision> = activeLegalDocuments

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): AuthSessionDto {
        lastCompleteRequest = request
        return authSessionDto(onboardingCompleted = true)
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthSessionDto {
        lastSignInEmail = email
        lastSignInPassword = password
        return promoterSignInSessions.email
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

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): AuthSessionDto {
        socialSignIns += 1
        return promoterSignInSessions.social
    }

    override suspend fun establishPromoterActivationSession(proof: PromoterActivationSessionProof) {
        lastActivationSessionProof = proof
    }

    override suspend fun previewPromoterInvite(inviteToken: String): PromoterActivationContext {
        previewException?.let { exception -> throw exception }
        return PromoterActivationContext(
            inviteToken = inviteToken,
            organizationId = "organization-1",
            listingId = "listing-1",
            businessName = "Etablissement Kwabor",
        )
    }

    override suspend fun activatePromoterInvite(inviteToken: String): PromoterActivationResultDto {
        activationException?.let { exception -> throw exception }
        return PromoterActivationResultDto(
            session = authSessionDto(onboardingCompleted = true),
            organizationId = "organization-1",
            listingId = "listing-1",
        )
    }

    override suspend fun deleteAccount(request: AccountDeletionRequest): AccountDeletionDataOutcome {
        lastDeletionRequest = request
        deletionException?.let { exception -> throw exception }
        return deletionOutcome
    }

    override suspend fun discardTemporarySession() {
        temporarySessionDiscards += 1
        discardException?.let { exception -> throw exception }
    }

    override suspend fun signOut() = Unit
}

private data class PromoterSignInSessions(
    val email: AuthSessionDto = authSessionDto(onboardingCompleted = true),
    val social: AuthSessionDto = authSessionDto(onboardingCompleted = true),
)

private const val VALID_ID_TOKEN = "header.payload.signature"
private const val VALID_NONCE = "abcdefghijklmnopqrstuvwxyzABCDEF"
private val VALID_INVITE_TOKEN = "a".repeat(64)
private val VALID_PKCE_CODE = "b".repeat(32)

private fun validSocialRequest(): SocialSignInRequest = SocialSignInRequest(
    provider = SocialAuthProvider.Google,
    idToken = VALID_ID_TOKEN,
    rawNonce = VALID_NONCE,
)

private fun validDeletionRequest(): AccountDeletionRequest = AccountDeletionRequest(
    expectedAccountId = "user-1",
    idempotencyKey = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    credential = AccountDeletionCredential.Password("password123"),
)

private fun authSessionDto(
    onboardingCompleted: Boolean,
    purpose: AuthSessionPurpose = AuthSessionPurpose.Standard,
    userId: String = "user-1",
    email: String? = "user@kwabor.test",
): AuthSessionDto = AuthSessionDto(
    userId = userId,
    email = email,
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
