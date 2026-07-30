package com.kwabor.android

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kwabor.android.app.AndroidDeepLinkSlotViewModel
import com.kwabor.android.app.AndroidSensitiveAuthDeepLinkPolicy
import com.kwabor.android.app.KwaborApp
import com.kwabor.android.app.KwaborAppDependencies
import com.kwabor.android.app.KwaborAppRuntimeState
import com.kwabor.android.app.KwaborUnavailableApp
import com.kwabor.android.auth.AndroidApproximateLocationService
import com.kwabor.android.auth.AndroidDeepLinkClassifier
import com.kwabor.android.auth.AndroidDeepLinkDestination
import com.kwabor.android.auth.AndroidGoogleIdentityProvider
import com.kwabor.android.auth.AndroidLegalDocumentLauncher
import com.kwabor.android.auth.SharedPreferencesAuthJourneyStore
import com.kwabor.android.auth.SharedPreferencesPromoterActivationSessionStore
import com.kwabor.android.auth.UuidIdempotencyKeyProvider
import com.kwabor.android.detail.AndroidDetailExternalActionLauncher
import com.kwabor.android.media.PublicHttpsListingMediaUrlPolicy
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.auth.AuthViewModelDependencies
import com.kwabor.android.presentation.detail.CatalogDetailViewModel
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.guide.GuideDiscoveryViewModel
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.app.KwaborCompositionRoot
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.PasswordRecoveryPresenter
import com.kwabor.shared.presentation.auth.RegistrationPresenter
import com.kwabor.shared.presentation.detail.catalogDetailMinuteTicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val deepLinkSlotViewModel by viewModels<AndroidDeepLinkSlotViewModel>()
    private val launchSplashExited = MutableStateFlow(false)
    private var pendingAuthCallback: String? = null
    private var authViewModel: AuthViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        val isFirstActivityInProcess =
            (application as KwaborApplication).launchProcessState.consumeIsFirstActivityInProcess()
        val splashGuard = LaunchSplashGuard(
            nowMillis = SystemClock::uptimeMillis,
            minimumVisibleDurationMillis = launchSplashMinimumVisibleDurationMillis(
                isFirstActivityInProcess = isFirstActivityInProcess,
            ),
        )
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition(splashGuard::shouldKeepOnScreen)
        splashScreen.setOnExitAnimationListener { provider ->
            provider.remove()
            launchSplashExited.value = true
        }
        acceptDeepLink(intent)
        val configuredApp = configuredAppOrNull()
        if (configuredApp == null) {
            pendingAuthCallback = null
            setContent { KwaborUnavailableApp() }
            return
        }

        showConfiguredApp(configuredApp)
    }

    override fun onStart() {
        super.onStart()
        (application as KwaborApplication).observability.retryPendingMaintenance()
    }

    private fun configuredAppOrNull(): ConfiguredApp? {
        val compositionRoot = (application as KwaborApplication).compositionRoot ?: return null
        val authPresenters = AuthPresenters(
            auth = compositionRoot.authPresenter ?: return null,
            passwordRecovery = compositionRoot.passwordRecoveryPresenter ?: return null,
            registration = compositionRoot.registrationPresenter ?: return null,
        )
        return ConfiguredApp(compositionRoot = compositionRoot, authPresenters = authPresenters)
    }

    private fun showConfiguredApp(configuredApp: ConfiguredApp) {
        val strings = stringsFor(AppLocale.French)
        val applicationState = application as KwaborApplication
        val configuredAuthViewModel = createAuthViewModel(
            configuredApp = configuredApp,
            strings = strings,
            applicationState = applicationState,
        )
        authViewModel = configuredAuthViewModel
        configuredAuthViewModel.attachGoogleIdentityActivity(this)
        dispatchPendingAuthCallback(configuredAuthViewModel)
        val dependencies = KwaborAppDependencies(
            exploreViewModel = createExploreViewModel(configuredApp.compositionRoot, strings),
            catalogDetailViewModel = createCatalogDetailViewModel(configuredApp.compositionRoot, strings),
            guideDiscoveryViewModel = createGuideDiscoveryViewModel(configuredApp.compositionRoot, strings),
            authViewModel = configuredAuthViewModel,
            onboardingViewModel = createOnboardingViewModel(
                applicationState,
                configuredApp.compositionRoot.dispatcherProvider,
            ),
            legalDocumentLauncher = AndroidLegalDocumentLauncher(applicationContext),
            observabilityController = applicationState.observability,
            listingMediaUrlPolicy = PublicHttpsListingMediaUrlPolicy,
            detailExternalActionLauncher = AndroidDetailExternalActionLauncher(applicationContext),
        )

        setContent {
            KwaborApp(
                dependencies = dependencies,
                runtimeState = KwaborAppRuntimeState(
                    pendingDeepLink = deepLinkSlotViewModel.delivery,
                    launchSplashExited = launchSplashExited,
                    onDeepLinkAcknowledged = { deliveryId ->
                        deepLinkSlotViewModel.acknowledge(deliveryId)
                    },
                    onDeepLinksReset = deepLinkSlotViewModel::resetForSensitiveAuthTransition,
                ),
            )
        }
    }

    private fun createExploreViewModel(
        compositionRoot: KwaborCompositionRoot,
        strings: KwaborStrings,
    ): ExploreViewModel = ViewModelProvider(
        owner = this,
        factory = viewModelFactory {
            initializer {
                ExploreViewModel(
                    presenter = compositionRoot.explorePresenter,
                    locationService = AndroidApproximateLocationService(applicationContext),
                    strings = strings,
                    coroutineScope = newViewModelScope(compositionRoot.dispatcherProvider),
                    track = (application as KwaborApplication).observability::track,
                )
            }
        },
    )[ExploreViewModel::class.java]

    private fun createCatalogDetailViewModel(
        compositionRoot: KwaborCompositionRoot,
        strings: KwaborStrings,
    ): CatalogDetailViewModel = ViewModelProvider(
        owner = this,
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

    private fun createGuideDiscoveryViewModel(
        compositionRoot: KwaborCompositionRoot,
        strings: KwaborStrings,
    ): GuideDiscoveryViewModel = ViewModelProvider(
        owner = this,
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

    private fun createAuthViewModel(
        configuredApp: ConfiguredApp,
        strings: KwaborStrings,
        applicationState: KwaborApplication,
    ): AuthViewModel = ViewModelProvider(
        owner = this,
        factory = viewModelFactory {
            initializer {
                AuthViewModel(
                    dependencies = AuthViewModelDependencies(
                        authPresenter = configuredApp.authPresenters.auth,
                        passwordRecoveryPresenter = configuredApp.authPresenters.passwordRecovery,
                        registrationPresenter = configuredApp.authPresenters.registration,
                        authJourneyStore = SharedPreferencesAuthJourneyStore(applicationContext),
                        promoterActivationSessionStore =
                        SharedPreferencesPromoterActivationSessionStore(applicationContext),
                        googleIdentityProvider = AndroidGoogleIdentityProvider(
                            context = applicationContext,
                            serverClientId = BuildConfig.KWABOR_GOOGLE_WEB_CLIENT_ID,
                        ),
                        googleIdentityUnavailableMessage = getString(R.string.auth_google_unavailable),
                        idempotencyKeyProvider = UuidIdempotencyKeyProvider,
                        clockProvider = configuredApp.compositionRoot.clockProvider,
                        track = applicationState.observability::track,
                        revokeObservabilityConsent = applicationState.observability::revokeAllConsent,
                    ),
                    strings = strings,
                    coroutineScope = newViewModelScope(configuredApp.compositionRoot.dispatcherProvider),
                )
            }
        },
    )[AuthViewModel::class.java]

    private fun createOnboardingViewModel(
        applicationState: KwaborApplication,
        dispatcherProvider: DispatcherProvider,
    ): OnboardingViewModel = ViewModelProvider(
        owner = this,
        factory = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    firstLaunchStore = applicationState.firstLaunchStore,
                    track = applicationState.observability::track,
                    coroutineScope = newViewModelScope(dispatcherProvider),
                )
            }
        },
    )[OnboardingViewModel::class.java]

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDeepLink(intent)
    }

    override fun onDestroy() {
        authViewModel?.detachGoogleIdentityActivity(this)
        authViewModel = null
        super.onDestroy()
    }

    private fun acceptDeepLink(sourceIntent: Intent) {
        val data = sourceIntent.data ?: return
        sourceIntent.data = null
        val rawUrl = data.toString()
        val destination = AndroidDeepLinkClassifier.classify(rawUrl)
        when (destination) {
            AndroidDeepLinkDestination.PromoterActivation -> acceptPromoterAuthCallback(rawUrl)
            AndroidDeepLinkDestination.RootNavigation,
            AndroidDeepLinkDestination.CatalogDetail,
            -> {
                val authAccess = authViewModel?.accessState?.value
                if (
                    AndroidSensitiveAuthDeepLinkPolicy.shouldRetainNavigation(
                        destination = destination,
                        signOutInProgress = authAccess?.signOutInProgress == true,
                        accountDeletionInProgress = authAccess?.accountDeletionInProgress == true,
                    )
                ) {
                    deepLinkSlotViewModel.offer(rawUrl)
                }
            }
            AndroidDeepLinkDestination.Rejected -> Unit
        }
    }

    private fun acceptPromoterAuthCallback(callbackUrl: String) {
        val currentAuthViewModel = authViewModel
        if (currentAuthViewModel == null) {
            pendingAuthCallback = callbackUrl
        } else {
            currentAuthViewModel.onIntent(AuthIntent.OpenPromoterActivation(callbackUrl))
        }
    }

    private fun dispatchPendingAuthCallback(viewModel: AuthViewModel) {
        val callbackUrl = pendingAuthCallback ?: return
        pendingAuthCallback = null
        viewModel.onIntent(AuthIntent.OpenPromoterActivation(callbackUrl))
    }
}

private fun newViewModelScope(dispatcherProvider: DispatcherProvider): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcherProvider.main)

private data class ConfiguredApp(
    val compositionRoot: KwaborCompositionRoot,
    val authPresenters: AuthPresenters,
)

private data class AuthPresenters(
    val auth: AuthPresenter,
    val passwordRecovery: PasswordRecoveryPresenter,
    val registration: RegistrationPresenter,
)
