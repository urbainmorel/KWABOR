package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthPresenterTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun loadCurrentSession_doesNotAuthenticateIncompleteAccount() = runTest {
        val presenter =
            AuthPresenter(FakeAuthRepository(currentSession = authSession(AccountSetupStatus.OnboardingRequired)))

        val state = presenter.loadCurrentSession(initialAuthUiState(), strings)

        assertTrue(state.hasSession)
        assertFalse(state.isAuthenticated)
    }

    @Test
    fun loadCurrentSession_marksCompletedAccountAsAuthenticated() = runTest {
        val presenter = AuthPresenter(FakeAuthRepository(currentSession = authSession(AccountSetupStatus.Complete)))

        val state = presenter.loadCurrentSession(initialAuthUiState(), strings)

        assertTrue(state.isAuthenticated)
    }

    @Test
    fun signInWithEmail_preservesIncompleteServerStatus() = runTest {
        val presenter = AuthPresenter(
            FakeAuthRepository(signInResult = DomainResult.Success(authSession(AccountSetupStatus.OnboardingRequired))),
        )

        val state = presenter.signInWithEmail(initialAuthUiState(), "user@kwabor.test", "password123", strings)

        assertTrue(state.hasSession)
        assertFalse(state.isAuthenticated)
    }

    @Test
    fun signInWithSocialIdToken_mapsPermissionErrorToSafeMessage() = runTest {
        val presenter = AuthPresenter(
            FakeAuthRepository(socialResult = DomainResult.Failure(DomainError.PermissionDenied("technical"))),
        )

        val state = presenter.signInWithSocialIdToken(
            initialAuthUiState(),
            com.kwabor.shared.domain.auth.SocialAuthProvider.Google,
            "id-token",
            strings,
        )

        assertEquals(strings.authPermissionDenied, state.errorMessage)
        assertFalse(state.isAuthenticated)
    }

    @Test
    fun signOut_clearsSession() = runTest {
        val presenter = AuthPresenter(FakeAuthRepository())

        val state = presenter.signOut(
            initialAuthUiState().copy(currentSession = authSession(AccountSetupStatus.Complete)),
            strings,
        )

        assertFalse(state.hasSession)
        assertEquals(strings.authSignedOut, state.noticeMessage)
    }
}

private class FakeAuthRepository(
    private val currentSession: AuthSession? = null,
    private val signInResult: DomainResult<AuthSession> = DomainResult.Success(
        authSession(AccountSetupStatus.Complete),
    ),
    private val socialResult: DomainResult<AuthSession> = DomainResult.Success(
        authSession(AccountSetupStatus.Complete),
    ),
) : AuthRepository {
    override suspend fun getCurrentSession(): DomainResult<AuthSession?> = DomainResult.Success(currentSession)

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession> = signInResult

    override suspend fun setInitialPassword(password: String): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun listActiveLegalDocuments(locale: AppLocale): DomainResult<List<LegalDocumentRevision>> =
        DomainResult.Success(emptyList())

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): DomainResult<AuthSession> =
        signInResult

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> = signInResult

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> =
        socialResult

    override suspend fun activatePromoterInvite(request: PromoterActivationRequest): DomainResult<AuthSession> =
        DomainResult.Failure(DomainError.Validation("error.auth.unused"))

    override suspend fun signOut(): DomainResult<Unit> = DomainResult.Success(Unit)
}

private fun authSession(status: AccountSetupStatus): AuthSession = AuthSession(
    userId = "user-1",
    email = "user@kwabor.test",
    expiresAtEpochMilliseconds = 1_783_080_000_000,
    accountSetupStatus = status,
)
