package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.AuthJourneyStore
import com.kwabor.android.auth.GoogleIdentityProvider
import com.kwabor.android.auth.GoogleIdentityResult
import com.kwabor.android.auth.IdempotencyKeyProvider
import com.kwabor.android.auth.InterruptedAuthJourney
import com.kwabor.android.auth.PromoterActivationSessionStore
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.AuthenticationMethod
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.PromoterActivationResult
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.PasswordRecoveryPresenter
import com.kwabor.shared.presentation.auth.PasswordRecoveryStep
import com.kwabor.shared.presentation.auth.RegistrationPresenter
import com.kwabor.shared.presentation.auth.RegistrationReducer
import com.kwabor.shared.presentation.auth.RegistrationStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelOnboardingTest {
    @Test
    fun incompleteRestoredSessionResumesAtPasswordWithRequirementsReady() = runTest {
        val viewModel = createViewModel(
            repository = RegistrationAuthRepository(currentSession = onboardingSession()),
            scope = this,
        )

        advanceUntilIdle()

        assertEquals(AuthSurface.Registration, viewModel.platformState.value.surface)
        assertEquals(RegistrationStep.Password, viewModel.registrationState.value.step)
        assertTrue(viewModel.registrationState.value.requirementsReady)
        assertTrue(viewModel.state.value.hasSession)
        assertFalse(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun completedRestoredSessionGoesDirectlyHomeWithoutPrimer() = runTest {
        val viewModel = createViewModel(
            repository = RegistrationAuthRepository(currentSession = completeSession()),
            scope = this,
        )

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isAuthenticated)
        assertEquals(AuthSurface.Hidden, viewModel.platformState.value.surface)
        assertEquals(RegistrationStep.Email, viewModel.registrationState.value.step)
    }

    @Test
    fun compactProfileCompletionClosesJourneyImmediately() = runTest {
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(repository = repository, scope = this)
        val effects = viewModel.effects.produceIn(backgroundScope)
        advanceUntilIdle()
        completeRegistrationProfile(viewModel)

        viewModel.onIntent(AuthIntent.CompleteProfile)
        advanceUntilIdle()

        assertEquals(null, viewModel.registrationState.value.errorMessage, viewModel.registrationState.value.toString())
        assertEquals(1, repository.completeOnboardingCallCount)
        assertEquals(RegistrationStep.Completed, viewModel.registrationState.value.step)
        assertEquals(AuthSurface.Hidden, viewModel.platformState.value.surface)
        assertEquals(AuthEffect.AuthenticationCompleted, effects.receive())
        assertTrue(effects.tryReceive().isFailure)
    }

    @Test
    fun onboardingSubmissionIgnoresDoubleTapBeforeCoroutineStarts() = runTest {
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(repository = repository, scope = this)
        advanceUntilIdle()
        completeRegistrationProfile(viewModel)

        viewModel.onIntent(AuthIntent.CompleteProfile)
        viewModel.onIntent(AuthIntent.CompleteProfile)
        advanceUntilIdle()

        assertEquals(null, viewModel.registrationState.value.errorMessage, viewModel.registrationState.value.toString())
        assertEquals(1, repository.completeOnboardingCallCount)
    }

    @Test
    fun credentialIntentsNeverExposeOtpOrPasswordInLogs() {
        val otpIntent = AuthIntent.SubmitOtp(TEST_OTP)
        val passwordIntent = AuthIntent.SubmitPassword(TEST_PASSWORD)

        assertFalse(otpIntent.toString().contains(TEST_OTP))
        assertFalse(passwordIntent.toString().contains(TEST_PASSWORD))
    }

    @Test
    fun otpSubmissionIsSingleFlightAcrossAutofillAndExplicitSubmit() = runTest {
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(repository = repository, scope = this)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        advanceUntilIdle()

        assertEquals(1, repository.otpVerificationCount)
        assertEquals(RegistrationStep.Password, viewModel.registrationState.value.step)
    }

    @Test
    fun continuingAsGuestSignsOutPartialOtpSessionBeforeEmittingGuestEffect() = runTest {
        var consentRevocationCount = 0
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                revokeConsent = {
                    consentRevocationCount += 1
                    true
                },
            ),
        )
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.OpenRegistration(AuthEntryPoint.SoftWall))
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.ContinueAsGuest)
        advanceUntilIdle()

        assertTrue(repository.signOutCalled)
        assertEquals(1, consentRevocationCount)
        assertFalse(viewModel.state.value.hasSession)
        assertEquals(AuthEffect.GuestContinuationSelected, viewModel.effects.first())
    }

    @Test
    fun continuingAsGuestStopsBeforeSignOutWhenConsentRevocationCannotBePersisted() = runTest {
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(revokeConsent = { false }),
        )
        val effects = viewModel.effects.produceIn(backgroundScope)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.OpenRegistration(AuthEntryPoint.SoftWall))
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.ContinueAsGuest)
        advanceUntilIdle()

        assertEquals(0, repository.signOutCallCount)
        assertTrue(viewModel.state.value.hasSession)
        assertEquals(
            stringsFor(AppLocale.French).settings.privacyPersistenceError,
            viewModel.registrationState.value.errorMessage,
        )
        assertTrue(effects.tryReceive().isFailure)
    }

    @Test
    fun retryRequirementsDoesNotResubmitPassword() = runTest {
        val repository = RegistrationAuthRepository(
            failurePlan = RegistrationAuthFailurePlan(failFirstLegalDocumentsLoad = true),
        )
        val viewModel = createViewModel(repository = repository, scope = this)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitPassword(TEST_PASSWORD))
        advanceUntilIdle()

        assertEquals(RegistrationStep.Profile, viewModel.registrationState.value.step)
        assertEquals(1, repository.passwordUpdateCount)

        viewModel.onIntent(AuthIntent.RetryRequirements)
        advanceUntilIdle()

        assertTrue(viewModel.registrationState.value.requirementsReady)
        assertEquals(1, repository.passwordUpdateCount)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelFederatedSecurityTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun googleSignInUsesNonceAndResumesEditableIdentityWithServerHints() = runTest {
        val journeyStore = FakeAuthJourneyStore()
        val googleProvider = FakeGoogleIdentityProvider(
            GoogleIdentityResult.Success(
                idToken = TEST_GOOGLE_ID_TOKEN,
                nonce = TEST_GOOGLE_RAW_NONCE,
                profileHint = com.kwabor.android.auth.GoogleProfileHint("Afi", "Soglo"),
            ),
        )
        val socialSession = onboardingSession().copy(
            authenticationMethod = AuthenticationMethod.Google,
            suggestedFirstName = "Afi",
            suggestedLastName = "Soglo",
        )
        val repository = RegistrationAuthRepository(
            authBehavior = RegistrationAuthBehavior(signInSession = socialSession),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                authJourneyStore = journeyStore,
                googleIdentityProvider = googleProvider,
            ),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ContinueWithGoogle)
        advanceUntilIdle()

        assertEquals(TEST_GOOGLE_ID_TOKEN, repository.lastSocialSignInRequest?.idToken)
        assertEquals(TEST_GOOGLE_RAW_NONCE, repository.lastSocialSignInRequest?.rawNonce)
        assertEquals(RegistrationStep.Profile, viewModel.registrationState.value.step)
        assertEquals("Afi", viewModel.registrationState.value.firstName)
        assertEquals("Soglo", viewModel.registrationState.value.lastName)
        assertEquals(InterruptedAuthJourney.SocialRegistration, journeyStore.read())
    }

    @Test
    fun failedSocialJourneyWriteDoesNotSignOutUntilConsentRevocationPersists() = runTest {
        val journeyStore = FakeAuthJourneyStore(writesSucceed = false)
        val googleProvider = FakeGoogleIdentityProvider(
            GoogleIdentityResult.Success(
                idToken = TEST_GOOGLE_ID_TOKEN,
                nonce = TEST_GOOGLE_RAW_NONCE,
                profileHint = com.kwabor.android.auth.GoogleProfileHint("Afi", "Soglo"),
            ),
        )
        val repository = RegistrationAuthRepository(
            authBehavior = RegistrationAuthBehavior(
                signInSession = onboardingSession().copy(authenticationMethod = AuthenticationMethod.Google),
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                authJourneyStore = journeyStore,
                googleIdentityProvider = googleProvider,
                revokeConsent = { false },
            ),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ContinueWithGoogle)
        advanceUntilIdle()

        assertEquals(0, repository.signOutCallCount)
        assertTrue(viewModel.state.value.hasSession)
        assertEquals(strings.settings.privacyPersistenceError, viewModel.registrationState.value.errorMessage)
    }

    @Test
    fun cancelledGooglePickerIsNotDisplayedAsAnError() = runTest {
        val viewModel = createViewModel(
            repository = RegistrationAuthRepository(),
            scope = this,
            overrides = AuthTestOverrides(
                googleIdentityProvider = FakeGoogleIdentityProvider(GoogleIdentityResult.Cancelled),
            ),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenSignIn())
        viewModel.onIntent(AuthIntent.ContinueWithGoogle)
        advanceUntilIdle()

        assertFalse(viewModel.accessState.value.isLoading)
        assertEquals(null, viewModel.accessState.value.errorMessage)
    }

    @Test
    fun socialRegistrationRestoresIdentityFromPrivateJourneyOrigin() = runTest {
        val journeyStore = FakeAuthJourneyStore(InterruptedAuthJourney.SocialRegistration)
        val socialSession = onboardingSession().copy(
            authenticationMethod = AuthenticationMethod.Google,
            suggestedFirstName = "Afi",
            suggestedLastName = "Soglo",
        )
        val viewModel = createViewModel(
            repository = RegistrationAuthRepository(currentSession = socialSession),
            scope = this,
            overrides = AuthTestOverrides(
                authJourneyStore = journeyStore,
            ),
        )

        advanceUntilIdle()

        assertEquals(RegistrationStep.Profile, viewModel.registrationState.value.step)
        assertEquals("Afi", viewModel.registrationState.value.firstName)
        assertEquals("Soglo", viewModel.registrationState.value.lastName)
        assertEquals(InterruptedAuthJourney.SocialRegistration, journeyStore.read())
    }

    @Test
    fun accountDeletionReusesPrivateIdempotencyKeyAfterAmbiguousFailure() = runTest {
        val probe = AccountDeletionProbe(failFirstAttempt = true)
        val viewModel = createAccountDeletionViewModel(probe)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(2, probe.requests.size)
        assertEquals(probe.requests.first().idempotencyKey, probe.requests.last().idempotencyKey)
        assertEquals(1, probe.generatedKeyCount)
        assertTrue(viewModel.state.value.hasSession)
        assertEquals(AuthEffect.AccountDeleted, viewModel.effects.first())
        viewModel.onIntent(AuthIntent.AccountDeletionNavigationHandled)
        assertFalse(viewModel.state.value.hasSession)
    }

    @Test
    fun accountDeletionRequiresExactConfirmationBeforeCreatingIdempotencyKey() = runTest {
        val probe = AccountDeletionProbe()
        val viewModel = createAccountDeletionViewModel(probe)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, confirmation = "supprimer")

        assertEquals(0, probe.requests.size)
        assertEquals(0, probe.generatedKeyCount)
        assertTrue(viewModel.accessState.value.accountDeletionDialogVisible)
        assertEquals(strings.authInvalidInput, viewModel.accessState.value.accountDeletionErrorMessage)
        assertTrue(
            isAccountDeletionConfirmationValid(
                value = "  ${strings.authDeleteAccountConfirmationPhrase}  ",
                expected = strings.authDeleteAccountConfirmationPhrase,
            ),
        )
        assertFalse(
            isAccountDeletionConfirmationValid(
                value = "supprimer",
                expected = strings.authDeleteAccountConfirmationPhrase,
            ),
        )
    }

    @Test
    fun accountDeletionStopsBeforeServerWhenConsentRevocationCannotBePersisted() = runTest {
        val probe = AccountDeletionProbe()
        val viewModel = createAccountDeletionViewModel(probe, revokeConsent = { false })
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertTrue(probe.requests.isEmpty())
        assertEquals(0, probe.generatedKeyCount)
        assertTrue(viewModel.state.value.isAuthenticated)
        assertFalse(viewModel.accessState.value.accountDeletionInProgress)
        assertEquals(
            strings.settings.privacyPersistenceError,
            viewModel.accessState.value.accountDeletionErrorMessage,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelPromoterSessionSafetyTest {

    @Test
    fun initialRestoreFailureWithoutCallbackIsFailClosedAndRetryable() = runTest {
        val repository = RegistrationAuthRepository(
            failurePlan = RegistrationAuthFailurePlan(currentSessionFailures = 1),
        )
        val viewModel = createViewModel(repository = repository, scope = this)
        advanceUntilIdle()

        assertSessionRestoreIsBlocked(viewModel)
        assertEquals(0, repository.promoterCallbackCallCount)

        viewModel.onIntent(AuthIntent.OpenSignIn())
        assertEquals(AuthSurface.SessionRestoreFailure, viewModel.platformState.value.surface)

        viewModel.onIntent(AuthIntent.RetrySessionRestore)
        advanceUntilIdle()

        assertEquals(AuthSessionRestoreStatus.Ready, viewModel.sessionRestoreStatus.value)
        assertTrue(viewModel.isSessionRestoreComplete.value)
        assertEquals(AuthSurface.Hidden, viewModel.platformState.value.surface)
        assertEquals(2, repository.getCurrentSessionCallCount)
    }

    @Test
    fun coldStartRevokesPendingImportedPromoterSessionBeforeRestoring() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(pending = true, operationEvents = events)
        val googleIdentityProvider = FakeGoogleIdentityProvider()
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(operationEvents = events),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                promoterActivationSessionStore = store,
                googleIdentityProvider = googleIdentityProvider,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("marker-read", "sign-out", "marker-clear", "get-current-session"),
            events,
        )
        assertEquals(1, repository.signOutCallCount)
        assertEquals(1, repository.getCurrentSessionCallCount)
        assertEquals(1, googleIdentityProvider.clearCredentialStateCallCount)
        assertFalse(store.pending)
        assertTrue(viewModel.isSessionRestoreComplete.value)
        assertFalse(viewModel.state.value.hasSession)
    }

    @Test
    fun coldStartStopsBeforeImportedSessionSignOutWhenConsentRevocationFails() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(pending = true, operationEvents = events)
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(operationEvents = events),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                promoterActivationSessionStore = store,
                revokeConsent = { false },
            ),
        )

        advanceUntilIdle()

        assertEquals(listOf("marker-read"), events)
        assertEquals(0, repository.signOutCallCount)
        assertEquals(0, repository.getCurrentSessionCallCount)
        assertTrue(store.pending)
        assertSessionRestoreIsBlocked(viewModel)
        assertEquals(
            stringsFor(AppLocale.French).settings.privacyPersistenceError,
            viewModel.state.value.errorMessage,
        )
    }

    @Test
    fun coldStartKeepsMarkerAndBlocksRestoreWhenImportedSessionRevocationFails() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(pending = true, operationEvents = events)
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            authBehavior = RegistrationAuthBehavior(signOutFailure = DomainError.NetworkUnavailable()),
            hooks = RegistrationAuthHooks(operationEvents = events),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()

        assertEquals(listOf("marker-read", "sign-out"), events)
        assertEquals(0, repository.getCurrentSessionCallCount)
        assertEquals(0, store.clearCallCount)
        assertTrue(store.pending)
        assertTrue(viewModel.isSessionRestoreComplete.value)
        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertEquals(AuthSurface.SessionRestoreFailure, viewModel.platformState.value.surface)
        assertFalse(viewModel.state.value.hasSession)

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(0, repository.promoterCallbackCallCount)
        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertTrue(viewModel.promoterActivationState.value.retryAvailable)
    }

    @Test
    fun coldStartBlocksRestoreUntilRevokedSessionMarkerCanBeCleared() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(
            pending = true,
            clearSucceeds = false,
            operationEvents = events,
        )
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(
                operationEvents = events,
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()

        assertEquals(listOf("marker-read", "sign-out", "marker-clear"), events)
        assertEquals(0, repository.getCurrentSessionCallCount)
        assertTrue(store.pending)
        assertSessionRestoreIsBlocked(viewModel)

        viewModel.onIntent(AuthIntent.OpenSignIn())

        assertEquals(AuthSurface.SessionRestoreFailure, viewModel.platformState.value.surface)

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(0, repository.promoterCallbackCallCount)
        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertTrue(viewModel.promoterActivationState.value.retryAvailable)

        store.clearSucceeds = true
        viewModel.onIntent(AuthIntent.RetrySessionRestore)
        advanceUntilIdle()

        assertPromoterCallbackReady(viewModel, repository)
        assertEquals(AuthSessionRestoreStatus.Ready, viewModel.sessionRestoreStatus.value)
    }

    @Test
    fun importedPromoterCallbackPersistsMarkerBeforeReady() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(operationEvents = events)
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = true))
                },
                operationEvents = events,
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(listOf("marker-mark", "promoter-callback"), events)
        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
        assertTrue(store.pending)
    }

    @Test
    fun invalidPromoterCallbackIsRejectedBeforeProvisionalMarkerOrShared() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(operationEvents = events)
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(operationEvents = events),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(
            AuthIntent.OpenPromoterActivation(
                "$TEST_PROMOTER_CALLBACK#access_token=forbidden&refresh_token=forbidden",
            ),
        )
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertEquals(0, store.markCallCount)
        assertEquals(0, repository.promoterCallbackCallCount)
        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
    }

    @Test
    fun provisionalMarkerSurvivesCancellationInsideSharedCallback() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(operationEvents = events)
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    assertTrue(store.pending)
                    throw CancellationException("Simulated process interruption")
                },
                operationEvents = events,
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(listOf("marker-mark", "promoter-callback"), events)
        assertEquals(PromoterActivationStage.Loading, viewModel.promoterActivationState.value.stage)
        assertEquals(1, repository.promoterCallbackCallCount)
        assertEquals(0, store.clearCallCount)
        assertTrue(store.pending)
    }

    @Test
    fun promoterCallbackErrorClearsImportedSessionAndMarkerBeforeError() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(operationEvents = events)
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(operationEvents = events),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(listOf("marker-mark", "promoter-callback", "sign-out", "marker-clear"), events)
        assertEquals(1, repository.signOutCallCount)
        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertFalse(store.pending)
        assertFalse(viewModel.state.value.hasSession)
    }

    @Test
    fun markerWriteFailureClearsProvisionalMarkerAndNeverCallsShared() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(
            markSucceeds = false,
            operationEvents = events,
        )
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = true))
                },
                operationEvents = events,
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(listOf("marker-mark", "marker-clear"), events)
        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertTrue(viewModel.promoterActivationState.value.retryAvailable)
        assertEquals(0, repository.signOutCallCount)
        assertEquals(0, repository.promoterCallbackCallCount)
        assertFalse(store.pending)
        assertFalse(viewModel.state.value.hasSession)
    }

    @Test
    fun provisionalMarkerWriteAndClearFailureBlocksWithoutCallingShared() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(
            markSucceeds = false,
            clearSucceeds = false,
            operationEvents = events,
        )
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = true))
                },
                operationEvents = events,
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                promoterActivationSessionStore = store,
            ),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(
            listOf("marker-mark", "marker-clear", "sign-out", "marker-clear"),
            events,
        )
        assertEquals(0, repository.promoterCallbackCallCount)
        assertEquals(1, repository.signOutCallCount)
        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertTrue(viewModel.promoterActivationState.value.retryAvailable)
        assertTrue(store.pending)
        assertFalse(viewModel.state.value.hasSession)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelPromoterActivationTest {
    @Test
    fun preexistingSessionNeverTouchesProvisionalMarkerStorage() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(
            clearSucceeds = false,
            operationEvents = events,
        )
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
                },
                operationEvents = events,
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                promoterActivationSessionStore = store,
            ),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_CALLBACK))
        advanceUntilIdle()

        assertEquals(listOf("promoter-callback"), events)
        assertEquals(0, repository.signOutCallCount)
        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
        assertFalse(store.pending)
        assertTrue(viewModel.state.value.hasSession)
    }

    @Test
    fun callbackErrorClearFailureBlocksAndCleansBeforeError() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(
            clearSucceeds = false,
            operationEvents = events,
        )
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(operationEvents = events),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(
            listOf("marker-mark", "promoter-callback", "sign-out", "marker-clear"),
            events,
        )
        assertEquals(1, repository.signOutCallCount)
        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertTrue(viewModel.promoterActivationState.value.retryAvailable)
        assertTrue(store.pending)
        assertFalse(viewModel.state.value.hasSession)
    }

    @Test
    fun preexistingPromoterSessionDoesNotArmProvisionalMarkerBeforeReady() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(operationEvents = events)
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
                },
                operationEvents = events,
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                promoterActivationSessionStore = store,
            ),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_CALLBACK))
        advanceUntilIdle()

        assertEquals(listOf("promoter-callback"), events)
        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)

        viewModel.onIntent(AuthIntent.CancelPromoterActivation)
        advanceUntilIdle()

        assertEquals(0, store.markCallCount)
        assertEquals(0, store.clearCallCount)
        assertEquals(0, repository.signOutCallCount)
        assertTrue(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun promoterCallbackNeverEntersStateAndCompletionCarriesTypedDestination() = runTest {
        val viewModel = createViewModel(
            repository = successfulPromoterRepository(sessionImportedForActivation = false),
            scope = this,
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_CALLBACK))
        advanceUntilIdle()

        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
        assertFalse(viewModel.promoterActivationState.value.toString().contains(TEST_PROMOTER_INVITE_TOKEN))

        viewModel.onIntent(AuthIntent.ActivatePromoterWithPassword(TEST_PASSWORD))
        advanceUntilIdle()
        assertEquals(PromoterActivationStage.Completed, viewModel.promoterActivationState.value.stage)

        viewModel.onIntent(AuthIntent.FinishPromoterActivation)
        advanceUntilIdle()

        assertEquals(
            AuthEffect.PromoterActivationCompleted(TEST_ORGANIZATION_ID, TEST_LISTING_ID),
            viewModel.effects.first(),
        )
    }

    @Test
    fun promoterCancellationRevokesSessionImportedForActivation() = runTest {
        val scenario = createPromoterCancellationScenario(sessionImportedForActivation = true)
        openAndCancelPromoterActivation(
            viewModel = scenario.viewModel,
            callbackUrl = TEST_PROMOTER_PKCE_CALLBACK,
        )

        assertEquals(1, scenario.repository.signOutCallCount)
        assertEquals(1, scenario.store.markCallCount)
        assertEquals(1, scenario.store.clearCallCount)
        assertFalse(scenario.store.pending)
        assertEquals(AuthSurface.Hidden, scenario.viewModel.platformState.value.surface)
    }

    @Test
    fun promoterCancellationPreservesPreexistingSession() = runTest {
        val scenario = createPromoterCancellationScenario(sessionImportedForActivation = false)
        openAndCancelPromoterActivation(
            viewModel = scenario.viewModel,
            callbackUrl = TEST_PROMOTER_CALLBACK,
        )

        assertEquals(0, scenario.repository.signOutCallCount)
        assertEquals(0, scenario.store.markCallCount)
        assertEquals(0, scenario.store.clearCallCount)
        assertTrue(scenario.viewModel.state.value.isAuthenticated)
        assertEquals(AuthSurface.Hidden, scenario.viewModel.platformState.value.surface)
    }

    private fun TestScope.createPromoterCancellationScenario(
        sessionImportedForActivation: Boolean,
    ): PromoterCancellationScenario {
        val store = FakePromoterActivationSessionStore()
        val repository = RegistrationAuthRepository(
            currentSession = completeSession().takeUnless { sessionImportedForActivation },
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(
                        promoterActivationContext(sessionImportedForActivation),
                    )
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                promoterActivationSessionStore = store,
            ),
        )
        return PromoterCancellationScenario(
            repository = repository,
            store = store,
            viewModel = viewModel,
        )
    }

    private suspend fun TestScope.openAndCancelPromoterActivation(viewModel: AuthViewModel, callbackUrl: String) {
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.OpenPromoterActivation(callbackUrl))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.CancelPromoterActivation)
        advanceUntilIdle()
    }

    @Test
    fun successfulImportedPromoterActivationClearsMarkerBeforeCompletion() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(operationEvents = events)
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = true))
                },
                onPromoterActivation = {
                    DomainResult.Success(
                        PromoterActivationResult(
                            session = completeSession(),
                            organizationId = TEST_ORGANIZATION_ID,
                            listingId = TEST_LISTING_ID,
                        ),
                    )
                },
                operationEvents = events,
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()
        events.clear()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.ActivatePromoterWithPassword(TEST_PASSWORD))
        advanceUntilIdle()

        assertEquals(
            listOf("marker-mark", "promoter-callback", "promoter-activate", "marker-clear"),
            events,
        )
        assertEquals(PromoterActivationStage.Completed, viewModel.promoterActivationState.value.stage)
        assertFalse(store.pending)
        assertTrue(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun successfulPromoterActivationStaysFailClosedUntilMarkerClearCanBeRetried() = runTest {
        val store = FakePromoterActivationSessionStore(clearSucceeds = false)
        val viewModel = createViewModel(
            repository = successfulPromoterRepository(sessionImportedForActivation = true),
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.ActivatePromoterWithPassword(TEST_PASSWORD))
        advanceUntilIdle()

        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertTrue(viewModel.promoterActivationState.value.retryAvailable)
        assertFalse(viewModel.state.value.hasSession)
        assertTrue(store.pending)

        store.clearSucceeds = true
        viewModel.onIntent(AuthIntent.RetryPromoterActivationLink)
        advanceUntilIdle()

        assertEquals(PromoterActivationStage.Completed, viewModel.promoterActivationState.value.stage)
        assertEquals(2, store.clearCallCount)
        assertFalse(store.pending)
        assertTrue(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun importedPromoterCancellationRetriesMarkerClearBeforeClosing() = runTest {
        val store = FakePromoterActivationSessionStore(clearSucceeds = false)
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = true))
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.CancelPromoterActivation)
        advanceUntilIdle()

        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertTrue(store.pending)
        assertEquals(AuthSurface.PromoterActivation, viewModel.platformState.value.surface)

        store.clearSucceeds = true
        viewModel.onIntent(AuthIntent.RetryPromoterActivationLink)
        advanceUntilIdle()

        assertEquals(2, repository.signOutCallCount)
        assertEquals(2, store.clearCallCount)
        assertFalse(store.pending)
        assertEquals(AuthSurface.Hidden, viewModel.platformState.value.surface)
    }

    @Test
    fun importedPromoterCancellationKeepsMarkerWhenSignOutFails() = runTest {
        val store = FakePromoterActivationSessionStore()
        val repository = RegistrationAuthRepository(
            authBehavior = RegistrationAuthBehavior(signOutFailure = DomainError.NetworkUnavailable()),
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = true))
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.CancelPromoterActivation)
        advanceUntilIdle()

        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertTrue(viewModel.promoterActivationState.value.retryAvailable)
        assertEquals(1, repository.signOutCallCount)
        assertEquals(0, store.clearCallCount)
        assertTrue(store.pending)
        assertEquals(AuthSurface.PromoterActivation, viewModel.platformState.value.surface)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelPromoterConcurrencyTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun promoterCallbackCannotCancelAccountDeletionInProgress() = runTest {
        val deletion = BlockedAccountDeletion()
        val promoterStore = FakePromoterActivationSessionStore()
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
                },
                onAccountDeletion = deletion::delete,
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                promoterActivationSessionStore = promoterStore,
            ),
        )
        val effects = viewModel.effects.produceIn(backgroundScope)
        advanceUntilIdle()

        startPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)
        assertTrue(deletion.started.isCompleted)

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        runCurrent()

        assertFalse(deletion.cancelled)
        assertTrue(viewModel.accessState.value.accountDeletionInProgress)
        assertEquals(0, repository.promoterCallbackCallCount)
        assertEquals(0, promoterStore.markCallCount)

        deletion.allowCompletion.complete(Unit)
        advanceUntilIdle()

        assertFalse(deletion.cancelled)
        assertEquals(AuthEffect.AccountDeleted, effects.receive())
    }

    @Test
    fun promoterCallbackWaitsForSlowRestoreAndIsProcessedExactlyOnce() = runTest {
        val restoreStarted = CompletableDeferred<Unit>()
        val allowRestoreToComplete = CompletableDeferred<Unit>()
        val store = FakePromoterActivationSessionStore()
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onGetCurrentSession = {
                    restoreStarted.complete(Unit)
                    allowRestoreToComplete.await()
                },
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = true))
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        runCurrent()
        assertTrue(restoreStarted.isCompleted)

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        runCurrent()

        assertFalse(viewModel.isSessionRestoreComplete.value)
        assertEquals(0, repository.promoterCallbackCallCount)
        assertEquals(0, store.markCallCount)

        allowRestoreToComplete.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.isSessionRestoreComplete.value)
        assertEquals(1, repository.promoterCallbackCallCount)
        assertEquals(1, store.markCallCount)
        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
    }

    @Test
    fun promoterCallbackWaitsForSignOutNavigationWithoutCancellingSignOut() = runTest {
        val signOutStarted = CompletableDeferred<Unit>()
        val allowSignOut = CompletableDeferred<Unit>()
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(
                onSignOut = {
                    signOutStarted.complete(Unit)
                    allowSignOut.await()
                },
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = true))
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
        )
        val effects = viewModel.effects.produceIn(backgroundScope)
        advanceUntilIdle()

        viewModel.confirmSignOut()
        runCurrent()
        assertTrue(signOutStarted.isCompleted)

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        runCurrent()

        assertEquals(0, repository.promoterCallbackCallCount)
        assertTrue(viewModel.accessState.value.signOutInProgress)

        allowSignOut.complete(Unit)
        runCurrent()

        assertEquals(AuthEffect.SignedOut, effects.receive())
        assertEquals(0, repository.promoterCallbackCallCount)

        viewModel.onIntent(AuthIntent.SignOutNavigationHandled)
        advanceUntilIdle()

        assertPromoterCallbackReady(viewModel, repository)
    }

    @Test
    fun promoterCallbackWaitsForPasswordSignInOperation() = runTest {
        val signInStarted = CompletableDeferred<Unit>()
        val allowSignIn = CompletableDeferred<Unit>()
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onSignInWithEmail = {
                    signInStarted.complete(Unit)
                    allowSignIn.await()
                },
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
        )
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.OpenSignIn())
        viewModel.onIntent(AuthIntent.ChangeSignInEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.ContinueFromSignInEmail)
        viewModel.onIntent(AuthIntent.SubmitSignInPassword(TEST_PASSWORD))
        runCurrent()
        assertTrue(signInStarted.isCompleted)

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        runCurrent()

        assertEquals(0, repository.promoterCallbackCallCount)

        allowSignIn.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, repository.promoterCallbackCallCount)
        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
    }

    @Test
    fun pkceKillWindowNeverArmsCleanupForPreexistingSession() = runTest {
        val store = FakePromoterActivationSessionStore()
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    throw CancellationException("Simulated process interruption")
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                promoterActivationSessionStore = store,
            ),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(TEST_PROMOTER_CALLBACK, repository.lastPromoterCallbackUrl)
        assertEquals(0, store.markCallCount)
        assertEquals(0, store.clearCallCount)
        assertFalse(store.pending)
        assertTrue(viewModel.state.value.isAuthenticated)

        val restartedViewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                promoterActivationSessionStore = store,
            ),
        )
        advanceUntilIdle()

        assertEquals(0, repository.signOutCallCount)
        assertTrue(restartedViewModel.state.value.isAuthenticated)
    }

    @Test
    fun pkceKillWindowArmsCleanupWithoutPreexistingSession() = runTest {
        val store = FakePromoterActivationSessionStore()
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    throw CancellationException("Simulated process interruption")
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()

        assertEquals(1, store.markCallCount)
        assertTrue(store.pending)

        val restartedViewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()

        assertEquals(1, repository.signOutCallCount)
        assertFalse(store.pending)
        assertFalse(restartedViewModel.state.value.hasSession)
    }

    @Test
    fun tokenOnlyCallbackNeverArmsTemporarySessionCleanup() = runTest {
        val store = FakePromoterActivationSessionStore()
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(promoterActivationSessionStore = store),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_CALLBACK))
        advanceUntilIdle()

        assertEquals(1, repository.promoterCallbackCallCount)
        assertEquals(0, store.markCallCount)
        assertEquals(0, store.clearCallCount)
        assertFalse(store.pending)
        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelPostAuthenticationTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun signOutStopsBeforeServerWhenConsentRevocationCannotBePersisted() = runTest {
        val repository = RegistrationAuthRepository(currentSession = completeSession())
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                revokeConsent = { false },
            ),
        )
        advanceUntilIdle()

        viewModel.confirmSignOut()
        advanceUntilIdle()

        assertEquals(0, repository.signOutCallCount)
        assertTrue(viewModel.state.value.isAuthenticated)
        assertFalse(viewModel.accessState.value.signOutConfirmationVisible)
        assertFalse(viewModel.accessState.value.signOutInProgress)
        assertEquals(
            strings.settings.privacyPersistenceError,
            viewModel.accessState.value.signOutErrorMessage,
        )
    }

    @Test
    fun completedAccountOtpFromRegistrationSignsOutAndRequiresPassword() = runTest {
        val repository = RegistrationAuthRepository(verifiedSession = completeSession())
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
        )
        val effects = viewModel.effects.produceIn(backgroundScope)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        assertFalse(viewModel.state.value.isAuthenticated)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.hasSession)
        assertEquals(AuthSurface.SignIn, viewModel.platformState.value.surface)
        assertEquals(SignInStep.Password, viewModel.accessState.value.signInStep)
        assertEquals(TEST_EMAIL, viewModel.accessState.value.signInEmail)
        assertEquals(1, repository.signOutCallCount)
        assertTrue(effects.tryReceive().isFailure)
    }

    @Test
    fun completedAccountOtpSignOutFailureShowsRetryablePasswordScreen() = runTest {
        val journeyStore = FakeAuthJourneyStore()
        val repository = RegistrationAuthRepository(
            verifiedSession = completeSession(),
            authBehavior = RegistrationAuthBehavior(
                signOutFailure = DomainError.NetworkUnavailable(),
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(authJourneyStore = journeyStore),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        assertFalse(viewModel.state.value.isAuthenticated)
        advanceUntilIdle()

        assertEquals(AuthSurface.SignIn, viewModel.platformState.value.surface)
        assertEquals(SignInStep.Password, viewModel.accessState.value.signInStep)
        assertEquals(TEST_EMAIL, viewModel.accessState.value.signInEmail)
        assertEquals(strings.offlineBanner, viewModel.accessState.value.errorMessage)
        assertEquals(RegistrationStep.Email, viewModel.registrationState.value.step)
        assertEquals(InterruptedAuthJourney.Registration, journeyStore.read())
        assertFalse(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun passwordSignInAfterRedirectFailureClearsMarkerBeforeRestart() = runTest {
        val journeyStore = FakeAuthJourneyStore()
        val repository = RegistrationAuthRepository(
            verifiedSession = completeSession(),
            authBehavior = RegistrationAuthBehavior(
                signOutFailure = DomainError.NetworkUnavailable(),
            ),
        )
        val overrides = AuthTestOverrides(
            authJourneyStore = journeyStore,
        )
        val viewModel = createViewModel(repository = repository, scope = this, overrides = overrides)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitSignInPassword(TEST_PASSWORD))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isAuthenticated)
        assertEquals(AuthSurface.Hidden, viewModel.platformState.value.surface)
        assertEquals(InterruptedAuthJourney.None, journeyStore.read())
        assertEquals(1, repository.signOutCallCount)

        val restoredViewModel = createViewModel(repository = repository, scope = this, overrides = overrides)
        advanceUntilIdle()

        assertTrue(restoredViewModel.state.value.isAuthenticated)
        assertEquals(AuthSurface.Hidden, restoredViewModel.platformState.value.surface)
        assertEquals(1, repository.signOutCallCount)
    }

    @Test
    fun passwordSignInMarkerClearFailureNeverPublishesAuthenticatedState() = runTest {
        val journeyStore = FakeAuthJourneyStore(clearsSucceed = false)
        val repository = RegistrationAuthRepository(
            verifiedSession = completeSession(),
            authBehavior = RegistrationAuthBehavior(
                signOutFailure = DomainError.NetworkUnavailable(),
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                authJourneyStore = journeyStore,
            ),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitSignInPassword(TEST_PASSWORD))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isAuthenticated)
        assertEquals(AuthSurface.SignIn, viewModel.platformState.value.surface)
        assertEquals(InterruptedAuthJourney.Registration, journeyStore.read())
        assertEquals(strings.authInvalidInput, viewModel.accessState.value.errorMessage)
    }

    @Test
    fun recoveryBackToEmailCannotBypassCooldownForSameAddress() = runTest {
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(repository = repository, scope = this)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.OpenSignIn())
        viewModel.onIntent(AuthIntent.ChangeSignInEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.ContinueFromSignInEmail)
        viewModel.onIntent(AuthIntent.OpenPasswordRecovery)
        viewModel.onIntent(AuthIntent.RequestRecoveryOtp)
        runCurrent()

        assertEquals(1, repository.recoveryRequestCount)
        assertEquals(PasswordRecoveryStep.Otp, viewModel.passwordRecoveryState.value.step)

        viewModel.onIntent(AuthIntent.Back)
        runCurrent()
        viewModel.onIntent(AuthIntent.RequestRecoveryOtp)
        runCurrent()

        assertEquals(1, repository.recoveryRequestCount)
        assertEquals(PasswordRecoveryStep.Email, viewModel.passwordRecoveryState.value.step)
        assertEquals(strings.registrationOtpWait, viewModel.passwordRecoveryState.value.errorMessage)
        assertTrue(viewModel.accessState.value.recoveryResendSecondsRemaining > 0)
    }
}

