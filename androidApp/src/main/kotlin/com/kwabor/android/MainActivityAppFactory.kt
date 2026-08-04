package com.kwabor.android

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kwabor.android.app.KwaborAppDependencies
import com.kwabor.android.auth.AndroidApproximateLocationService
import com.kwabor.android.auth.AndroidGoogleIdentityProvider
import com.kwabor.android.auth.AndroidLegalDocumentLauncher
import com.kwabor.android.auth.SharedPreferencesAuthJourneyStore
import com.kwabor.android.auth.SharedPreferencesPromoterActivationSessionStore
import com.kwabor.android.auth.UuidIdempotencyKeyProvider
import com.kwabor.android.detail.AndroidDetailExternalActionLauncher
import com.kwabor.android.media.PublicHttpsListingMediaUrlPolicy
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.auth.AuthViewModelDependencies
import com.kwabor.android.presentation.detail.CatalogDetailViewModel
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.guide.GuideDiscoveryViewModel
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.android.presentation.search.SearchViewModel
import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.app.KwaborCompositionRoot
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.PasswordRecoveryPresenter
import com.kwabor.shared.presentation.auth.RegistrationPresenter
import com.kwabor.shared.presentation.detail.catalogDetailMinuteTicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

internal class MainActivityAppFactory(
    private val activity: ComponentActivity,
    private val applicationState: KwaborApplication,
    private val configuredApp: ConfiguredApp,
    private val strings: KwaborStrings,
) {
    private val compositionRoot: KwaborCompositionRoot = configuredApp.compositionRoot

    fun createAuthViewModel(): AuthViewModel = ViewModelProvider(
        owner = activity,
        factory = viewModelFactory {
            initializer {
                AuthViewModel(
                    dependencies = AuthViewModelDependencies(
                        authPresenter = configuredApp.authPresenters.auth,
                        passwordRecoveryPresenter = configuredApp.authPresenters.passwordRecovery,
                        registrationPresenter = configuredApp.authPresenters.registration,
                        authJourneyStore = SharedPreferencesAuthJourneyStore(activity.applicationContext),
                        promoterActivationSessionStore =
                        SharedPreferencesPromoterActivationSessionStore(activity.applicationContext),
                        googleIdentityProvider = AndroidGoogleIdentityProvider(
                            context = activity.applicationContext,
                            serverClientId = BuildConfig.KWABOR_GOOGLE_WEB_CLIENT_ID,
                        ),
                        googleIdentityUnavailableMessage = activity.getString(R.string.auth_google_unavailable),
                        idempotencyKeyProvider = UuidIdempotencyKeyProvider,
                        clockProvider = compositionRoot.clockProvider,
                        track = applicationState.observability::track,
                        revokeObservabilityConsent = applicationState.observability::revokeAllConsent,
                    ),
                    strings = strings,
                    coroutineScope = newViewModelScope(compositionRoot.dispatcherProvider),
                )
            }
        },
    )[AuthViewModel::class.java]

    fun createDependencies(authViewModel: AuthViewModel): KwaborAppDependencies = KwaborAppDependencies(
        exploreViewModel = createExploreViewModel(),
        searchViewModel = createSearchViewModel(),
        catalogDetailViewModel = createCatalogDetailViewModel(),
        guideDiscoveryViewModel = createGuideDiscoveryViewModel(),
        authViewModel = authViewModel,
        onboardingViewModel = createOnboardingViewModel(),
        legalDocumentLauncher = AndroidLegalDocumentLauncher(activity.applicationContext),
        observabilityController = applicationState.observability,
        listingMediaUrlPolicy = PublicHttpsListingMediaUrlPolicy,
        detailExternalActionLauncher = AndroidDetailExternalActionLauncher(activity.applicationContext),
    )

    private fun createExploreViewModel(): ExploreViewModel = ViewModelProvider(
        owner = activity,
        factory = viewModelFactory {
            initializer {
                ExploreViewModel(
                    presenter = compositionRoot.explorePresenter,
                    locationService = AndroidApproximateLocationService(activity.applicationContext),
                    strings = strings,
                    coroutineScope = newViewModelScope(compositionRoot.dispatcherProvider),
                    track = applicationState.observability::track,
                )
            }
        },
    )[ExploreViewModel::class.java]

    private fun createSearchViewModel(): SearchViewModel = ViewModelProvider(
        owner = activity,
        factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    presenter = compositionRoot.searchPresenter,
                    strings = strings.search,
                    coroutineScope = newViewModelScope(compositionRoot.dispatcherProvider),
                    track = applicationState.observability::track,
                )
            }
        },
    )[SearchViewModel::class.java]

    private fun createCatalogDetailViewModel(): CatalogDetailViewModel = ViewModelProvider(
        owner = activity,
        factory = viewModelFactory {
            initializer {
                CatalogDetailViewModel(
                    presenter = compositionRoot.catalogDetailPresenter,
                    strings = strings,
                    coroutineScope = newViewModelScope(compositionRoot.dispatcherProvider),
                    temporalTicks = catalogDetailMinuteTicks(),
                )
            }
        },
    )[CatalogDetailViewModel::class.java]

    private fun createGuideDiscoveryViewModel(): GuideDiscoveryViewModel = ViewModelProvider(
        owner = activity,
        factory = viewModelFactory {
            initializer {
                GuideDiscoveryViewModel(
                    presenter = compositionRoot.guideDiscoveryPresenter,
                    strings = strings.guideDiscovery,
                    coroutineScope = newViewModelScope(compositionRoot.dispatcherProvider),
                )
            }
        },
    )[GuideDiscoveryViewModel::class.java]

    private fun createOnboardingViewModel(): OnboardingViewModel = ViewModelProvider(
        owner = activity,
        factory = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    firstLaunchStore = applicationState.firstLaunchStore,
                    track = applicationState.observability::track,
                    coroutineScope = newViewModelScope(compositionRoot.dispatcherProvider),
                )
            }
        },
    )[OnboardingViewModel::class.java]
}

internal data class ConfiguredApp(
    val compositionRoot: KwaborCompositionRoot,
    val authPresenters: AuthPresenters,
)

internal data class AuthPresenters(
    val auth: AuthPresenter,
    val passwordRecovery: PasswordRecoveryPresenter,
    val registration: RegistrationPresenter,
)

private fun newViewModelScope(dispatcherProvider: DispatcherProvider): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcherProvider.main)
