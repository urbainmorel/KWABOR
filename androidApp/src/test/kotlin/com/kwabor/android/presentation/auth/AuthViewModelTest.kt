package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.AuthJourneyStore
import com.kwabor.android.auth.InterruptedAuthJourney
import com.kwabor.android.auth.NotificationPermissionPolicy
import com.kwabor.android.auth.NotificationPrimingStore
import com.kwabor.android.auth.RegistrationLocationResult
import com.kwabor.android.auth.RegistrationLocationService
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.AuthSessionPurpose
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingDetail
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.PasswordRecoveryPresenter
import com.kwabor.shared.presentation.auth.PasswordRecoveryStep
import com.kwabor.shared.presentation.auth.RegistrationPresenter
import com.kwabor.shared.presentation.auth.RegistrationReducer
import com.kwabor.shared.presentation.auth.RegistrationStep
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
class AuthViewModelTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun incompleteRestoredSessionResumesRegistrationAndNeverAuthenticates() = runTest {
        val repository = RegistrationAuthRepository(currentSession = onboardingSession())
        val viewModel = createViewModel(repository = repository, scope = this)

        advanceUntilIdle()

        assertEquals(AuthSurface.Registration, viewModel.platformState.value.surface)
        assertEquals(RegistrationStep.Password, viewModel.registrationState.value.step)
        assertTrue(viewModel.state.value.hasSession)
        assertFalse(viewModel.state.value.isAuthenticated)
    }

    @Test
    fun completedRestoredSessionResumesUnresolvedNotificationPrimingInsteadOfHome() = runTest {
        val viewModel = createViewModel(
            repository = RegistrationAuthRepository(currentSession = completeSession()),
            scope = this,
            overrides = AuthTestOverrides(
                notificationPrimingStore = FakeNotificationPrimingStore(resolved = false),
            ),
        )

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isAuthenticated)
        assertEquals(AuthSurface.Registration, viewModel.platformState.value.surface)
        assertEquals(RegistrationStep.NotificationPriming, viewModel.registrationState.value.step)
    }

    @Test
    fun completedRestoredSessionSkipsNotificationPrimingOnceInstallationResolvedIt() = runTest {
        val viewModel = createViewModel(
            repository = RegistrationAuthRepository(currentSession = completeSession()),
            scope = this,
            overrides = AuthTestOverrides(
                notificationPrimingStore = FakeNotificationPrimingStore(resolved = true),
            ),
        )

        advanceUntilIdle()

        assertTrue(viewModel.state.value.isAuthenticated)
        assertEquals(AuthSurface.Hidden, viewModel.platformState.value.surface)
        assertEquals(RegistrationStep.Email, viewModel.registrationState.value.step)
    }

    @Test
    fun locationPermissionIsRequestedOnlyAfterIntentAndDenialKeepsManualFlowAvailable() = runTest {
        var locationReads = 0
        val viewModel = createViewModel(
            repository = RegistrationAuthRepository(),
            scope = this,
            overrides = AuthTestOverrides(
                locationService = RegistrationLocationService {
                    locationReads += 1
                    RegistrationLocationResult.Available(latitude = 6.37, longitude = 2.39)
                },
            ),
        )
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestLocation)

        assertEquals(AuthPlatformEffect.RequestLocationPermission, viewModel.platformEffects.first())
        viewModel.onIntent(AuthIntent.LocationPermissionResult(granted = false))

        assertEquals(RegistrationLocationStatus.PermissionDenied, viewModel.platformState.value.locationStatus)
        assertEquals(0, locationReads)
        assertEquals(null, viewModel.registrationState.value.selectedCityId)
    }

    @Test
    fun completeRegistrationAppliesOptionalConsentThenPrimesNotificationsWithoutBlockingDenial() = runTest {
        val appliedConsents = mutableListOf<ObservabilityConsent>()
        val notificationPrimingStore = FakeNotificationPrimingStore(resolved = false)
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                notificationPermissionPolicy = NotificationPermissionPolicy { true },
                notificationPrimingStore = notificationPrimingStore,
                applyConsent = appliedConsents::add,
            ),
        )
        advanceUntilIdle()

        completeRegistrationUntilObservability(viewModel)
        viewModel.onIntent(AuthIntent.ChangeAnalyticsConsent(accepted = true))
        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()

        assertEquals(RegistrationStep.NotificationPriming, viewModel.registrationState.value.step)
        assertEquals(listOf(ObservabilityConsent(analyticsAllowed = true)), appliedConsents)
        assertTrue(viewModel.state.value.isAuthenticated)

        viewModel.onIntent(AuthIntent.EnableNotifications)
        assertTrue(viewModel.platformState.value.notificationPermissionRequestInFlight)
        assertEquals(AuthPlatformEffect.RequestNotificationPermission, viewModel.platformEffects.first())

        viewModel.onIntent(AuthIntent.NotificationPermissionResult(granted = false))
        advanceUntilIdle()

        assertFalse(viewModel.platformState.value.notificationPermissionRequestInFlight)
        assertTrue(notificationPrimingStore.resolved)
        assertEquals(1, notificationPrimingStore.markResolvedCalls)
        assertEquals(AuthEffect.AuthenticationCompleted, viewModel.effects.first())
        assertEquals(AuthSurface.Hidden, viewModel.platformState.value.surface)
        assertEquals(RegistrationStep.Completed, viewModel.registrationState.value.step)
    }

    @Test
    fun observabilityConsentIsPersistedBeforeOnboardingRpcStarts() = runTest {
        var consentPersisted = false
        val repository = RegistrationAuthRepository(
            onCompleteOnboarding = { assertTrue(consentPersisted) },
        )
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(
                applyConsent = {
                    consentPersisted = true
                    true
                },
            ),
        )
        advanceUntilIdle()
        completeRegistrationUntilObservability(viewModel)

        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()

        assertTrue(consentPersisted)
        assertEquals(RegistrationStep.NotificationPriming, viewModel.registrationState.value.step)
    }

    @Test
    fun onboardingRpcIsNotStartedWhenObservabilityConsentCannotBePersisted() = runTest {
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(applyConsent = { false }),
        )
        advanceUntilIdle()
        completeRegistrationUntilObservability(viewModel)

        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()

        assertEquals(0, repository.completeOnboardingCallCount)
        assertEquals(RegistrationStep.Observability, viewModel.registrationState.value.step)
        assertTrue(viewModel.platformState.value.observabilityConsentPersistenceFailed)
    }

    @Test
    fun onboardingSubmissionIgnoresDoubleTapBeforeCoroutineStarts() = runTest {
        val appliedConsents = mutableListOf<ObservabilityConsent>()
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(applyConsent = appliedConsents::add),
        )
        advanceUntilIdle()
        completeRegistrationUntilObservability(viewModel)

        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()

        assertEquals(1, repository.completeOnboardingCallCount)
        assertEquals(1, appliedConsents.size)
    }

    @Test
    fun notificationPermissionRequestIgnoresDoubleTapUntilPlatformCallback() = runTest {
        val viewModel = createViewModel(
            repository = RegistrationAuthRepository(),
            scope = this,
            overrides = AuthTestOverrides(
                notificationPermissionPolicy = NotificationPermissionPolicy { true },
            ),
        )
        advanceUntilIdle()
        completeRegistrationUntilObservability(viewModel)
        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()
        val platformEffects = viewModel.platformEffects.produceIn(backgroundScope)

        viewModel.onIntent(AuthIntent.EnableNotifications)
        viewModel.onIntent(AuthIntent.EnableNotifications)
        runCurrent()

        assertTrue(viewModel.platformState.value.notificationPermissionRequestInFlight)
        assertEquals(AuthPlatformEffect.RequestNotificationPermission, platformEffects.receive())
        assertTrue(platformEffects.tryReceive().isFailure)

        viewModel.onIntent(AuthIntent.NotificationPermissionResult(granted = true))
        advanceUntilIdle()

        assertFalse(viewModel.platformState.value.notificationPermissionRequestInFlight)
        assertEquals(RegistrationStep.Completed, viewModel.registrationState.value.step)
    }

    @Test
    fun processRestartAfterSuccessfulRpcResumesUnresolvedNotificationPriming() = runTest {
        val repository = RegistrationAuthRepository()
        val store = FakeNotificationPrimingStore(resolved = false)
        val initialViewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(notificationPrimingStore = store),
        )
        advanceUntilIdle()
        completeRegistrationUntilObservability(initialViewModel)
        initialViewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()
        assertEquals(RegistrationStep.NotificationPriming, initialViewModel.registrationState.value.step)

        val restoredViewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(notificationPrimingStore = store),
        )
        advanceUntilIdle()

        assertEquals(AuthSurface.Registration, restoredViewModel.platformState.value.surface)
        assertEquals(RegistrationStep.NotificationPriming, restoredViewModel.registrationState.value.step)
    }

    @Test
    fun notificationChoiceIsPersistedBeforeJourneyCompletesAndIsNeverProposedAgain() = runTest {
        val store = FakeNotificationPrimingStore(resolved = false)
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(notificationPrimingStore = store),
        )
        advanceUntilIdle()
        completeRegistrationUntilObservability(viewModel)
        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.SkipNotifications)
        advanceUntilIdle()

        assertTrue(store.resolved)
        assertEquals(1, store.markResolvedCalls)
        assertEquals(AuthSurface.Hidden, viewModel.platformState.value.surface)
        assertEquals(RegistrationStep.Completed, viewModel.registrationState.value.step)

        val restoredViewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(notificationPrimingStore = store),
        )
        advanceUntilIdle()
        assertEquals(AuthSurface.Hidden, restoredViewModel.platformState.value.surface)
    }

    @Test
    fun failedNotificationChoicePersistenceKeepsPrimerVisibleAndAllowsRetry() = runTest {
        val store = FakeNotificationPrimingStore(resolved = false, writesSucceed = false)
        val viewModel = createViewModel(
            repository = RegistrationAuthRepository(),
            scope = this,
            overrides = AuthTestOverrides(notificationPrimingStore = store),
        )
        advanceUntilIdle()
        completeRegistrationUntilObservability(viewModel)
        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.SkipNotifications)

        assertEquals(RegistrationStep.NotificationPriming, viewModel.registrationState.value.step)
        assertEquals(AuthSurface.Registration, viewModel.platformState.value.surface)
        assertTrue(viewModel.platformState.value.notificationPrimingPersistenceFailed)

        store.writesSucceed = true
        viewModel.onIntent(AuthIntent.SkipNotifications)
        advanceUntilIdle()

        assertTrue(store.resolved)
        assertEquals(RegistrationStep.Completed, viewModel.registrationState.value.step)
    }

    @Test
    fun latestObservabilityConsentIsAppliedBeforeEveryOnboardingSubmissionIncludingFailure() = runTest {
        val appliedConsents = mutableListOf<ObservabilityConsent>()
        val repository = RegistrationAuthRepository(onboardingCompletionFailuresRemaining = 1)
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(applyConsent = appliedConsents::add),
        )
        advanceUntilIdle()
        completeRegistrationUntilObservability(viewModel)
        viewModel.onIntent(AuthIntent.ChangeAnalyticsConsent(accepted = true))

        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()

        assertEquals(listOf(ObservabilityConsent(analyticsAllowed = true)), appliedConsents)
        assertEquals(RegistrationStep.Observability, viewModel.registrationState.value.step)

        viewModel.onIntent(AuthIntent.ChangeAnalyticsConsent(accepted = false))
        viewModel.onIntent(AuthIntent.ChangeDiagnosticsConsent(accepted = true))
        viewModel.onIntent(AuthIntent.CompleteOnboarding)
        advanceUntilIdle()

        assertEquals(
            listOf(
                ObservabilityConsent(analyticsAllowed = true),
                ObservabilityConsent(diagnosticsAllowed = true),
            ),
            appliedConsents,
        )
        assertEquals(RegistrationStep.NotificationPriming, viewModel.registrationState.value.step)
    }

    @Test
    fun credentialIntentsNeverExposeOtpOrPasswordInLogs() {
        val otpIntent = AuthIntent.SubmitOtp(TEST_OTP)
        val passwordIntent = AuthIntent.SubmitPassword(TEST_PASSWORD, TEST_PASSWORD)

        assertFalse(otpIntent.toString().contains(TEST_OTP))
        assertFalse(passwordIntent.toString().contains(TEST_PASSWORD))
    }

    @Test
    fun continuingAsGuestSignsOutPartialOtpSessionBeforeEmittingGuestEffect() = runTest {
        val repository = RegistrationAuthRepository()
        val viewModel = createViewModel(repository = repository, scope = this)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.OpenRegistration(AuthEntryPoint.SoftWall))
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        assertFalse(viewModel.state.value.isAuthenticated)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.ContinueAsGuest)
        advanceUntilIdle()

        assertTrue(repository.signOutCalled)
        assertFalse(viewModel.state.value.hasSession)
        assertEquals(AuthEffect.GuestContinuationSelected, viewModel.effects.first())
    }

    @Test
    fun retryRequirementsDoesNotResubmitPassword() = runTest {
        val repository = RegistrationAuthRepository(failFirstLegalDocumentsLoad = true)
        val viewModel = createViewModel(repository = repository, scope = this)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        assertFalse(viewModel.state.value.isAuthenticated)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitPassword(TEST_PASSWORD, TEST_PASSWORD))
        advanceUntilIdle()

        assertTrue(viewModel.registrationState.value.cities.isNotEmpty())
        assertEquals(null, viewModel.registrationState.value.termsDocument)
        assertEquals(1, repository.passwordUpdateCount)

        viewModel.onIntent(AuthIntent.RetryRequirements)
        advanceUntilIdle()

        assertTrue(viewModel.registrationState.value.termsDocument != null)
        assertEquals(1, repository.passwordUpdateCount)
    }

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
                locationService = overrides.locationService,
                notificationPermissionPolicy = overrides.notificationPermissionPolicy,
                notificationPrimingStore = overrides.notificationPrimingStore,
                authJourneyStore = overrides.authJourneyStore,
                clockProvider = clock,
                applyObservabilityConsent = overrides.applyConsent,
            ),
            strings = strings,
            coroutineScope = this,
        )
    }

    private suspend fun TestScope.completeRegistrationUntilObservability(viewModel: AuthViewModel) {
        viewModel.onIntent(AuthIntent.OpenRegistration())
        viewModel.onIntent(AuthIntent.ChangeEmail(TEST_EMAIL))
        viewModel.onIntent(AuthIntent.RequestOtp)
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitOtp(TEST_OTP))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.SubmitPassword(TEST_PASSWORD, TEST_PASSWORD))
        advanceUntilIdle()
        viewModel.onIntent(AuthIntent.ChangeFirstName("Afi"))
        viewModel.onIntent(AuthIntent.ChangeLastName("Soglo"))
        viewModel.onIntent(AuthIntent.ContinueFromIdentity)
        viewModel.onIntent(AuthIntent.SelectCity(TEST_CITY_ID))
        viewModel.onIntent(AuthIntent.ContinueFromCity)
        viewModel.onIntent(AuthIntent.SelectCurrency(KwaborCurrency.Eur))
        viewModel.onIntent(AuthIntent.ContinueFromCurrency)
        LegalDocumentType.entries.forEach { type ->
            viewModel.onIntent(AuthIntent.ChangeLegalAcceptance(type, accepted = true))
        }
        viewModel.onIntent(AuthIntent.ContinueFromLegal)
        assertEquals(RegistrationStep.Observability, viewModel.registrationState.value.step)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelPostAuthenticationTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun completedAccountOtpFromRegistrationSignsOutAndRequiresPassword() = runTest {
        val store = FakeNotificationPrimingStore(resolved = false)
        val repository = RegistrationAuthRepository(verifiedSession = completeSession())
        val viewModel = createViewModel(
            repository = repository,
            scope = this,
            overrides = AuthTestOverrides(notificationPrimingStore = store),
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
        val notificationStore = FakeNotificationPrimingStore(resolved = true)
        val repository = RegistrationAuthRepository(
            verifiedSession = completeSession(),
            authBehavior = RegistrationAuthBehavior(
                signOutFailure = DomainError.NetworkUnavailable(),
            ),
        )
        val overrides = AuthTestOverrides(
            notificationPrimingStore = notificationStore,
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
                notificationPrimingStore = FakeNotificationPrimingStore(resolved = true),
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

    @Test
    fun locationPermissionRequestIsSingleFlightAndResetsAfterEveryResult() = runTest {
        val viewModel = createViewModel(repository = RegistrationAuthRepository(), scope = this)
        val platformEffects = viewModel.platformEffects.produceIn(backgroundScope)
        advanceUntilIdle()

        viewModel.onIntent(AuthIntent.RequestLocation)
        viewModel.onIntent(AuthIntent.RequestLocation)
        runCurrent()

        assertTrue(viewModel.platformState.value.locationPermissionRequestInFlight)
        assertEquals(AuthPlatformEffect.RequestLocationPermission, platformEffects.receive())
        assertTrue(platformEffects.tryReceive().isFailure)

        viewModel.onIntent(AuthIntent.LocationPermissionResult(granted = false))

        assertFalse(viewModel.platformState.value.locationPermissionRequestInFlight)
        assertEquals(RegistrationLocationStatus.PermissionDenied, viewModel.platformState.value.locationStatus)

        viewModel.onIntent(AuthIntent.RequestLocation)
        runCurrent()
        assertTrue(viewModel.platformState.value.locationPermissionRequestInFlight)
        assertEquals(AuthPlatformEffect.RequestLocationPermission, platformEffects.receive())

        viewModel.onIntent(AuthIntent.LocationPermissionResult(granted = true))
        advanceUntilIdle()

        assertFalse(viewModel.platformState.value.locationPermissionRequestInFlight)
        assertEquals(RegistrationLocationStatus.Unavailable, viewModel.platformState.value.locationStatus)
    }

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
                locationService = overrides.locationService,
                notificationPermissionPolicy = overrides.notificationPermissionPolicy,
                notificationPrimingStore = overrides.notificationPrimingStore,
                authJourneyStore = overrides.authJourneyStore,
                clockProvider = clock,
                applyObservabilityConsent = overrides.applyConsent,
            ),
            strings = strings,
            coroutineScope = this,
        )
    }
}

