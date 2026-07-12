package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.EmailOtpProfileRequest
import com.kwabor.shared.domain.auth.EmailSignUpRequest
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthPresenterTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun loadCurrentSession_marksExistingSessionAsAuthenticated() = runTest {
        val repository = FakeAuthRepository(currentSession = authSession())
        val presenter = AuthPresenter(repository)

        val state = presenter.loadCurrentSession(initialAuthUiState(), strings)

        assertTrue(state.isAuthenticated)
        assertEquals("user-1", state.currentSession?.userId)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun requestEmailOtp_movesToOtpStepAndShowsNotice() = runTest {
        val repository = FakeAuthRepository()
        val presenter = AuthPresenter(repository)

        val state = presenter.requestEmailOtp(
            state = initialAuthUiState().copy(email = "user@kwabor.test"),
            strings = strings,
        )

        assertEquals(AuthStep.Otp, state.step)
        assertEquals(strings.authOtpSent, state.noticeMessage)
        assertFalse(state.isLoading)
        assertEquals("user@kwabor.test", repository.requestedEmail)
    }

    @Test
    fun verifyEmailOtpWithProfile_requiresLegalAcceptanceBeforeRepositoryCall() = runTest {
        val repository = FakeAuthRepository()
        val presenter = AuthPresenter(repository)

        val state = presenter.verifyEmailOtpWithProfile(
            state = validOtpState().copy(legalAccepted = false),
            strings = strings,
        )

        assertEquals(strings.authInvalidInput, state.errorMessage)
        assertEquals(null, repository.verifiedRequest)
        assertFalse(state.isAuthenticated)
    }

    @Test
    fun verifyEmailOtpWithProfile_mapsSessionAndTrimmedProfile() = runTest {
        val repository = FakeAuthRepository()
        val presenter = AuthPresenter(repository)

        val state = presenter.verifyEmailOtpWithProfile(
            state = validOtpState().copy(firstName = " Afi ", lastName = " Kwabor "),
            strings = strings,
        )

        assertTrue(state.isAuthenticated)
        assertEquals(strings.authSessionReady, state.noticeMessage)
        assertEquals("user-1", state.currentSession?.userId)
        assertEquals("Afi", repository.verifiedRequest?.onboarding?.firstName)
        assertEquals("Kwabor", repository.verifiedRequest?.onboarding?.lastName)
        assertEquals(AppLocale.French, repository.verifiedRequest?.onboarding?.preferredLocale)
        assertEquals(KwaborCurrency.Xof, repository.verifiedRequest?.onboarding?.preferredCurrency)
    }

    @Test
    fun verifyEmailOtpWithProfile_mapsPermissionErrorToUserMessage() = runTest {
        val repository = FakeAuthRepository(
            verifyResult = DomainResult.Failure(DomainError.PermissionDenied("error.auth.denied")),
        )
        val presenter = AuthPresenter(repository)

        val state = presenter.verifyEmailOtpWithProfile(state = validOtpState(), strings = strings)

        assertEquals(strings.authPermissionDenied, state.errorMessage)
        assertFalse(state.isAuthenticated)
    }

    @Test
    fun signOut_clearsSessionAndKeepsNotice() = runTest {
        val repository = FakeAuthRepository()
        val presenter = AuthPresenter(repository)

        val state = presenter.signOut(
            state = initialAuthUiState().copy(currentSession = authSession()),
            strings = strings,
        )

        assertFalse(state.isAuthenticated)
        assertEquals(strings.authSignedOut, state.noticeMessage)
        assertTrue(repository.signOutCalled)
    }

    @Test
    fun signInWithSocialIdToken_delegatesProviderAndMapsSession() = runTest {
        val repository = FakeAuthRepository()
        val presenter = AuthPresenter(repository)

        val state = presenter.signInWithSocialIdToken(
            state = initialAuthUiState(),
            provider = SocialAuthProvider.Google,
            idToken = "id-token",
            strings = strings,
        )

        assertTrue(state.isAuthenticated)
        assertEquals(SocialAuthProvider.Google, repository.socialRequest?.provider)
        assertEquals("id-token", repository.socialRequest?.idToken)
    }

    private fun validOtpState(): AuthUiState = initialAuthUiState().copy(
        email = "user@kwabor.test",
        otpCode = "123456",
        firstName = "Afi",
        lastName = "Kwabor",
        legalAccepted = true,
        step = AuthStep.Otp,
    )
}

private class FakeAuthRepository(
    private val currentSession: AuthSession? = null,
    private val requestOtpResult: DomainResult<Unit> = DomainResult.Success(Unit),
    private val verifyResult: DomainResult<AuthSession> = DomainResult.Success(authSession()),
    private val socialResult: DomainResult<AuthSession> = DomainResult.Success(authSession()),
) : AuthRepository {
    var requestedEmail: String? = null
        private set
    var verifiedRequest: EmailOtpProfileRequest? = null
        private set
    var socialRequest: SocialSignInRequest? = null
        private set
    var signOutCalled: Boolean = false
        private set

    override suspend fun getCurrentSession(): DomainResult<AuthSession?> = DomainResult.Success(currentSession)

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> {
        requestedEmail = email
        return requestOtpResult
    }

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun verifyEmailOtpWithProfile(request: EmailOtpProfileRequest): DomainResult<AuthSession> {
        verifiedRequest = request
        return verifyResult
    }

    override suspend fun signUpWithEmail(request: EmailSignUpRequest): DomainResult<AuthSession> =
        DomainResult.Success(authSession())

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> =
        DomainResult.Success(authSession())

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> {
        socialRequest = request
        return socialResult
    }

    override suspend fun activatePromoterInvite(request: PromoterActivationRequest): DomainResult<AuthSession> =
        DomainResult.Failure(DomainError.Validation("error.auth.unused"))

    override suspend fun signOut(): DomainResult<Unit> {
        signOutCalled = true
        return DomainResult.Success(Unit)
    }
}

private fun authSession(): AuthSession = AuthSession(
    userId = "user-1",
    email = "user@kwabor.test",
    expiresAtEpochMilliseconds = 1_783_080_000_000,
)
