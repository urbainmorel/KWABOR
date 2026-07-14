package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.EmailOtpProfileRequest
import com.kwabor.shared.domain.auth.EmailSignUpRequest
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.AuthStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun requestOtp_reducesInputsAndMovesToOtpStep() = runTest {
        val repository = ViewModelAuthRepository()
        val viewModel = AuthViewModel(AuthPresenter(repository), strings, this)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.Open)
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isVisible)
        assertEquals(AuthStep.Otp, viewModel.state.value.step)
        assertEquals(TEST_EMAIL, repository.requestedEmail)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun sessionLoad_preservesEditsMadeWhileRepositoryIsWaiting() = runTest {
        val sessionGate = CompletableDeferred<Unit>()
        val repository = ViewModelAuthRepository(sessionGate = sessionGate)
        val viewModel = AuthViewModel(AuthPresenter(repository), strings, this)

        assertFalse(viewModel.isSessionRestoreComplete.value)
        viewModel.onIntent(AuthIntent.Open)
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        sessionGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isVisible)
        assertEquals(TEST_EMAIL, viewModel.state.value.email)
        assertTrue(viewModel.isSessionRestoreComplete.value)
    }

    @Test
    fun requestOtp_doesNotReopenSheetDismissedDuringNetworkCall() = runTest {
        val requestGate = CompletableDeferred<Unit>()
        val repository = ViewModelAuthRepository(requestGate = requestGate)
        val viewModel = AuthViewModel(AuthPresenter(repository), strings, this)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.Open)
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        runCurrent()

        viewModel.onIntent(AuthIntent.Dismiss)
        requestGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isVisible)
        assertEquals(AuthStep.Otp, viewModel.state.value.step)
    }

    @Test
    fun verifyOtp_closesSheetAndEmitsAuthenticationEffect() = runTest {
        val viewModel = AuthViewModel(AuthPresenter(ViewModelAuthRepository()), strings, this)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.Open)
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.ChangeFirstName("Afi"))
        viewModel.onIntent(AuthIntent.ChangeLastName("Kwabor"))
        viewModel.onIntent(AuthIntent.ChangeOtpCode("123456"))
        viewModel.onIntent(AuthIntent.ChangeLegalAccepted(true))

        viewModel.onIntent(AuthIntent.VerifyOtp)
        advanceUntilIdle()

        assertIs<AuthEffect.AuthenticationCompleted>(viewModel.effects.first())
        assertTrue(viewModel.state.value.isAuthenticated)
        assertFalse(viewModel.state.value.isVisible)
    }
}

private class ViewModelAuthRepository(
    private val sessionGate: CompletableDeferred<Unit>? = null,
    private val requestGate: CompletableDeferred<Unit>? = null,
) : AuthRepository {
    var requestedEmail: String? = null
        private set

    override suspend fun getCurrentSession(): DomainResult<AuthSession?> {
        sessionGate?.await()
        return DomainResult.Success(null)
    }

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> {
        requestedEmail = email
        requestGate?.await()
        return DomainResult.Success(Unit)
    }

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun verifyEmailOtpWithProfile(request: EmailOtpProfileRequest): DomainResult<AuthSession> =
        DomainResult.Success(authSession())

    override suspend fun signUpWithEmail(request: EmailSignUpRequest): DomainResult<AuthSession> =
        DomainResult.Success(authSession())

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> =
        DomainResult.Success(authSession())

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> =
        DomainResult.Success(authSession())

    override suspend fun activatePromoterInvite(request: PromoterActivationRequest): DomainResult<AuthSession> =
        DomainResult.Success(authSession())

    override suspend fun signOut(): DomainResult<Unit> = DomainResult.Success(Unit)
}

private fun authSession(): AuthSession = AuthSession(
    userId = "user-1",
    email = TEST_EMAIL,
    expiresAtEpochMilliseconds = 1_783_080_000_000,
)

private const val TEST_EMAIL = "user@kwabor.test"
