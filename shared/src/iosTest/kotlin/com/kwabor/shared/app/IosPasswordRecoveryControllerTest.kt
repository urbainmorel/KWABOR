package com.kwabor.shared.app

import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.presentation.auth.PasswordRecoveryPresenter
import com.kwabor.shared.presentation.auth.PasswordRecoveryStep
import com.kwabor.shared.presentation.auth.PasswordRecoveryUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IosPasswordRecoveryControllerTest {
    @Test
    fun recoveryStateDoesNotExposeEmailThroughToString() {
        val email = "sensitive@kwabor.test"
        val state = PasswordRecoveryUiState(
            step = PasswordRecoveryStep.Otp,
            email = email,
        )

        assertFalse(state.toString().contains(email))
    }

    @Test
    fun unconfiguredControllerCompletesWithoutKeepingSensitiveInputs() {
        val controller = IosPasswordRecoveryController(
            presenter = null,
            dispatcherProvider = TestDispatcherProvider,
        )
        var completed = true

        controller.verifyOtp("123456") { success -> completed = success }

        assertFalse(completed)
        controller.close()
    }

    @Test
    fun resumeVerifiedSessionOpensNewPasswordWithoutNetworkCall() {
        val repository = IosRecoveryAuthRepository()
        val controller = configuredController(repository)
        var observedState: PasswordRecoveryUiState? = null
        controller.observe { state -> observedState = state }

        controller.resumeVerifiedSession(" user@kwabor.test ")

        assertEquals(PasswordRecoveryStep.NewPassword, observedState?.step)
        assertEquals("user@kwabor.test", observedState?.email)
        assertEquals(0, repository.recoveryRequests)
        controller.close()
    }

    @Test
    fun cancelFailureKeepsRecoveryStepAndCompletesFalse() {
        val repository = IosRecoveryAuthRepository(
            cancelResult = DomainResult.Failure(DomainError.NetworkUnavailable()),
        )
        val controller = configuredController(repository)
        var observedState: PasswordRecoveryUiState? = null
        var completed = true
        controller.observe { state -> observedState = state }
        controller.resumeVerifiedSession("user@kwabor.test")

        controller.cancel { success -> completed = success }

        assertFalse(completed)
        assertEquals(PasswordRecoveryStep.NewPassword, observedState?.step)
        assertEquals("Vous êtes hors ligne", observedState?.errorMessage)
        controller.close()
    }

    private fun configuredController(repository: AuthRepository): IosPasswordRecoveryController =
        IosPasswordRecoveryController(
            presenter = PasswordRecoveryPresenter(repository, IosRecoveryClock),
            dispatcherProvider = TestDispatcherProvider,
        )
}

private object TestDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
}

private object IosRecoveryClock : ClockProvider {
    override fun nowEpochMilliseconds(): Long = 0L
}

private class IosRecoveryAuthRepository(
    private val cancelResult: DomainResult<Unit> = DomainResult.Success(Unit),
) : AuthRepository {
    var recoveryRequests: Int = 0
        private set

    override suspend fun getCurrentSession(): DomainResult<AuthSession?> = DomainResult.Success(null)

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> = unused()

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession> = unused()

    override suspend fun setInitialPassword(password: String): DomainResult<Unit> = unused()

    override suspend fun listActiveLegalDocuments(locale: AppLocale): DomainResult<List<LegalDocumentRevision>> =
        unused()

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): DomainResult<AuthSession> = unused()

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> = unused()

    override suspend fun requestPasswordRecovery(email: String): DomainResult<Unit> {
        recoveryRequests += 1
        return DomainResult.Success(Unit)
    }

    override suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): DomainResult<AuthSession> = unused()

    override suspend fun completePasswordRecovery(newPassword: String): DomainResult<Unit> = unused()

    override suspend fun cancelPasswordRecovery(): DomainResult<Unit> = cancelResult

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> = unused()

    override suspend fun activatePromoterInvite(request: PromoterActivationRequest): DomainResult<AuthSession> =
        unused()

    override suspend fun signOut(): DomainResult<Unit> = unused()

    private fun <T> unused(): DomainResult<T> = DomainResult.Failure(DomainError.Unexpected("unused"))
}