private data class AuthTestOverrides(
    val locationService: RegistrationLocationService = RegistrationLocationService {
        RegistrationLocationResult.Unavailable
    },
    val notificationPermissionPolicy: NotificationPermissionPolicy = NotificationPermissionPolicy { false },
    val notificationPrimingStore: NotificationPrimingStore = FakeNotificationPrimingStore(resolved = false),
    val authJourneyStore: AuthJourneyStore = FakeAuthJourneyStore(),
    val applyConsent: (ObservabilityConsent) -> Boolean = { true },
)

private data class RegistrationAuthBehavior(
    val signInSession: AuthSession = completeSession(),
    val recoverySession: AuthSession = passwordRecoverySession(),
    val signOutFailure: DomainError? = null,
)

private class RegistrationAuthRepository(
    currentSession: AuthSession? = null,
    private val verifiedSession: AuthSession = onboardingSession(),
    private val failFirstLegalDocumentsLoad: Boolean = false,
    private var onboardingCompletionFailuresRemaining: Int = 0,
    private val onCompleteOnboarding: () -> Unit = {},
    private val authBehavior: RegistrationAuthBehavior = RegistrationAuthBehavior(),
) : AuthRepository {
    private var session: AuthSession? = currentSession
    private var legalDocumentsLoadCount = 0

    var signOutCalled = false
        private set
    var passwordUpdateCount = 0
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

    override suspend fun getCurrentSession(): DomainResult<AuthSession?> = DomainResult.Success(session)

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession> {
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
        onCompleteOnboarding()
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

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> =
        DomainResult.Success(completeSession())

    override suspend fun activatePromoterInvite(request: PromoterActivationRequest): DomainResult<AuthSession> =
        DomainResult.Failure(DomainError.Validation("error.auth.unused"))

    override suspend fun signOut(): DomainResult<Unit> {
        signOutCallCount += 1
        signOutCalled = true
        authBehavior.signOutFailure?.let { return DomainResult.Failure(it) }
        session = null
        return DomainResult.Success(Unit)
    }
}

private class FakeAuthJourneyStore(
    private var journey: InterruptedAuthJourney = InterruptedAuthJourney.None,
    private val clearsSucceed: Boolean = true,
) : AuthJourneyStore {
    override fun read(): InterruptedAuthJourney = journey

    override fun write(journey: InterruptedAuthJourney): Boolean {
        this.journey = journey
        return true
    }

    override fun clear(): Boolean {
        if (!clearsSucceed) return false
        journey = InterruptedAuthJourney.None
        return true
    }
}

private class FakeNotificationPrimingStore(
    var resolved: Boolean,
    var writesSucceed: Boolean = true,
) : NotificationPrimingStore {
    var markResolvedCalls: Int = 0
        private set

    override fun isResolved(): Boolean = resolved

    override fun markResolved(): Boolean {
        markResolvedCalls += 1
        if (writesSucceed) resolved = true
        return writesSucceed
    }
}

private class RegistrationCatalogRepository : CatalogRepository {
    override suspend fun listCities(): DomainResult<List<City>> = DomainResult.Success(
        listOf(City(id = TEST_CITY_ID, name = "Cotonou", latitude = 6.37, longitude = 2.39)),
    )

    override suspend fun listCategories(): DomainResult<List<Category>> = DomainResult.Success(emptyList())

    override suspend fun listListings(
        filters: ListingFilters,
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> = unexpected()

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> = unexpected()

    override suspend fun getListingDetail(listingId: String): DomainResult<ListingDetail> = unexpected()

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

private fun legalDocument(type: LegalDocumentType): LegalDocumentRevision = LegalDocumentRevision(
    id = "document-${type.name}",
    type = type,
    version = "2026-07",
    locale = AppLocale.French,
    url = "https://kwabor.test/legal/${type.name.lowercase()}",
    effectiveAtEpochMilliseconds = TEST_EPOCH_MILLISECONDS,
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
private const val TEST_EPOCH_MILLISECONDS = 1_783_800_000_000L
