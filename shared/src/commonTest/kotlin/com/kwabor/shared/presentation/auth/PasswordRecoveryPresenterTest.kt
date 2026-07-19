package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordRecoveryPresenterTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun resumeVerifiedSession_opensNewPasswordWithoutRetainingTransientState() {
        val presenter = PasswordRecoveryPresenter(FakePasswordRecoveryRepository(), FixedClock())

        val state = presenter.resumeVerifiedSession(
            state = PasswordRecoveryUiState(
                step = PasswordRecoveryStep.Email,
                isLoading = true,
                errorMessage = "old error",
                noticeMessage = "old notice",
            ),
            email = " user@kwabor.test ",
        )

        assertEquals(PasswordRecoveryStep.NewPassword, state.step)
        assertEquals("user@kwabor.test", state.email)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertNull(state.noticeMessage)
    }

    @Test
    fun requestCode_usesNeutralNoticeAndStartsCooldown() = runTest {
        val repository = FakePasswordRecoveryRepository()
        val presenter = PasswordRecoveryPresenter(repository, FixedClock(1_000L))

        val state = presenter.requestCode(initialPasswordRecoveryUiState(), " user@kwabor.test ", strings)

        assertEquals(PasswordRecoveryStep.Otp, state.step)
        assertEquals("user@kwabor.test", state.email)
        assertEquals(31_000L, state.resendAvailableAtEpochMilliseconds)
        assertEquals(strings.passwordRecoveryCodeSent, state.noticeMessage)
        assertEquals(" user@kwabor.test ", repository.requestedEmail)
    }

    @Test
    fun resendCode_isBlockedUntilThirtySecondsHaveElapsed() = runTest {
        val repository = FakePasswordRecoveryRepository()
        val clock = FixedClock(10_000L)
        val presenter = PasswordRecoveryPresenter(repository, clock)
        val initial = PasswordRecoveryUiState(
            step = PasswordRecoveryStep.Otp,
            email = "user@kwabor.test",
            resendAvailableAtEpochMilliseconds = 20_000L,
        )

        val blocked = presenter.resendCode(initial, strings)
        clock.now = 20_000L
        val allowed = presenter.resendCode(blocked, strings)

        assertEquals(strings.registrationOtpWait, blocked.errorMessage)
        assertEquals(PasswordRecoveryStep.Otp, allowed.step)
        assertEquals(1, repository.recoveryRequests)
    }

    @Test
    fun verifyOtp_requiresRecoveryPurposeBeforeAdvancing() = runTest {
        val standardRepository = FakePasswordRecoveryRepository(
            verifiedSession = recoverySession().copy(purpose = AuthSessionPurpose.Standard),
        )
        val recoveryRepository = FakePasswordRecoveryRepository()
        val state = PasswordRecoveryUiState(step = PasswordRecoveryStep.Otp, email = "user@kwabor.test")

        val rejected = PasswordRecoveryPresenter(standardRepository, FixedClock()).verifyOtp(state, "123456", strings)
        val accepted = PasswordRecoveryPresenter(recoveryRepository, FixedClock()).verifyOtp(state, "123456", strings)

        assertEquals(PasswordRecoveryStep.Otp, rejected.step)
        assertEquals(strings.authSessionExpired, rejected.errorMessage)
        assertEquals(PasswordRecoveryStep.NewPassword, accepted.step)
    }

    @Test
    fun complete_validatesConfirmationWithoutPersistingSecretsInState() = runTest {
        val repository = FakePasswordRecoveryRepository()
        val presenter = PasswordRecoveryPresenter(repository, FixedClock())
        val state = PasswordRecoveryUiState(
            step = PasswordRecoveryStep.NewPassword,
            email = "sensitive@kwabor.test",
        )

        val mismatch = presenter.complete(state, "password-one", "password-two", strings)
        val passwordAfterMismatch = repository.completedPassword
        val completed = presenter.complete(state, "password-one", "password-one", strings)

        assertEquals(strings.registrationPasswordMismatch, mismatch.errorMessage)
        assertNull(passwordAfterMismatch)
        assertEquals(PasswordRecoveryStep.Completed, completed.step)
        assertEquals("password-one", repository.completedPassword)
        assertFalse(completed.toString().contains("sensitive@kwabor.test"))
        assertFalse(completed.toString().contains("password-one"))
    }

    @Test
    fun cancel_returnsInitialStateOnlyAfterRepositoryCleanup() = runTest {
        val repository = FakePasswordRecoveryRepository()
        val presenter = PasswordRecoveryPresenter(repository, FixedClock())

        val state = presenter.cancel(
            PasswordRecoveryUiState(step = PasswordRecoveryStep.NewPassword, email = "user@kwabor.test"),
            strings,
        )

        assertTrue(repository.cancelled)
        assertEquals(initialPasswordRecoveryUiState(), state)
    }

    @Test
    fun typedRecoveryErrors_areMappedToSafeCopy() = runTest {
        val repository = FakePasswordRecoveryRepository(
            verifyFailure = DomainError.Validation("error.auth.otp_expired"),
        )
        val presenter = PasswordRecoveryPresenter(repository, FixedClock())

        val state = presenter.verifyOtp(
            PasswordRecoveryUiState(step = PasswordRecoveryStep.Otp, email = "user@kwabor.test"),
            "123456",
            strings,
        )

        assertEquals(strings.registrationOtpExpired, state.errorMessage)
    }
}

private class FixedClock(var now: Long = 0L) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = now
}

private class FakePasswordRecoveryRepository(
    private val verifiedSession: AuthSession = recoverySession(),
    private val verifyFailure: DomainError? = null,
) : AuthRepository {
    var recoveryRequests: Int = 0
        private set
    var requestedEmail: String? = null
        private set
    var completedPassword: String? = null
        private set
    var cancelled: Boolean = false
        private set

    override suspend fun getCurrentSession(): DomainResult<AuthSession?> = DomainResult.Success(null)

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession> =
        DomainResult.Success(verifiedSession)

    override suspend fun setInitialPassword(password: String): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun listActiveLegalDocuments(locale: AppLocale): DomainResult<List<LegalDocumentRevision>> =
        DomainResult.Success(emptyList())

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): DomainResult<AuthSession> =
        DomainResult.Success(verifiedSession)

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> =
        DomainResult.Success(verifiedSession)

    override suspend fun requestPasswordRecovery(email: String): DomainResult<Unit> {
        recoveryRequests += 1
        requestedEmail = email
        return DomainResult.Success(Unit)
    }

    override suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): DomainResult<AuthSession> =
        verifyFailure?.let { error -> DomainResult.Failure(error) } ?: DomainResult.Success(verifiedSession)

    override suspend fun completePasswordRecovery(newPassword: String): DomainResult<Unit> {
        completedPassword = newPassword
        return DomainResult.Success(Unit)
    }

    override suspend fun cancelPasswordRecovery(): DomainResult<Unit> {
        cancelled = true
        return DomainResult.Success(Unit)
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> =
        DomainResult.Success(verifiedSession)

    override suspend fun activatePromoterInvite(request: PromoterActivationRequest): DomainResult<AuthSession> =
        DomainResult.Failure(DomainError.Validation("error.auth.unused"))

    override suspend fun signOut(): DomainResult<Unit> = DomainResult.Success(Unit)
}

private fun recoverySession(): AuthSession = AuthSession(
    userId = "user-1",
    email = "user@kwabor.test",
    expiresAtEpochMilliseconds = 1_783_080_000_000,
    accountSetupStatus = AccountSetupStatus.Complete,
    purpose = AuthSessionPurpose.PasswordRecovery,
)
