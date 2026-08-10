package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.AccountDeletionProviderCleanupStore
import com.kwabor.android.auth.AuthJourneyStore
import com.kwabor.android.auth.GoogleIdentityProvider
import com.kwabor.android.auth.GoogleIdentityResult
import com.kwabor.android.auth.IdempotencyKeyProvider
import com.kwabor.android.auth.InterruptedAuthJourney
import com.kwabor.android.auth.PromoterActivationSessionStore
import com.kwabor.shared.domain.auth.AccountDeletionOutcome
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCleanupPendingCancellation
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
import com.kwabor.shared.presentation.interaction.InteractionAccountDeletionPurgeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelAccountDeletionSafetyTest {
    private val strings = stringsFor(AppLocale.French)

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
    fun accountDeletionReusesPrivateIdempotencyKeyAfterExplicitRejection() = runTest {
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

    @Test
    fun accountDeletionCommitsProviderCleanupMarkerBeforeRemoteBoundary() = runTest {
        val operations = mutableListOf<String>()
        val store = FakeAccountDeletionProviderCleanupStore(operationEvents = operations)
        val google = FakeGoogleIdentityProvider(operationEvents = operations)
        val deletion = AccountDeletionProbe(operationEvents = operations)
        val viewModel = createAccountDeletionViewModel(
            probe = deletion,
            googleIdentityProvider = google,
            providerCleanupStore = store,
        )
        advanceUntilIdle()
        operations.clear()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertTrue(operations.indexOf("provider-cleanup-marker-mark") < operations.indexOf("remote-delete"))
        assertTrue(operations.indexOf("remote-delete") < operations.indexOf("google-clear"))
        assertFalse(store.pending)
    }

    @Test
    fun providerCleanupMarkerWriteFailureStopsBeforeRemoteAndReleasesFence() = runTest {
        val store = FakeAccountDeletionProviderCleanupStore(markSucceeds = false)
        val google = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe()
        val deletion = AccountDeletionProbe()
        val viewModel = createAccountDeletionViewModel(
            probe = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = google,
            providerCleanupStore = store,
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertTrue(deletion.requests.isEmpty())
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
        assertEquals(0, google.clearCredentialStateCallCount)
        assertFalse(store.pending)
        assertTrue(viewModel.state.value.isAuthenticated)
        assertEquals(
            strings.settings.privacyPersistenceError,
            viewModel.accessState.value.accountDeletionErrorMessage,
        )
    }

    @Test
    fun explicitRejectionDisarmsMarkerWithoutClearingProviderState() = runTest {
        val store = FakeAccountDeletionProviderCleanupStore()
        val google = FakeGoogleIdentityProvider()
        val deletion = AccountDeletionProbe(failFirstAttempt = true)
        val viewModel = createAccountDeletionViewModel(
            probe = deletion,
            googleIdentityProvider = google,
            providerCleanupStore = store,
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(1, deletion.requests.size)
        assertEquals(1, store.markCallCount)
        assertEquals(1, store.clearCallCount)
        assertEquals(0, google.clearCredentialStateCallCount)
        assertFalse(store.pending)
        assertTrue(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun rejectedDeletionMarkerClearFailureReleasesFenceButFailsAuthClosed() = runTest {
        val store = FakeAccountDeletionProviderCleanupStore(clearSucceeds = false)
        val google = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe()
        val deletion = AccountDeletionProbe(failFirstAttempt = true)
        val viewModel = createAccountDeletionViewModel(
            probe = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = google,
            providerCleanupStore = store,
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
        assertEquals(0, google.clearCredentialStateCallCount)
        assertTrue(store.pending)
        assertFalse(viewModel.state.value.hasSession)
        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertEquals(AuthSurface.SessionRestoreFailure, viewModel.platformState.value.surface)
    }

    @Test
    fun accountDeletionPurgesCapturedAccountBeforeRemoteAndKeepsSuccessBlocked() = runTest {
        val operations = mutableListOf<String>()
        val deletion = AccountDeletionProbe(operationEvents = operations)
        val interactions = AccountDeletionInteractionLifecycleProbe(operationEvents = operations)
        val viewModel = createAccountDeletionViewModel(deletion, interactionLifecycle = interactions)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(listOf("purge:$TEST_ACCOUNT_ID", "remote-delete"), operations)
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.purgedAccountIds)
        assertEquals(TEST_ACCOUNT_ID, deletion.requests.single().expectedAccountId)
        assertTrue(interactions.resumedAccountIds.isEmpty())
    }

    @Test
    fun accountDeletionStopsBeforeRemoteWhenInteractionPurgeFails() = runTest {
        val deletion = AccountDeletionProbe()
        val interactions = AccountDeletionInteractionLifecycleProbe(
            purgeResult = DomainResult.Failure(DomainError.LocalStorageUnavailable()),
        )
        val viewModel = createAccountDeletionViewModel(deletion, interactionLifecycle = interactions)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.purgedAccountIds)
        assertTrue(interactions.resumedAccountIds.isEmpty())
        assertTrue(deletion.requests.isEmpty())
        assertEquals(0, deletion.generatedKeyCount)
        assertEquals(
            strings.settings.privacyPersistenceError,
            viewModel.accessState.value.accountDeletionErrorMessage,
        )
    }

    @Test
    fun explicitRemoteDeletionFailureResumesCapturedAccount() = runTest {
        val operations = mutableListOf<String>()
        val deletion = AccountDeletionProbe(failFirstAttempt = true, operationEvents = operations)
        val interactions = AccountDeletionInteractionLifecycleProbe(operationEvents = operations)
        val viewModel = createAccountDeletionViewModel(deletion, interactionLifecycle = interactions)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(
            listOf("purge:$TEST_ACCOUNT_ID", "remote-delete", "resume:$TEST_ACCOUNT_ID"),
            operations,
        )
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
        assertFalse(viewModel.accessState.value.accountDeletionInProgress)
    }

    @Test
    fun unknownRemoteOutcomeKeepsFenceAndASecondViewModelCannotAcquireOrResumeIt() = runTest {
        val operations = mutableListOf<String>()
        val interactions = AccountDeletionInteractionLifecycleProbe(
            subsequentPurgeResult = DomainResult.Success(
                InteractionAccountDeletionPurgeOutcome.AlreadyBlocked,
            ),
            operationEvents = operations,
        )
        val firstDeletion = AccountDeletionProbe(
            outcome = AccountDeletionOutcome.OutcomeUnknown,
            operationEvents = operations,
        )
        val firstViewModel = createAccountDeletionViewModel(firstDeletion, interactionLifecycle = interactions)
        advanceUntilIdle()

        firstViewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(firstViewModel, strings.authDeleteAccountConfirmationPhrase)

        val secondDeletion = AccountDeletionProbe(operationEvents = operations)
        val secondViewModel = createAccountDeletionViewModel(secondDeletion, interactionLifecycle = interactions)
        advanceUntilIdle()
        secondViewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(secondViewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(
            listOf("purge:$TEST_ACCOUNT_ID", "remote-delete", "purge:$TEST_ACCOUNT_ID"),
            operations,
        )
        assertTrue(interactions.resumedAccountIds.isEmpty())
        assertTrue(secondDeletion.requests.isEmpty())
        assertEquals(
            strings.authAccountDeletionOutcomeUnknown,
            secondViewModel.accessState.value.accountDeletionErrorMessage,
        )
    }

    @Test
    fun deletedOutcomeRetainsMarkerAfterProviderFailureAndForegroundRetryDoesNotDuplicateEffect() = runTest {
        val store = FakeAccountDeletionProviderCleanupStore()
        val google = FakeGoogleIdentityProvider(clearSucceeds = false)
        val deletion = AccountDeletionProbe()
        val viewModel = createAccountDeletionViewModel(
            probe = deletion,
            googleIdentityProvider = google,
            providerCleanupStore = store,
        )
        val effects = viewModel.effects.produceIn(backgroundScope)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(AuthEffect.AccountDeleted, effects.receive())
        assertTrue(store.pending)
        assertEquals(1, google.clearCredentialStateCallCount)
        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertFalse(viewModel.state.value.hasSession)
        viewModel.onIntent(AuthIntent.AccountDeletionNavigationHandled)
        assertEquals(AuthSurface.SessionRestoreFailure, viewModel.platformState.value.surface)

        google.clearSucceeds = true
        viewModel.onForeground()
        advanceUntilIdle()

        assertFalse(store.pending)
        assertEquals(2, google.clearCredentialStateCallCount)
        assertEquals(AuthSessionRestoreStatus.Ready, viewModel.sessionRestoreStatus.value)
        assertTrue(effects.tryReceive().isFailure)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelAccountDeletionOutcomeSafetyTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun pendingLocalCleanupBlocksLiveAuthMutationsBehindRestoreRetry() = runTest {
        val viewModel = createAccountDeletionViewModel(
            AccountDeletionProbe(outcome = AccountDeletionOutcome.LocalCleanupPending),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)
        viewModel.onIntent(AuthIntent.OpenSignIn())

        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertEquals(AuthSurface.SessionRestoreFailure, viewModel.platformState.value.surface)
        assertFalse(viewModel.state.value.hasSession)
        assertEquals(
            strings.authAccountDeletionOutcomeUnknown,
            viewModel.accessState.value.accountDeletionErrorMessage,
        )
    }

    @Test
    fun rejectedDeletionWithPendingCleanupResumesFenceButKeepsAuthRestoreGateClosed() = runTest {
        val operations = mutableListOf<String>()
        val interactions = AccountDeletionInteractionLifecycleProbe(operationEvents = operations)
        val rejection = DomainError.Validation("error.auth.account_deletion_reauthentication_failed")
        val viewModel = createAccountDeletionViewModel(
            probe = AccountDeletionProbe(
                outcome = AccountDeletionOutcome.RejectedCleanupPending(rejection),
                operationEvents = operations,
            ),
            interactionLifecycle = interactions,
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(
            listOf("purge:$TEST_ACCOUNT_ID", "remote-delete", "resume:$TEST_ACCOUNT_ID"),
            operations,
        )
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertEquals(AuthSurface.SessionRestoreFailure, viewModel.platformState.value.surface)
        assertFalse(viewModel.state.value.hasSession)
    }

    @Test
    fun googleCancellationPurgesBeforePickerThenResumesCapturedAccount() = runTest {
        val operations = mutableListOf<String>()
        val deletion = AccountDeletionProbe(operationEvents = operations)
        val interactions = AccountDeletionInteractionLifecycleProbe(operationEvents = operations)
        val google = FakeGoogleIdentityProvider(
            result = GoogleIdentityResult.Cancelled,
            operationEvents = operations,
        )
        val viewModel = createAccountDeletionViewModel(
            probe = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = google,
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        viewModel.onIntent(
            AuthIntent.DeleteAccountWithGoogle(strings.authDeleteAccountConfirmationPhrase),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("purge:$TEST_ACCOUNT_ID", "google-acquire", "resume:$TEST_ACCOUNT_ID"),
            operations,
        )
        assertTrue(deletion.requests.isEmpty())
        assertFalse(viewModel.accessState.value.accountDeletionInProgress)
        assertEquals(null, viewModel.accessState.value.accountDeletionErrorMessage)
    }

    @Test
    fun unavailableGooglePickerResumesCapturedAccountWithNeutralError() = runTest {
        val operations = mutableListOf<String>()
        val interactions = AccountDeletionInteractionLifecycleProbe(operationEvents = operations)
        val viewModel = createAccountDeletionViewModel(
            probe = AccountDeletionProbe(operationEvents = operations),
            interactionLifecycle = interactions,
            googleIdentityProvider = FakeGoogleIdentityProvider(
                result = GoogleIdentityResult.Unavailable,
                operationEvents = operations,
            ),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        viewModel.onIntent(
            AuthIntent.DeleteAccountWithGoogle(strings.authDeleteAccountConfirmationPhrase),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("purge:$TEST_ACCOUNT_ID", "google-acquire", "resume:$TEST_ACCOUNT_ID"),
            operations,
        )
        assertEquals(TEST_GOOGLE_UNAVAILABLE_MESSAGE, viewModel.accessState.value.accountDeletionErrorMessage)
    }

    @Test
    fun accountSwitchAfterPurgeAbortsRemoteAndResumesCapturedAccount() = runTest {
        val operations = mutableListOf<String>()
        val deletion = AccountDeletionProbe(operationEvents = operations)
        lateinit var runtime: AuthViewModelRuntime
        val interactions = AccountDeletionInteractionLifecycleProbe(
            operationEvents = operations,
            afterPurge = {
                runtime.authState.value = runtime.authState.value.copy(
                    currentSession = completeSession().copy(userId = TEST_ACCOUNT_B_ID),
                )
            },
        )
        val fixture = createAccountDeletionCoordinatorFixture(deletion, interactions)
        runtime = fixture.runtime

        fixture.coordinator.handle(
            AuthIntent.DeleteAccountWithPassword(
                password = TEST_PASSWORD,
                confirmation = strings.authDeleteAccountConfirmationPhrase,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("purge:$TEST_ACCOUNT_ID", "resume:$TEST_ACCOUNT_ID"),
            operations,
        )
        assertTrue(deletion.requests.isEmpty())
        assertEquals(TEST_ACCOUNT_B_ID, runtime.authState.value.currentSession?.userId)
        assertEquals(strings.authSessionExpired, runtime.accessState.value.accountDeletionErrorMessage)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionUnexpectedFailureSafetyTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun preBoundaryRuntimeExceptionResumesFenceWithNeutralError() = runTest {
        val store = FakeAccountDeletionProviderCleanupStore()
        val google = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe()
        val deletion = AccountDeletionProbe()
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(onAccountDeletion = deletion::delete),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                accountDeletionProviderCleanupStore = store,
                googleIdentityProvider = google,
                idempotencyKeyProvider = IdempotencyKeyProvider {
                    throw IllegalStateException("raw pre-boundary detail")
                },
                purgeInteractionsForAccountDeletion = interactions::purge,
                resumeInteractionsAfterAccountDeletionFailure = interactions::resume,
            ),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertTrue(deletion.requests.isEmpty())
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
        assertEquals(0, store.markCallCount)
        assertEquals(0, google.clearCredentialStateCallCount)
        assertFalse(viewModel.accessState.value.accountDeletionInProgress)
        assertEquals(strings.authFederatedUnavailable, viewModel.accessState.value.accountDeletionErrorMessage)
    }

    @Test
    fun postBoundaryRuntimeExceptionRetainsFenceAndFailsClosedAsUnknown() = runTest {
        val store = FakeAccountDeletionProviderCleanupStore()
        val google = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe()
        val deletion = AccountDeletionProbe(
            beforeResult = { throw IllegalStateException("raw post-boundary detail") },
        )
        val viewModel = createAccountDeletionViewModel(
            probe = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = google,
            providerCleanupStore = store,
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, strings.authDeleteAccountConfirmationPhrase)

        assertEquals(1, deletion.requests.size)
        assertTrue(store.pending)
        assertTrue(interactions.resumedAccountIds.isEmpty())
        assertEquals(0, google.clearCredentialStateCallCount)
        assertFalse(viewModel.accessState.value.accountDeletionInProgress)
        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertEquals(
            strings.authAccountDeletionOutcomeUnknown,
            viewModel.accessState.value.accountDeletionErrorMessage,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionCancellationSafetyTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun preTransportCancellationReleasesOwnedAccountFence() = runTest {
        val operations = mutableListOf<String>()
        val interactions = AccountDeletionInteractionLifecycleProbe(operationEvents = operations)
        val deletion = AccountDeletionProbe(
            operationEvents = operations,
            beforeDelete = {
                throw AccountDeletionPreTransportCancellation(
                    CancellationException("cancelled during reauthentication"),
                )
            },
        )
        val fixture = createAccountDeletionCoordinatorFixture(deletion, interactions)

        fixture.startPasswordDeletion()
        advanceUntilIdle()

        assertEquals(
            listOf("purge:$TEST_ACCOUNT_ID", "resume:$TEST_ACCOUNT_ID"),
            operations,
        )
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
    }

    @Test
    fun preTransportCancellationWithCleanupDebtReleasesFenceAndFailsAuthClosed() = runTest {
        val operations = mutableListOf<String>()
        val interactions = AccountDeletionInteractionLifecycleProbe(operationEvents = operations)
        val deletion = AccountDeletionProbe(
            operationEvents = operations,
            beforeDelete = {
                throw AccountDeletionPreTransportCleanupPendingCancellation(
                    CancellationException("cancelled with durable cleanup debt"),
                )
            },
        )
        val fixture = createAccountDeletionCoordinatorFixture(deletion, interactions)

        fixture.startPasswordDeletion()
        advanceUntilIdle()

        assertEquals(listOf("purge:$TEST_ACCOUNT_ID", "resume:$TEST_ACCOUNT_ID"), operations)
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
        assertNull(fixture.runtime.authState.value.currentSession)
        assertEquals(AuthSessionRestoreStatus.Failed, fixture.runtime.sessionRestoreStatus.value)
        assertEquals(
            strings.settings.privacyPersistenceError,
            fixture.runtime.accessState.value.accountDeletionErrorMessage,
        )
    }

    @Test
    fun cancellationDuringRejectedMarkerClearStillDisarmsAndResumesExactlyOnce() = runTest {
        lateinit var fixture: AccountDeletionCoordinatorFixture
        val store = FakeAccountDeletionProviderCleanupStore(
            beforeClear = { fixture.runtime.accountDeletionJob?.cancel() },
        )
        val google = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe()
        fixture = createAccountDeletionCoordinatorFixture(
            deletion = AccountDeletionProbe(failFirstAttempt = true),
            interactionLifecycle = interactions,
            googleIdentityProvider = google,
            providerCleanupStore = store,
        )

        fixture.startPasswordDeletion()
        advanceUntilIdle()

        assertFalse(store.pending)
        assertEquals(1, store.clearCallCount)
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
        assertEquals(0, google.clearCredentialStateCallCount)
        assertFalse(fixture.runtime.accessState.value.accountDeletionInProgress)
    }

    @Test
    fun cancellationDuringRejectedCleanupPendingDisarmStillResumesAndFailsClosed() = runTest {
        lateinit var fixture: AccountDeletionCoordinatorFixture
        val store = FakeAccountDeletionProviderCleanupStore(
            beforeClear = { fixture.runtime.accountDeletionJob?.cancel() },
        )
        val google = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe()
        fixture = createAccountDeletionCoordinatorFixture(
            deletion = AccountDeletionProbe(
                outcome = AccountDeletionOutcome.RejectedCleanupPending(
                    DomainError.Validation("error.auth.account_deletion_reauthentication_failed"),
                ),
            ),
            interactionLifecycle = interactions,
            googleIdentityProvider = google,
            providerCleanupStore = store,
        )

        fixture.startPasswordDeletion()
        advanceUntilIdle()

        assertFalse(store.pending)
        assertEquals(1, store.clearCallCount)
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
        assertEquals(0, google.clearCredentialStateCallCount)
        assertEquals(AuthSessionRestoreStatus.Failed, fixture.runtime.sessionRestoreStatus.value)
        assertFalse(fixture.runtime.accessState.value.accountDeletionInProgress)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionPurgeWorkerSafetyTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun clearingSensitiveStateWhilePurgeIsBlockedTerminatesOwnerAndReleasesLateAcquisition() = runTest {
        val purgeStarted = CompletableDeferred<Unit>()
        val allowPurgeResult = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val providerCleanupStore = FakeAccountDeletionProviderCleanupStore()
        val googleIdentityProvider = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe(
            operationEvents = operations,
            afterPurge = {
                purgeStarted.complete(Unit)
                allowPurgeResult.await()
            },
        )
        val deletion = AccountDeletionProbe(operationEvents = operations)
        val fixture = createAccountDeletionCoordinatorFixture(
            deletion = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = googleIdentityProvider,
            providerCleanupStore = providerCleanupStore,
        )

        fixture.startPasswordDeletion()
        purgeStarted.await()
        val ownerJob = requireNotNull(fixture.runtime.accountDeletionJob)
        fixture.coordinator.clearSensitiveState()
        runCurrent()

        assertPurgeWaitStoppedBeforeRemote(
            fixture,
            deletion,
            interactions,
            providerCleanupStore,
            googleIdentityProvider,
        )
        assertEquals(listOf("purge:$TEST_ACCOUNT_ID"), operations)
        assertTrue(ownerJob.isCompleted)

        completeLatePurgeRelease(allowPurgeResult, operations, interactions)
    }

    @Test
    fun purgeWaitTimeoutTerminatesOwnerAndReleasesLateAcquisition() = runTest {
        val purgeStarted = CompletableDeferred<Unit>()
        val allowPurgeResult = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val providerCleanupStore = FakeAccountDeletionProviderCleanupStore()
        val googleIdentityProvider = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe(
            operationEvents = operations,
            afterPurge = {
                purgeStarted.complete(Unit)
                allowPurgeResult.await()
            },
        )
        val deletion = AccountDeletionProbe(operationEvents = operations)
        val fixture = createAccountDeletionCoordinatorFixture(
            deletion = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = googleIdentityProvider,
            providerCleanupStore = providerCleanupStore,
        )

        fixture.startPasswordDeletion()
        purgeStarted.await()
        advanceTimeBy(10_000L)
        runCurrent()

        assertPurgeWaitStoppedBeforeRemote(
            fixture,
            deletion,
            interactions,
            providerCleanupStore,
            googleIdentityProvider,
        )
        assertPurgeTimeoutError(fixture)

        completeLatePurgeRelease(allowPurgeResult, operations, interactions)
    }

    @Test
    fun purgeExceptionTerminatesOwnerWithoutRemoteOrResume() = runTest {
        val providerCleanupStore = FakeAccountDeletionProviderCleanupStore()
        val googleIdentityProvider = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe(
            afterPurge = { error("purge failed unexpectedly") },
        )
        val deletion = AccountDeletionProbe()
        val fixture = createAccountDeletionCoordinatorFixture(
            deletion = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = googleIdentityProvider,
            providerCleanupStore = providerCleanupStore,
        )

        fixture.startPasswordDeletion()
        advanceUntilIdle()

        assertFalse(fixture.runtime.accessState.value.accountDeletionInProgress)
        assertTrue(deletion.requests.isEmpty())
        assertTrue(interactions.resumedAccountIds.isEmpty())
        assertEquals(0, providerCleanupStore.markCallCount)
        assertEquals(0, googleIdentityProvider.clearCredentialStateCallCount)
    }

    @Test
    fun retriesWhilePurgeIsBlockedStaySingleFlightAndReleaseOnce() = runTest {
        val purgeStarted = CompletableDeferred<Unit>()
        val allowPurgeResult = CompletableDeferred<Unit>()
        val store = FakeAccountDeletionProviderCleanupStore()
        val google = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe(
            afterPurge = {
                purgeStarted.complete(Unit)
                allowPurgeResult.await()
            },
        )
        val deletion = AccountDeletionProbe()
        val fixture = createAccountDeletionCoordinatorFixture(
            deletion = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = google,
            providerCleanupStore = store,
        )

        fixture.startPasswordDeletion()
        purgeStarted.await()
        advanceTimeBy(10_000L)
        runCurrent()
        repeat(3) {
            fixture.startPasswordDeletion()
            runCurrent()
        }

        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.purgedAccountIds)
        assertTrue(interactions.resumedAccountIds.isEmpty())
        assertTrue(deletion.requests.isEmpty())
        assertEquals(0, store.markCallCount)

        allowPurgeResult.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
        assertEquals(0, google.clearCredentialStateCallCount)
    }

    @Test
    fun recreatedCoordinatorSharesSingleFlightAndLateRelease() = runTest {
        val purgeStarted = CompletableDeferred<Unit>()
        val allowPurgeResult = CompletableDeferred<Unit>()
        val registry = AccountDeletionPurgeRegistry()
        val firstInteractions = AccountDeletionInteractionLifecycleProbe(
            afterPurge = {
                purgeStarted.complete(Unit)
                allowPurgeResult.await()
            },
        )
        val firstDeletion = AccountDeletionProbe()
        val first = createAccountDeletionCoordinatorFixture(
            deletion = firstDeletion,
            interactionLifecycle = firstInteractions,
            purgeRegistry = registry,
        )

        first.startPasswordDeletion()
        purgeStarted.await()
        advanceTimeBy(10_000L)
        runCurrent()
        first.coordinator.clearSensitiveState()

        val secondInteractions = AccountDeletionInteractionLifecycleProbe()
        val secondDeletion = AccountDeletionProbe()
        val second = createAccountDeletionCoordinatorFixture(
            deletion = secondDeletion,
            interactionLifecycle = secondInteractions,
            purgeRegistry = registry,
        )
        second.startPasswordDeletion()
        advanceUntilIdle()

        assertSharedPurgeWorker(firstInteractions, secondInteractions, firstDeletion, secondDeletion)

        allowPurgeResult.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf(TEST_ACCOUNT_ID), firstInteractions.resumedAccountIds)
        assertTrue(secondInteractions.resumedAccountIds.isEmpty())
    }

    private fun assertLatePurgeRelease(
        operations: List<String>,
        interactions: AccountDeletionInteractionLifecycleProbe,
    ) {
        assertEquals(listOf("purge:$TEST_ACCOUNT_ID", "resume:$TEST_ACCOUNT_ID"), operations)
        assertEquals(listOf(TEST_ACCOUNT_ID), interactions.resumedAccountIds)
    }

    private fun TestScope.completeLatePurgeRelease(
        allowPurgeResult: CompletableDeferred<Unit>,
        operations: List<String>,
        interactions: AccountDeletionInteractionLifecycleProbe,
    ) {
        allowPurgeResult.complete(Unit)
        runCurrent()
        advanceUntilIdle()
        assertLatePurgeRelease(operations, interactions)
    }

    private fun assertPurgeTimeoutError(fixture: AccountDeletionCoordinatorFixture) {
        assertEquals(
            strings.settings.privacyPersistenceError,
            fixture.runtime.accessState.value.accountDeletionErrorMessage,
        )
    }

    private fun assertSharedPurgeWorker(
        firstInteractions: AccountDeletionInteractionLifecycleProbe,
        secondInteractions: AccountDeletionInteractionLifecycleProbe,
        firstDeletion: AccountDeletionProbe,
        secondDeletion: AccountDeletionProbe,
    ) {
        assertEquals(listOf(TEST_ACCOUNT_ID), firstInteractions.purgedAccountIds)
        assertTrue(secondInteractions.purgedAccountIds.isEmpty())
        assertTrue(firstDeletion.requests.isEmpty())
        assertTrue(secondDeletion.requests.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionPurgeRegistrySafetyTest {

    @Test
    fun registryRemainsOwnedUntilLateResumeCompletes() = runTest {
        val resumeStarted = CompletableDeferred<Unit>()
        val allowResume = CompletableDeferred<Unit>()
        val worker = AccountDeletionPurgeWorker(
            workerScope = backgroundScope,
            registry = AccountDeletionPurgeRegistry(),
            purge = {
                DomainResult.Success(InteractionAccountDeletionPurgeOutcome.Acquired(0))
            },
            resume = {
                resumeStarted.complete(Unit)
                allowResume.await()
            },
        )

        val firstHandoff = worker.start(TEST_ACCOUNT_ID)
        runCurrent()
        assertEquals(AccountDeletionPurgeWorkerResult.Acquired, firstHandoff.awaitResult())
        assertTrue(firstHandoff.abandon())
        worker.resumeAbandonedAcquisition(TEST_ACCOUNT_ID, firstHandoff)
        runCurrent()
        assertTrue(resumeStarted.isCompleted)

        val blockedHandoff = worker.start(TEST_ACCOUNT_ID)
        assertEquals(AccountDeletionPurgeWorkerResult.AlreadyBlocked, blockedHandoff.awaitResult())

        allowResume.complete(Unit)
        runCurrent()
        val nextHandoff = worker.start(TEST_ACCOUNT_ID)
        runCurrent()
        assertEquals(AccountDeletionPurgeWorkerResult.Acquired, nextHandoff.awaitResult())
        assertTrue(nextHandoff.claimAcquisition())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionRemoteCancellationSafetyTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun cancellingRemoteCallKeepsOwnedAccountBlocked() = runTest {
        val remoteStarted = CompletableDeferred<Unit>()
        val keepRemotePending = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val providerCleanupStore = FakeAccountDeletionProviderCleanupStore()
        val googleIdentityProvider = FakeGoogleIdentityProvider()
        val interactions = AccountDeletionInteractionLifecycleProbe(
            operationEvents = operations,
        )
        val deletion = AccountDeletionProbe(
            operationEvents = operations,
            beforeResult = {
                remoteStarted.complete(Unit)
                keepRemotePending.await()
            },
        )
        val fixture = createAccountDeletionCoordinatorFixture(
            deletion = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = googleIdentityProvider,
            providerCleanupStore = providerCleanupStore,
        )

        fixture.startPasswordDeletion()
        remoteStarted.await()
        requireNotNull(fixture.runtime.accountDeletionJob).cancel()
        advanceUntilIdle()
        fixture.coordinator.clearSensitiveState()
        advanceUntilIdle()

        assertEquals(
            listOf("purge:$TEST_ACCOUNT_ID", "remote-delete"),
            operations,
        )
        assertTrue(interactions.resumedAccountIds.isEmpty())
        assertTrue(providerCleanupStore.pending)
        assertEquals(0, googleIdentityProvider.clearCredentialStateCallCount)
        assertEquals(AuthSessionRestoreStatus.Failed, fixture.runtime.sessionRestoreStatus.value)
    }

    @Test
    fun clearingSensitiveStateDuringRemoteCallKeepsOwnedAccountBlocked() = runTest {
        val remoteStarted = CompletableDeferred<Unit>()
        val keepRemotePending = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val interactions = AccountDeletionInteractionLifecycleProbe(
            operationEvents = operations,
        )
        val deletion = AccountDeletionProbe(
            operationEvents = operations,
            beforeResult = {
                remoteStarted.complete(Unit)
                keepRemotePending.await()
            },
        )
        val fixture = createAccountDeletionCoordinatorFixture(deletion, interactions)

        fixture.startPasswordDeletion()
        remoteStarted.await()
        fixture.coordinator.clearSensitiveState()
        advanceUntilIdle()
        fixture.coordinator.clearSensitiveState()
        advanceUntilIdle()

        assertEquals(
            listOf("purge:$TEST_ACCOUNT_ID", "remote-delete"),
            operations,
        )
        assertTrue(interactions.resumedAccountIds.isEmpty())
    }

    @Test
    fun clearingAfterRemoteSuccessNeverResumesOwnedAccount() = runTest {
        val credentialClearStarted = CompletableDeferred<Unit>()
        val keepCredentialClearPending = CompletableDeferred<Unit>()
        val interactions = AccountDeletionInteractionLifecycleProbe()
        val providerCleanupStore = FakeAccountDeletionProviderCleanupStore()
        val googleIdentityProvider = FakeGoogleIdentityProvider(
            beforeClear = {
                credentialClearStarted.complete(Unit)
                keepCredentialClearPending.await()
            },
        )
        val deletion = AccountDeletionProbe()
        val fixture = createAccountDeletionCoordinatorFixture(
            deletion = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = googleIdentityProvider,
            providerCleanupStore = providerCleanupStore,
        )

        fixture.startPasswordDeletion()
        credentialClearStarted.await()
        fixture.coordinator.clearSensitiveState()
        advanceUntilIdle()

        assertEquals(TEST_ACCOUNT_ID, deletion.requests.single().expectedAccountId)
        assertTrue(interactions.resumedAccountIds.isEmpty())
        assertFalse(fixture.runtime.accessState.value.accountDeletionInProgress)
        assertEquals(AuthEffect.AccountDeleted, fixture.runtime.effectChannel.tryReceive().getOrNull())
        assertTrue(providerCleanupStore.pending)
        assertEquals(1, googleIdentityProvider.clearCredentialStateCallCount)
        assertEquals(AuthSessionRestoreStatus.Failed, fixture.runtime.sessionRestoreStatus.value)
        keepCredentialClearPending.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun providerClearTimeoutAfterDeletedPublishesTerminalThenFailsRestoreClosed() = runTest {
        val neverClear = CompletableDeferred<Unit>()
        val interactions = AccountDeletionInteractionLifecycleProbe()
        val providerCleanupStore = FakeAccountDeletionProviderCleanupStore()
        val googleIdentityProvider = FakeGoogleIdentityProvider(
            beforeClear = { neverClear.await() },
        )
        val deletion = AccountDeletionProbe()
        val fixture = createAccountDeletionCoordinatorFixture(
            deletion = deletion,
            interactionLifecycle = interactions,
            googleIdentityProvider = googleIdentityProvider,
            providerCleanupStore = providerCleanupStore,
        )

        fixture.startPasswordDeletion()
        advanceUntilIdle()

        assertEquals(AuthEffect.AccountDeleted, fixture.runtime.effectChannel.tryReceive().getOrNull())
        assertTrue(fixture.runtime.effectChannel.tryReceive().isFailure)
        assertEquals(TEST_ACCOUNT_ID, deletion.requests.single().expectedAccountId)
        assertTrue(interactions.resumedAccountIds.isEmpty())
        assertTrue(providerCleanupStore.pending)
        assertEquals(0, providerCleanupStore.clearCallCount)
        assertEquals(1, googleIdentityProvider.clearCredentialStateCallCount)
        assertEquals(AuthSessionRestoreStatus.Failed, fixture.runtime.sessionRestoreStatus.value)
        assertEquals(AuthSurface.SessionRestoreFailure, fixture.runtime.platformState.value.surface)
    }
}

private fun AccountDeletionCoordinatorFixture.startPasswordDeletion() {
    coordinator.handle(
        AuthIntent.DeleteAccountWithPassword(
            password = TEST_PASSWORD,
            confirmation = stringsFor(AppLocale.French).authDeleteAccountConfirmationPhrase,
        ),
    )
}

private fun assertPurgeWaitStoppedBeforeRemote(
    fixture: AccountDeletionCoordinatorFixture,
    deletion: AccountDeletionProbe,
    interactions: AccountDeletionInteractionLifecycleProbe,
    store: FakeAccountDeletionProviderCleanupStore,
    google: FakeGoogleIdentityProvider,
) {
    fixture.runtime.accountDeletionJob?.let { ownerJob -> assertTrue(ownerJob.isCompleted) }
    assertFalse(fixture.runtime.accessState.value.accountDeletionInProgress)
    assertTrue(deletion.requests.isEmpty())
    assertTrue(interactions.resumedAccountIds.isEmpty())
    assertEquals(0, store.markCallCount)
    assertEquals(0, google.clearCredentialStateCallCount)
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
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelAccountDeletionProviderRestoreTest {
    private val deletionConfirmation = stringsFor(AppLocale.French).authDeleteAccountConfirmationPhrase

    @Test
    fun immediatePromoterCallbackWaitsForMarkerReadOnInjectedIoDispatcher() = runTest(timeout = 10.seconds) {
        val ioDispatcher = RecordingCoroutineDispatcher(StandardTestDispatcher(testScheduler))
        val store = FakeAccountDeletionProviderCleanupStore(
            verifyAccessContext = { check(ioDispatcher.isExecuting) },
        )
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                accountDeletionProviderCleanupStore = store,
                accountDeletionIoDispatcher = ioDispatcher,
            ),
        )

        assertEquals(0, store.readCallCount)
        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        assertEquals(0, repository.promoterCallbackCallCount)

        advanceUntilIdle()

        assertTrue(ioDispatcher.dispatchCount > 0)
        assertTrue(store.readCallCount > 0)
        assertEquals(0, store.clearCallCount)
        assertEquals(1, repository.promoterCallbackCallCount)
        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
    }

    @Test
    fun processRestartWithLiveSessionClearsMarkerWithoutProviderState() = runTest {
        val events = mutableListOf<String>()
        val store = FakeAccountDeletionProviderCleanupStore(
            pending = true,
            operationEvents = events,
        )
        val google = FakeGoogleIdentityProvider(operationEvents = events)
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(operationEvents = events),
        )

        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                accountDeletionProviderCleanupStore = store,
                googleIdentityProvider = google,
            ),
        )
        advanceUntilIdle()

        assertEquals(-1, events.indexOf("google-clear"))
        assertTrue(events.indexOf("get-current-session") < events.indexOf("provider-cleanup-marker-clear"))
        assertFalse(store.pending)
        assertEquals(0, google.clearCredentialStateCallCount)
        assertEquals(1, repository.getCurrentSessionCallCount)
        assertEquals(AuthSessionRestoreStatus.Ready, viewModel.sessionRestoreStatus.value)
        assertTrue(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun processRestartWithoutSessionClearsProviderStateAfterRestoreProbe() = runTest {
        val events = mutableListOf<String>()
        val store = FakeAccountDeletionProviderCleanupStore(
            pending = true,
            operationEvents = events,
        )
        val google = FakeGoogleIdentityProvider(operationEvents = events)
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(operationEvents = events),
        )

        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                accountDeletionProviderCleanupStore = store,
                googleIdentityProvider = google,
            ),
        )
        advanceUntilIdle()

        assertTrue(events.indexOf("get-current-session") < events.indexOf("google-clear"))
        assertFalse(store.pending)
        assertEquals(1, google.clearCredentialStateCallCount)
        assertEquals(AuthSessionRestoreStatus.Ready, viewModel.sessionRestoreStatus.value)
        assertFalse(viewModel.state.value.hasSession)
    }

    @Test
    fun rejectedDeletionClearDebtRestoresLiveSessionWithoutClearingProvider() = runTest {
        val store = FakeAccountDeletionProviderCleanupStore(clearSucceeds = false)
        val google = FakeGoogleIdentityProvider()
        val repository = RegistrationAuthRepository(
            currentSession = completeSession(),
            hooks = RegistrationAuthHooks(
                onAccountDeletion = {
                    DomainResult.Failure(DomainError.Validation("error.auth.account_deletion_rejected"))
                },
            ),
        )
        val overrides = AuthTestOverrides(
            accountDeletionProviderCleanupStore = store,
            googleIdentityProvider = google,
        )
        val viewModel = createViewModel(repository = repository, scope = this, overrides = overrides)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestAccountDeletion)
        submitPasswordAccountDeletion(viewModel, stringsFor(AppLocale.French).authDeleteAccountConfirmationPhrase)

        assertTrue(store.pending)
        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertEquals(0, google.clearCredentialStateCallCount)
        store.clearSucceeds = true

        val restartedViewModel = createViewModel(repository = repository, scope = this, overrides = overrides)
        advanceUntilIdle()

        assertFalse(store.pending)
        assertEquals(0, google.clearCredentialStateCallCount)
        assertTrue(restartedViewModel.state.value.isAuthenticated)
        assertEquals(AuthSessionRestoreStatus.Ready, restartedViewModel.sessionRestoreStatus.value)
    }

    @Test
    fun unreadableDeletionProviderMarkerBlocksSessionRestore() = runTest {
        val store = object : AccountDeletionProviderCleanupStore {
            override fun hasPendingCleanup(): Boolean = error("unreadable marker")

            override fun markPending(): Boolean = error("unwritable marker")

            override fun clear(): Boolean = error("unclearable marker")
        }
        val repository = RegistrationAuthRepository(currentSession = completeSession())
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                accountDeletionProviderCleanupStore = store,
                googleIdentityProvider = FakeGoogleIdentityProvider(),
            ),
        )

        advanceUntilIdle()

        assertEquals(1, repository.getCurrentSessionCallCount)
        assertSessionRestoreIsBlocked(viewModel)
    }

    @Test
    fun failedDeletionProviderCleanupQueuesPromoterCallbackUntilForegroundRetryIsReady() = runTest {
        val store = FakeAccountDeletionProviderCleanupStore(pending = true)
        val google = FakeGoogleIdentityProvider(clearSucceeds = false)
        val repository = RegistrationAuthRepository(
            hooks = RegistrationAuthHooks(
                onPromoterCallback = {
                    DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
                },
            ),
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                accountDeletionProviderCleanupStore = store,
                googleIdentityProvider = google,
            ),
        )
        advanceUntilIdle()

        assertSessionRestoreIsBlocked(viewModel)
        assertEquals(1, repository.getCurrentSessionCallCount)
        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        advanceUntilIdle()
        assertEquals(0, repository.promoterCallbackCallCount)

        google.clearSucceeds = true
        viewModel.onForeground()
        advanceUntilIdle()

        assertFalse(store.pending)
        assertEquals(2, google.clearCredentialStateCallCount)
        assertEquals(2, repository.getCurrentSessionCallCount)
        assertEquals(1, repository.promoterCallbackCallCount)
        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
    }

    @Test
    fun sessionRestoreRetrySettlesDeletionProviderCleanupDebt() = runTest {
        val store = FakeAccountDeletionProviderCleanupStore(pending = true)
        val google = FakeGoogleIdentityProvider(clearSucceeds = false)
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                accountDeletionProviderCleanupStore = store,
                googleIdentityProvider = google,
            ),
        )
        advanceUntilIdle()

        assertSessionRestoreIsBlocked(viewModel)
        google.clearSucceeds = true

        viewModel.onIntent(AuthIntent.RetrySessionRestore)
        advanceUntilIdle()

        assertFalse(store.pending)
        assertEquals(2, google.clearCredentialStateCallCount)
        assertEquals(2, repository.getCurrentSessionCallCount)
        assertEquals(AuthSessionRestoreStatus.Ready, viewModel.sessionRestoreStatus.value)
    }

    @Test
    fun rejectedDeletionDrainsRetainedCallbackOnlyAfterTerminalReadyState() = runTest {
        val remoteStarted = CompletableDeferred<Unit>()
        val allowRejection = CompletableDeferred<Unit>()
        val store = FakeAccountDeletionProviderCleanupStore()
        var callbackCallCount = 0
        val viewModel = createAccountDeletionCallbackViewModel(
            probe = AccountDeletionProbe(
                failFirstAttempt = true,
                beforeResult = {
                    remoteStarted.complete(Unit)
                    allowRejection.await()
                },
            ),
            onPromoterCallback = {
                callbackCallCount += 1
                DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
            },
            providerCleanupStore = store,
        )
        advanceUntilIdle()

        startPasswordAccountDeletion(viewModel, deletionConfirmation)
        remoteStarted.await()
        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        runCurrent()

        assertTrue(store.pending)
        assertTrue(viewModel.accessState.value.accountDeletionInProgress)
        assertTrue(viewModel.accountDeletionBlocksViewerSession.value)
        assertEquals(0, callbackCallCount)

        allowRejection.complete(Unit)
        advanceUntilIdle()

        assertFalse(store.pending)
        assertFalse(viewModel.accessState.value.accountDeletionInProgress)
        assertFalse(viewModel.accountDeletionBlocksViewerSession.value)
        assertEquals(AuthSessionRestoreStatus.Ready, viewModel.sessionRestoreStatus.value)
        assertEquals(1, callbackCallCount)
        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
    }

    @Test
    fun rejectedCleanupPendingRetainsCallbackUntilForegroundRestoreIsReady() = runTest {
        val remoteStarted = CompletableDeferred<Unit>()
        val allowRejection = CompletableDeferred<Unit>()
        val store = FakeAccountDeletionProviderCleanupStore()
        val callbackUrls = mutableListOf<String>()
        val rejection = DomainError.Validation("error.auth.account_deletion_reauthentication_failed")
        val viewModel = createAccountDeletionCallbackViewModel(
            probe = AccountDeletionProbe(
                outcome = AccountDeletionOutcome.RejectedCleanupPending(rejection),
                beforeResult = {
                    remoteStarted.complete(Unit)
                    allowRejection.await()
                },
            ),
            onPromoterCallback = { callbackUrl ->
                callbackUrls += callbackUrl
                DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
            },
            providerCleanupStore = store,
        )
        advanceUntilIdle()

        startPasswordAccountDeletion(viewModel, deletionConfirmation)
        remoteStarted.await()
        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        allowRejection.complete(Unit)
        advanceUntilIdle()

        assertFalse(store.pending)
        assertFalse(viewModel.accountDeletionBlocksViewerSession.value)
        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertTrue(callbackUrls.isEmpty())

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_SECOND_PROMOTER_PKCE_CALLBACK))
        runCurrent()
        assertTrue(callbackUrls.isEmpty())

        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(AuthSessionRestoreStatus.Ready, viewModel.sessionRestoreStatus.value)
        assertEquals(listOf(TEST_PROMOTER_CALLBACK), callbackUrls)
        assertEquals(PromoterActivationStage.Ready, viewModel.promoterActivationState.value.stage)
    }

    @Test
    fun deletedOutcomeDiscardsRetainedCallbackBeforeNavigationPublishesGuestState() = runTest {
        val remoteStarted = CompletableDeferred<Unit>()
        val allowDeletion = CompletableDeferred<Unit>()
        val store = FakeAccountDeletionProviderCleanupStore()
        var callbackCallCount = 0
        val viewModel = createAccountDeletionCallbackViewModel(
            probe = AccountDeletionProbe(
                beforeResult = {
                    remoteStarted.complete(Unit)
                    allowDeletion.await()
                },
            ),
            onPromoterCallback = {
                callbackCallCount += 1
                DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
            },
            providerCleanupStore = store,
        )
        advanceUntilIdle()

        startPasswordAccountDeletion(viewModel, deletionConfirmation)
        remoteStarted.await()
        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        allowDeletion.complete(Unit)
        advanceUntilIdle()

        assertFalse(store.pending)
        assertEquals(AuthSessionRestoreStatus.Ready, viewModel.sessionRestoreStatus.value)
        assertTrue(viewModel.accountDeletionBlocksViewerSession.value)
        assertEquals(0, callbackCallCount)
        assertTrue(viewModel.state.value.isAuthenticated)

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        viewModel.onIntent(AuthIntent.AccountDeletionNavigationHandled)
        viewModel.onForeground()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.hasSession)
        assertFalse(viewModel.accountDeletionBlocksViewerSession.value)
        assertEquals(0, callbackCallCount)
        assertEquals(PromoterActivationStage.Loading, viewModel.promoterActivationState.value.stage)
    }

    @Test
    fun unknownOutcomeDiscardsRetainedCallbackAcrossForegroundAndRetry() = runTest {
        val remoteStarted = CompletableDeferred<Unit>()
        val allowUnknown = CompletableDeferred<Unit>()
        val store = FakeAccountDeletionProviderCleanupStore()
        var callbackCallCount = 0
        val viewModel = createAccountDeletionCallbackViewModel(
            probe = AccountDeletionProbe(
                outcome = AccountDeletionOutcome.OutcomeUnknown,
                beforeResult = {
                    remoteStarted.complete(Unit)
                    allowUnknown.await()
                },
            ),
            onPromoterCallback = {
                callbackCallCount += 1
                DomainResult.Success(promoterActivationContext(sessionImportedForActivation = false))
            },
            providerCleanupStore = store,
        )
        advanceUntilIdle()

        startPasswordAccountDeletion(viewModel, deletionConfirmation)
        remoteStarted.await()
        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        allowUnknown.complete(Unit)
        advanceUntilIdle()

        assertFalse(store.pending)
        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertTrue(viewModel.accountDeletionBlocksViewerSession.value)
        assertEquals(0, callbackCallCount)

        viewModel.onIntent(AuthIntent.OpenPromoterActivation(TEST_PROMOTER_PKCE_CALLBACK))
        viewModel.onForeground()
        viewModel.onIntent(AuthIntent.RetrySessionRestore)
        advanceUntilIdle()

        assertEquals(AuthSessionRestoreStatus.Failed, viewModel.sessionRestoreStatus.value)
        assertFalse(viewModel.state.value.hasSession)
        assertTrue(viewModel.accountDeletionBlocksViewerSession.value)
        assertEquals(0, callbackCallCount)
        assertEquals(PromoterActivationStage.Loading, viewModel.promoterActivationState.value.stage)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelPromoterSessionCleanupTest {

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
        runCurrent()
        advanceUntilIdle()

        assertEquals(0, repository.promoterCallbackCallCount)
        assertEquals(PromoterActivationStage.Error, viewModel.promoterActivationState.value.stage)
        assertTrue(viewModel.promoterActivationState.value.retryAvailable)
    }

    @Test
    fun coldStartBlocksRestoreUntilRevokedSessionMarkerCanBeCleared() = runTest {
        val events = mutableListOf<String>()
        val store = FakePromoterActivationSessionStore(pending = true, clearSucceeds = false, operationEvents = events)
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
        runCurrent()
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
    private val outcome: AccountDeletionOutcome = AccountDeletionOutcome.Deleted,
    private val operationEvents: MutableList<String>? = null,
    private val beforeDelete: suspend () -> Unit = {},
    private val beforeResult: suspend () -> Unit = {},
) {
    val requests = mutableListOf<AccountDeletionRequest>()
    var generatedKeyCount = 0
        private set

    val idempotencyKeyProvider = IdempotencyKeyProvider {
        generatedKeyCount += 1
        TEST_IDEMPOTENCY_KEY
    }

    suspend fun delete(request: AccountDeletionRequest): DomainResult<AccountDeletionOutcome> {
        beforeDelete()
        operationEvents?.add("remote-delete")
        requests += request
        beforeResult()
        return if (failFirstAttempt && requests.size == 1) {
            DomainResult.Failure(DomainError.Validation("error.auth.account_deletion_rejected"))
        } else {
            DomainResult.Success(outcome)
        }
    }
}

private class AccountDeletionInteractionLifecycleProbe(
    private val purgeResult: DomainResult<InteractionAccountDeletionPurgeOutcome> =
        DomainResult.Success(InteractionAccountDeletionPurgeOutcome.Acquired(0)),
    private val subsequentPurgeResult: DomainResult<InteractionAccountDeletionPurgeOutcome>? = null,
    private val operationEvents: MutableList<String>? = null,
    private val afterPurge: suspend () -> Unit = {},
) {
    val purgedAccountIds = mutableListOf<String>()
    val resumedAccountIds = mutableListOf<String>()

    suspend fun purge(accountId: String): DomainResult<InteractionAccountDeletionPurgeOutcome> {
        operationEvents?.add("purge:$accountId")
        purgedAccountIds += accountId
        afterPurge()
        return if (purgedAccountIds.size > 1) subsequentPurgeResult ?: purgeResult else purgeResult
    }

    suspend fun resume(accountId: String) {
        operationEvents?.add("resume:$accountId")
        resumedAccountIds += accountId
    }
}

private fun TestScope.createAccountDeletionViewModel(
    probe: AccountDeletionProbe,
    revokeConsent: () -> Boolean = { true },
    interactionLifecycle: AccountDeletionInteractionLifecycleProbe =
        AccountDeletionInteractionLifecycleProbe(),
    googleIdentityProvider: GoogleIdentityProvider = FakeGoogleIdentityProvider(),
    providerCleanupStore: AccountDeletionProviderCleanupStore =
        FakeAccountDeletionProviderCleanupStore(),
): AuthViewModel = createViewModel(
    repository = RegistrationAuthRepository(
        currentSession = completeSession(),
        hooks = RegistrationAuthHooks(onAccountDeletion = probe::delete),
    ),
    scope = this,
    overrides = AuthTestOverrides(
        idempotencyKeyProvider = probe.idempotencyKeyProvider,
        revokeConsent = revokeConsent,
        googleIdentityProvider = googleIdentityProvider,
        accountDeletionProviderCleanupStore = providerCleanupStore,
        purgeInteractionsForAccountDeletion = interactionLifecycle::purge,
        resumeInteractionsAfterAccountDeletionFailure = interactionLifecycle::resume,
    ),
)

private fun TestScope.createAccountDeletionCallbackViewModel(
    probe: AccountDeletionProbe,
    onPromoterCallback: suspend (String) -> DomainResult<PromoterActivationContext>,
    providerCleanupStore: AccountDeletionProviderCleanupStore,
): AuthViewModel = createViewModel(
    repository = RegistrationAuthRepository(
        currentSession = completeSession(),
        hooks = RegistrationAuthHooks(
            onPromoterCallback = onPromoterCallback,
            onAccountDeletion = probe::delete,
        ),
    ),
    scope = this,
    overrides = AuthTestOverrides(
        idempotencyKeyProvider = probe.idempotencyKeyProvider,
        accountDeletionProviderCleanupStore = providerCleanupStore,
    ),
)

private data class AccountDeletionCoordinatorFixture(
    val coordinator: AccountDeletionCoordinator,
    val runtime: AuthViewModelRuntime,
)

private data class AccountDeletionPresentationFixture(
    val runtime: AuthViewModelRuntime,
    val registrationPresenter: RegistrationPresenter,
    val passwordRecoveryPresenter: PasswordRecoveryPresenter,
)

private fun TestScope.createAccountDeletionCoordinatorFixture(
    deletion: AccountDeletionProbe,
    interactionLifecycle: AccountDeletionInteractionLifecycleProbe,
    googleIdentityProvider: GoogleIdentityProvider = FakeGoogleIdentityProvider(),
    providerCleanupStore: AccountDeletionProviderCleanupStore =
        FakeAccountDeletionProviderCleanupStore(),
    purgeRegistry: AccountDeletionPurgeRegistry = AccountDeletionPurgeRegistry(),
): AccountDeletionCoordinatorFixture {
    val clock = accountDeletionTestClock()
    val repository = RegistrationAuthRepository(
        currentSession = completeSession(),
        hooks = RegistrationAuthHooks(onAccountDeletion = deletion::delete),
    )
    val presentation = createAccountDeletionPresentationFixture(repository, clock)
    val dependencies = AuthViewModelDependencies(
        authPresenter = AuthPresenter(repository),
        registrationPresenter = presentation.registrationPresenter,
        passwordRecoveryPresenter = presentation.passwordRecoveryPresenter,
        authJourneyStore = FakeAuthJourneyStore(),
        promoterActivationSessionStore = FakePromoterActivationSessionStore(),
        accountDeletionProviderCleanupStore = providerCleanupStore,
        googleIdentityProvider = googleIdentityProvider,
        googleIdentityUnavailableMessage = TEST_GOOGLE_UNAVAILABLE_MESSAGE,
        idempotencyKeyProvider = deletion.idempotencyKeyProvider,
        clockProvider = clock,
        accountDeletionIoDispatcher = StandardTestDispatcher(testScheduler),
        accountDeletionWorkerScope = backgroundScope,
        accountDeletionPurgeRegistry = purgeRegistry,
        track = {},
        revokeObservabilityConsent = { true },
        purgeInteractionsForAccountDeletion = interactionLifecycle::purge,
        resumeInteractionsAfterAccountDeletionFailure = interactionLifecycle::resume,
    )
    return AccountDeletionCoordinatorFixture(
        coordinator = AccountDeletionCoordinator(
            runtime = presentation.runtime,
            dependencies = dependencies,
            providerCleanup = AccountDeletionProviderCleanupCoordinator(
                store = dependencies.accountDeletionProviderCleanupStore,
                googleIdentityProvider = googleIdentityProvider,
                ioDispatcher = dependencies.accountDeletionIoDispatcher,
            ),
        ),
        runtime = presentation.runtime,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.accountDeletionTestClock(): ClockProvider = object : ClockProvider {
    override fun nowEpochMilliseconds(): Long = TEST_EPOCH_MILLISECONDS + testScheduler.currentTime
}

private fun TestScope.createAccountDeletionPresentationFixture(
    repository: AuthRepository,
    clock: ClockProvider,
): AccountDeletionPresentationFixture {
    val registrationPresenter = RegistrationPresenter(
        repository,
        RegistrationCatalogRepository(),
        clock,
        RegistrationReducer(),
    )
    val passwordRecoveryPresenter = PasswordRecoveryPresenter(repository, clock)
    val runtime = AuthViewModelRuntime(
        registrationPresenter = registrationPresenter,
        passwordRecoveryPresenter = passwordRecoveryPresenter,
        strings = stringsFor(AppLocale.French),
        coroutineScope = this,
    ).also { createdRuntime ->
        createdRuntime.authState.value = createdRuntime.authState.value.copy(currentSession = completeSession())
        createdRuntime.accessState.value = AuthAccessUiState(accountDeletionDialogVisible = true)
    }
    return AccountDeletionPresentationFixture(runtime, registrationPresenter, passwordRecoveryPresenter)
}

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

    suspend fun delete(request: AccountDeletionRequest): DomainResult<AccountDeletionOutcome> {
        check(request.idempotencyKey.isNotBlank())
        started.complete(Unit)
        try {
            allowCompletion.await()
        } catch (exception: CancellationException) {
            cancelled = true
            throw exception
        }
        return DomainResult.Success(AccountDeletionOutcome.Deleted)
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
            accountDeletionProviderCleanupStore = overrides.accountDeletionProviderCleanupStore,
            googleIdentityProvider = overrides.googleIdentityProvider,
            googleIdentityUnavailableMessage = TEST_GOOGLE_UNAVAILABLE_MESSAGE,
            idempotencyKeyProvider = overrides.idempotencyKeyProvider,
            clockProvider = clock,
            accountDeletionIoDispatcher = overrides.accountDeletionIoDispatcher
                ?: StandardTestDispatcher(scope.testScheduler),
            accountDeletionWorkerScope = scope.backgroundScope,
            accountDeletionPurgeRegistry = overrides.accountDeletionPurgeRegistry,
            track = overrides.track,
            revokeObservabilityConsent = overrides.revokeConsent,
            purgeInteractionsForAccountDeletion = overrides.purgeInteractionsForAccountDeletion,
            resumeInteractionsAfterAccountDeletionFailure =
            overrides.resumeInteractionsAfterAccountDeletionFailure,
        ),
        strings = stringsFor(AppLocale.French),
        coroutineScope = lifecycleTestScope(),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.lifecycleTestScope(): CoroutineScope = CoroutineScope(
    coroutineContext.minusKey(Job) +
        SupervisorJob(requireNotNull(backgroundScope.coroutineContext[Job])),
)

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
    val accountDeletionProviderCleanupStore: AccountDeletionProviderCleanupStore =
        FakeAccountDeletionProviderCleanupStore(),
    val googleIdentityProvider: GoogleIdentityProvider = FakeGoogleIdentityProvider(),
    val idempotencyKeyProvider: IdempotencyKeyProvider = IdempotencyKeyProvider { TEST_IDEMPOTENCY_KEY },
    val accountDeletionIoDispatcher: CoroutineDispatcher? = null,
    val accountDeletionPurgeRegistry: AccountDeletionPurgeRegistry = AccountDeletionPurgeRegistry(),
    val revokeConsent: () -> Boolean = { true },
    val track: (com.kwabor.shared.domain.observability.AnalyticsEvent) -> Unit = {},
    val purgeInteractionsForAccountDeletion:
    suspend (String) -> DomainResult<InteractionAccountDeletionPurgeOutcome> = {
        DomainResult.Success(InteractionAccountDeletionPurgeOutcome.Acquired(0))
    },
    val resumeInteractionsAfterAccountDeletionFailure: suspend (String) -> Unit = {},
)

private class RecordingCoroutineDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private var executionDepth: Int = 0

    var dispatchCount: Int = 0
        private set

    val isExecuting: Boolean
        get() = executionDepth > 0

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount += 1
        delegate.dispatch(context) {
            executionDepth += 1
            try {
                block.run()
            } finally {
                executionDepth -= 1
            }
        }
    }
}

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

private class FakeAccountDeletionProviderCleanupStore(
    var pending: Boolean = false,
    var markSucceeds: Boolean = true,
    var clearSucceeds: Boolean = true,
    private val operationEvents: MutableList<String>? = null,
    private val beforeClear: () -> Unit = {},
    private val verifyAccessContext: () -> Unit = {},
) : AccountDeletionProviderCleanupStore {
    var readCallCount: Int = 0
        private set
    var markCallCount: Int = 0
        private set
    var clearCallCount: Int = 0
        private set

    override fun hasPendingCleanup(): Boolean {
        verifyAccessContext()
        readCallCount += 1
        operationEvents?.add("provider-cleanup-marker-read")
        return pending
    }

    override fun markPending(): Boolean {
        verifyAccessContext()
        markCallCount += 1
        operationEvents?.add("provider-cleanup-marker-mark")
        if (markSucceeds) pending = true
        return markSucceeds
    }

    override fun clear(): Boolean {
        verifyAccessContext()
        clearCallCount += 1
        operationEvents?.add("provider-cleanup-marker-clear")
        beforeClear()
        if (clearSucceeds) pending = false
        return clearSucceeds
    }
}

private class FakeGoogleIdentityProvider(
    private val result: GoogleIdentityResult = GoogleIdentityResult.Unavailable,
    private val operationEvents: MutableList<String>? = null,
    private val beforeClear: suspend () -> Unit = {},
    var clearSucceeds: Boolean = true,
) : GoogleIdentityProvider {
    override val isConfigured: Boolean = result !is GoogleIdentityResult.Unavailable

    var clearCredentialStateCallCount: Int = 0
        private set
    var acquireIdTokenCallCount: Int = 0
        private set

    override suspend fun acquireIdToken(): GoogleIdentityResult {
        operationEvents?.add("google-acquire")
        acquireIdTokenCallCount += 1
        return result
    }

    override suspend fun clearCredentialState(): Boolean {
        clearCredentialStateCallCount += 1
        operationEvents?.add("google-clear")
        beforeClear()
        return clearSucceeds
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
    val onAccountDeletion: suspend (AccountDeletionRequest) -> DomainResult<AccountDeletionOutcome> = {
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

    override suspend fun deleteAccount(request: AccountDeletionRequest): DomainResult<AccountDeletionOutcome> {
        val result = hooks.onAccountDeletion(request)
        if (result is DomainResult.Success && result.value == AccountDeletionOutcome.Deleted) {
            session = null
        }
        return result
    }

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
    userId = TEST_ACCOUNT_ID,
    email = TEST_EMAIL,
    expiresAtEpochMilliseconds = TEST_EPOCH_MILLISECONDS + 3_600_000L,
    accountSetupStatus = AccountSetupStatus.OnboardingRequired,
)

private fun completeSession(): AuthSession = onboardingSession().copy(accountSetupStatus = AccountSetupStatus.Complete)

private fun passwordRecoverySession(): AuthSession = completeSession().copy(
    purpose = AuthSessionPurpose.PasswordRecovery,
)

private const val TEST_EMAIL = "user@kwabor.test"
private const val TEST_ACCOUNT_ID = "user-1"
private const val TEST_ACCOUNT_B_ID = "user-2"
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
private val TEST_SECOND_PROMOTER_CALLBACK =
    "kwabor://auth/promoter-activate?token=${"c".repeat(64)}"
private val TEST_SECOND_PROMOTER_PKCE_CALLBACK =
    "$TEST_SECOND_PROMOTER_CALLBACK&code=${"d".repeat(32)}"
private const val TEST_ORGANIZATION_ID = "organization-1"
private const val TEST_LISTING_ID = "listing-1"
private const val TEST_EPOCH_MILLISECONDS = 1_783_800_000_000L