private class AccountDeletionProbe(
    private val failFirstAttempt: Boolean = false,
) {
    val requests = mutableListOf<AccountDeletionRequest>()
    var generatedKeyCount = 0
        private set

    val idempotencyKeyProvider = IdempotencyKeyProvider {
        generatedKeyCount += 1
        TEST_IDEMPOTENCY_KEY
    }

    suspend fun delete(request: AccountDeletionRequest): DomainResult<Unit> {
        requests += request
        return if (failFirstAttempt && requests.size == 1) {
            DomainResult.Failure(DomainError.NetworkUnavailable())
        } else {
            DomainResult.Success(Unit)
        }
    }
}

private fun TestScope.createAccountDeletionViewModel(
    probe: AccountDeletionProbe,
    revokeConsent: () -> Boolean = { true },
): AuthViewModel = createViewModel(
    repository = RegistrationAuthRepository(
        currentSession = completeSession(),
        hooks = RegistrationAuthHooks(onAccountDeletion = probe::delete),
    ),
    scope = this,
    overrides = AuthTestOverrides(
        idempotencyKeyProvider = probe.idempotencyKeyProvider,
        revokeConsent = revokeConsent,
    ),
)

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.submitPasswordAccountDeletion(viewModel: AuthViewModel, confirmation: String) {
    viewModel.onIntent(
        AuthIntent.DeleteAccountWithPassword(
            password = TEST_PASSWORD,
            confirmation = confirmation,
        ),
    )
    advanceUntilIdle()
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.startPasswordAccountDeletion(viewModel: AuthViewModel, confirmation: String) {
    viewModel.onIntent(AuthIntent.RequestAccountDeletion)
    viewModel.onIntent(
        AuthIntent.DeleteAccountWithPassword(
            password = TEST_PASSWORD,
            confirmation = confirmation,
        ),
    )
    runCurrent()
}

private data class PromoterCancellationScenario(
    val repository: RegistrationAuthRepository,
    val store: FakePromoterActivationSessionStore,
    val viewModel: AuthViewModel,
)

private fun successfulPromoterRepository(sessionImportedForActivation: Boolean): RegistrationAuthRepository =
    RegistrationAuthRepository(
        hooks = RegistrationAuthHooks(
            onPromoterCallback = {
                DomainResult.Success(
                    promoterActivationContext(sessionImportedForActivation),
                )
            },
            onPromoterActivation = { request ->
                assertEquals(TEST_PROMOTER_INVITE_TOKEN, request.inviteToken)
                DomainResult.Success(
                    PromoterActivationResult(
                        session = completeSession(),
                        organizationId = TEST_ORGANIZATION_ID,
                        listingId = TEST_LISTING_ID,
                    ),
                )
            },
        ),
    )

private class BlockedAccountDeletion {
    val started = CompletableDeferred<Unit>()
    val allowCompletion = CompletableDeferred<Unit>()
    var cancelled = false
        private set

    suspend fun delete(request: AccountDeletionRequest): DomainResult<Unit> {
        check(request.idempotencyKey.isNotBlank())
        started.complete(Unit)
        try {
            allowCompletion.await()
        } catch (exception: CancellationException) {
            cancelled = true
            throw exception
        }
        return DomainResult.Success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.createViewModel(
    repository: RegistrationAuthRepository,
    scope: TestScope,
    overrides: AuthTestOverrides = AuthTestOverrides(),
): AuthViewModel {
    val clock = object : ClockProvider {
        override fun nowEpochMilliseconds(): Long = TEST_EPOCH_MILLISECONDS + scope.testScheduler.currentTime
    }
    return AuthViewModel(
        dependencies = AuthViewModelDependencies(
            authPresenter = AuthPresenter(repository),
            registrationPresenter = RegistrationPresenter(
                repository,
                RegistrationCatalogRepository(),
                clock,
                RegistrationReducer(),
            ),
            passwordRecoveryPresenter = PasswordRecoveryPresenter(repository, clock),
            authJourneyStore = overrides.authJourneyStore,
            promoterActivationSessionStore = overrides.promoterActivationSessionStore,
            googleIdentityProvider = overrides.googleIdentityProvider,
            googleIdentityUnavailableMessage = TEST_GOOGLE_UNAVAILABLE_MESSAGE,
            idempotencyKeyProvider = overrides.idempotencyKeyProvider,
            clockProvider = clock,
            track = overrides.track,
            revokeObservabilityConsent = overrides.revokeConsent,
        ),
        strings = stringsFor(AppLocale.French),
        coroutineScope = this,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.completeRegistrationProfile(viewModel: AuthViewModel) {
    viewModel.onIntent(AuthIntent.OpenRegistration())
    viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
    viewModel.onIntent(AuthIntent.RequestOtp)
    advanceUntilIdle()
    viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
    advanceUntilIdle()
    viewModel.onIntent(AuthIntent.SubmitPassword(TEST_PASSWORD))
    advanceUntilIdle()
    viewModel.onIntent(AuthIntent.ChangeFirstName("Afi"))
    viewModel.onIntent(AuthIntent.ChangeLastName("Soglo"))
    viewModel.onIntent(AuthIntent.SelectCity(TEST_CITY_ID))
    viewModel.onIntent(AuthIntent.SelectCurrency(KwaborCurrency.Eur))
    LegalDocumentType.entries.forEach { type ->
        viewModel.onIntent(AuthIntent.ChangeLegalAcceptance(type, accepted = true))
    }
    assertEquals(RegistrationStep.Profile, viewModel.registrationState.value.step)
    assertEquals(null, viewModel.registrationState.value.errorMessage, viewModel.registrationState.value.toString())
}

private data class AuthTestOverrides(
    val authJourneyStore: AuthJourneyStore = FakeAuthJourneyStore(),
    val promoterActivationSessionStore: PromoterActivationSessionStore =
        FakePromoterActivationSessionStore(),
    val googleIdentityProvider: GoogleIdentityProvider = FakeGoogleIdentityProvider(),
    val idempotencyKeyProvider: IdempotencyKeyProvider = IdempotencyKeyProvider { TEST_IDEMPOTENCY_KEY },
    val revokeConsent: () -> Boolean = { true },
    val track: (com.kwabor.shared.domain.observability.AnalyticsEvent) -> Unit = {},
)

private class FakePromoterActivationSessionStore(
    var pending: Boolean = false,
    var markSucceeds: Boolean = true,
    var clearSucceeds: Boolean = true,
    private val operationEvents: MutableList<String>? = null,
) : PromoterActivationSessionStore {
    var readCallCount: Int = 0
        private set
    var markCallCount: Int = 0
        private set
    var clearCallCount: Int = 0
        private set

    override fun hasPendingImportedSession(): Boolean {
        readCallCount += 1
        operationEvents?.add("marker-read")
        return pending
    }

    override fun markImportedSessionPending(): Boolean {
        markCallCount += 1
        operationEvents?.add("marker-mark")
        pending = true
        return markSucceeds
    }

    override fun clear(): Boolean {
        clearCallCount += 1
        operationEvents?.add("marker-clear")
        if (clearSucceeds) pending = false
        return clearSucceeds
    }
}

private class FakeGoogleIdentityProvider(
    private val result: GoogleIdentityResult = GoogleIdentityResult.Unavailable,
) : GoogleIdentityProvider {
    override val isConfigured: Boolean = result !is GoogleIdentityResult.Unavailable

    var clearCredentialStateCallCount: Int = 0
        private set
    var acquireIdTokenCallCount: Int = 0
        private set

    override suspend fun acquireIdToken(): GoogleIdentityResult {
        acquireIdTokenCallCount += 1
        return result
    }

    override suspend fun clearCredentialState() {
        clearCredentialStateCallCount += 1
    }
}

private data class RegistrationAuthBehavior(
    val signInSession: AuthSession = completeSession(),
    val recoverySession: AuthSession = passwordRecoverySession(),
    val signOutFailure: DomainError? = null,
)

private data class RegistrationAuthFailurePlan(
    val currentSessionFailures: Int = 0,
    val failFirstLegalDocumentsLoad: Boolean = false,
    val onboardingCompletionFailures: Int = 0,
)

private data class RegistrationAuthHooks(
    val onGetCurrentSession: suspend () -> Unit = {},
    val onSignInWithEmail: suspend () -> Unit = {},
    val onSignOut: suspend () -> Unit = {},
    val onCompleteOnboarding: () -> Unit = {},
    val onPromoterCallback: suspend (String) -> DomainResult<PromoterActivationContext> = {
        DomainResult.Failure(DomainError.Validation("error.auth.unused"))
    },
    val onPromoterActivation: (PromoterActivationRequest) -> DomainResult<PromoterActivationResult> = {
        DomainResult.Failure(DomainError.Validation("error.auth.unused"))
    },
    val onAccountDeletion: suspend (AccountDeletionRequest) -> DomainResult<Unit> = {
        DomainResult.Failure(DomainError.Validation("error.auth.unused"))
    },
    val operationEvents: MutableList<String>? = null,
)

private class RegistrationAuthRepository(
    currentSession: AuthSession? = null,
    private val verifiedSession: AuthSession = onboardingSession(),
    private val authBehavior: RegistrationAuthBehavior = RegistrationAuthBehavior(),
    failurePlan: RegistrationAuthFailurePlan = RegistrationAuthFailurePlan(),
    private val hooks: RegistrationAuthHooks = RegistrationAuthHooks(),
) : AuthRepository {
    private var session: AuthSession? = currentSession
    private var legalDocumentsLoadCount = 0
    private var getCurrentSessionFailuresRemaining = failurePlan.currentSessionFailures
    private val failFirstLegalDocumentsLoad = failurePlan.failFirstLegalDocumentsLoad
    private var onboardingCompletionFailuresRemaining = failurePlan.onboardingCompletionFailures

    var signOutCalled = false
        private set
    var passwordUpdateCount = 0
        private set
    var otpVerificationCount = 0
        private set
    var completeOnboardingCallCount = 0
        private set
    var signInCallCount = 0
        private set
    var recoveryRequestCount = 0
        private set
    var recoveryCompletionCount = 0
        private set
    var signOutCallCount = 0
        private set
    var getCurrentSessionCallCount = 0
        private set
    var promoterCallbackCallCount = 0
        private set
    var lastPromoterCallbackUrl: String? = null
        private set
    var lastSocialSignInRequest: SocialSignInRequest? = null
        private set

    override suspend fun getCurrentSession(): DomainResult<AuthSession?> {
        getCurrentSessionCallCount += 1
        hooks.operationEvents?.add("get-current-session")
        hooks.onGetCurrentSession()
        if (getCurrentSessionFailuresRemaining > 0) {
            getCurrentSessionFailuresRemaining -= 1
            return DomainResult.Failure(DomainError.NetworkUnavailable())
        }
        return DomainResult.Success(session)
    }

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession> {
        otpVerificationCount += 1
        val verified = verifiedSession
        session = verified
        return DomainResult.Success(verified)
    }

    override suspend fun setInitialPassword(password: String): DomainResult<Unit> {
        passwordUpdateCount += 1
        return DomainResult.Success(Unit)
    }

    override suspend fun listActiveLegalDocuments(locale: AppLocale): DomainResult<List<LegalDocumentRevision>> {
        legalDocumentsLoadCount += 1
        if (failFirstLegalDocumentsLoad && legalDocumentsLoadCount == 1) {
            return DomainResult.Failure(DomainError.NetworkUnavailable())
        }
        return DomainResult.Success(LegalDocumentType.entries.map { type -> legalDocument(type) })
    }

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): DomainResult<AuthSession> {
        completeOnboardingCallCount += 1
        hooks.onCompleteOnboarding()
        if (onboardingCompletionFailuresRemaining > 0) {
            onboardingCompletionFailuresRemaining -= 1
            return DomainResult.Failure(DomainError.NetworkUnavailable())
        }
        val completed = completeSession()
        session = completed
        return DomainResult.Success(completed)
    }

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> {
        signInCallCount += 1
        hooks.onSignInWithEmail()
        session = authBehavior.signInSession
        return DomainResult.Success(authBehavior.signInSession)
    }

    override suspend fun requestPasswordRecovery(email: String): DomainResult<Unit> {
        recoveryRequestCount += 1
        return DomainResult.Success(Unit)
    }

    override suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): DomainResult<AuthSession> {
        session = authBehavior.recoverySession
        return DomainResult.Success(authBehavior.recoverySession)
    }

    override suspend fun completePasswordRecovery(newPassword: String): DomainResult<Unit> {
        recoveryCompletionCount += 1
        session = null
        return DomainResult.Success(Unit)
    }

    override suspend fun cancelPasswordRecovery(): DomainResult<Unit> {
        session = null
        return DomainResult.Success(Unit)
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> {
        lastSocialSignInRequest = request
        return DomainResult.Success(authBehavior.signInSession)
    }

    override suspend fun handlePromoterActivationCallback(
        callbackUrl: String,
    ): DomainResult<PromoterActivationContext> {
        promoterCallbackCallCount += 1
        lastPromoterCallbackUrl = callbackUrl
        hooks.operationEvents?.add("promoter-callback")
        return hooks.onPromoterCallback(callbackUrl)
    }

    override suspend fun activatePromoterInvite(
        request: PromoterActivationRequest,
    ): DomainResult<PromoterActivationResult> {
        hooks.operationEvents?.add("promoter-activate")
        return hooks.onPromoterActivation(request)
    }

    override suspend fun deleteAccount(request: AccountDeletionRequest): DomainResult<Unit> =
        hooks.onAccountDeletion(request)

    override suspend fun signOut(): DomainResult<Unit> {
        signOutCallCount += 1
        signOutCalled = true
        hooks.operationEvents?.add("sign-out")
        hooks.onSignOut()
        authBehavior.signOutFailure?.let { return DomainResult.Failure(it) }
        session = null
        return DomainResult.Success(Unit)
    }
}

private class FakeAuthJourneyStore(
    private var journey: InterruptedAuthJourney = InterruptedAuthJourney.None,
    private val clearsSucceed: Boolean = true,
    private val writesSucceed: Boolean = true,
) : AuthJourneyStore {
    override fun read(): InterruptedAuthJourney = journey

    override fun write(journey: InterruptedAuthJourney): Boolean {
        if (!writesSucceed) return false
        this.journey = journey
        return true
    }

    override fun clear(): Boolean {
        if (!clearsSucceed) return false
        journey = InterruptedAuthJourney.None
        return true
    }
}

private class RegistrationCatalogRepository : CatalogRepository {
    override suspend fun listCities(): DomainResult<List<City>> = DomainResult.Success(
        listOf(City(id = TEST_CITY_ID, name = "Cotonou", latitude = 6.37, longitude = 2.39)),
    )

    override suspend fun listCategories(): DomainResult<List<Category>> = DomainResult.Success(emptyList())

    override suspend fun listListings(
        filters: ListingFilters,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = unexpected()

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = unexpected()

    override suspend fun getListingDetail(listingId: String): DomainResult<CatalogDetail> = unexpected()

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        unexpected()

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = unexpected()

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> = unexpected()

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> = unexpected()

    override suspend fun favoriteListing(listingId: String): DomainResult<ListingViewerInteraction> = unexpected()

    override suspend fun unfavoriteListing(listingId: String): DomainResult<ListingViewerInteraction> = unexpected()
}

private fun <T> unexpected(): DomainResult<T> = DomainResult.Failure(DomainError.Unexpected())

private fun assertSessionRestoreIsBlocked(viewModel: AuthViewModel) {
    assertTrue(viewModel.isSessionRestoreComplete.value)
    assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
    assertEquals(AuthSurface.SessionRestoreFailure, viewModel.platformState.value.surface)
    assertFalse(viewModel.state.value.hasSession)
}

private fun AuthViewModel.confirmSignOut() {
    onIntent(AuthIntent.RequestSignOut)
    onIntent(AuthIntent.ConfirmSignOut)
}

private fun assertPromoterCallbackReady(viewModel: AuthViewModel, repository: RegistrationAuthRepository) {
    assertEquals(1, repository.promoterCallbackCallCount)
    assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
}

private fun legalDocument(type: LegalDocumentType): LegalDocumentRevision = LegalDocumentRevision(
    id = "document-${type.name}",
    type = type,
    version = "2026-07",
    locale = AppLocale.French,
    url = "https://kwabor.test/legal/${type.name.lowercase()}",
    effectiveAtEpochMilliseconds = TEST_EPOCH_MILLISECONDS,
)

private fun promoterActivationContext(sessionImportedForActivation: Boolean): PromoterActivationContext =
    PromoterActivationContext(
        inviteToken = TEST_PROMOTER_INVITE_TOKEN,
        organizationId = TEST_ORGANIZATION_ID,
        listingId = TEST_LISTING_ID,
        businessName = "Chez Afi",
        sessionImportedForActivation = sessionImportedForActivation,
    )

private fun onboardingSession(): AuthSession = AuthSession(
    userId = "user-1",
    email = TEST_EMAIL,
    expiresAtEpochMilliseconds = TEST_EPOCH_MILLISECONDS + 3_600_000L,
    accountSetupStatus = AccountSetupStatus.OnboardingRequired,
)

private fun completeSession(): AuthSession = onboardingSession().copy(accountSetupStatus = AccountSetupStatus.Complete)

private fun passwordRecoverySession(): AuthSession = completeSession().copy(
    purpose = AuthSessionPurpose.PasswordRecovery,
)

private const val TEST_EMAIL = "user@kwabor.test"
private const val TEST_OTP = "123456"
private const val TEST_PASSWORD = "mot-de-passe-solide"
private const val TEST_CITY_ID = "cotonou"
private const val TEST_GOOGLE_UNAVAILABLE_MESSAGE = "Connexion Google indisponible"
private const val TEST_IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000001"
private const val TEST_GOOGLE_ID_TOKEN = "google-id-token"
private const val TEST_GOOGLE_RAW_NONCE = "google-raw-nonce"
private val TEST_PROMOTER_INVITE_TOKEN = "a".repeat(64)
private val TEST_PROMOTER_CALLBACK =
    "kwabor://auth/promoter-activate?token=$TEST_PROMOTER_INVITE_TOKEN"
private val TEST_PROMOTER_PKCE_CALLBACK =
    "$TEST_PROMOTER_CALLBACK&code=${"b".repeat(32)}"
private const val TEST_ORGANIZATION_ID = "organization-1"
private const val TEST_LISTING_ID = "listing-1"
private const val TEST_EPOCH_MILLISECONDS = 1_783_800_000_000L
