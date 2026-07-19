package com.kwabor.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kwabor.android.app.KwaborApp
import com.kwabor.android.app.KwaborAppDependencies
import com.kwabor.android.app.KwaborAppRuntimeState
import com.kwabor.android.app.KwaborUnavailableApp
import com.kwabor.android.auth.AndroidLegalDocumentLauncher
import com.kwabor.android.auth.AndroidNotificationPermissionPolicy
import com.kwabor.android.auth.AndroidRegistrationLocationService
import com.kwabor.android.auth.SharedPreferencesAuthJourneyStore
import com.kwabor.android.auth.SharedPreferencesNotificationPrimingStore
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.auth.AuthViewModelDependencies
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.shared.app.KwaborCompositionRoot
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.PasswordRecoveryPresenter
import com.kwabor.shared.presentation.auth.RegistrationPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingDeepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        acceptDeepLink(intent)
        val configuredApp = configuredAppOrNull()
        if (configuredApp == null) {
            setContent { KwaborUnavailableApp() }
            return
        }

        showConfiguredApp(configuredApp)
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
        val dependencies = KwaborAppDependencies(
            exploreViewModel = createExploreViewModel(configuredApp.compositionRoot, strings),
            authViewModel = createAuthViewModel(
                configuredApp = configuredApp,
                strings = strings,
                applicationState = applicationState,
            ),
            onboardingViewModel = createOnboardingViewModel(
                applicationState,
                configuredApp.compositionRoot.dispatcherProvider,
            ),
            legalDocumentLauncher = AndroidLegalDocumentLauncher(applicationContext),
        )

        setContent {
            KwaborApp(
                dependencies = dependencies,
                runtimeState = KwaborAppRuntimeState(
                    pendingDeepLink = pendingDeepLink,
                    onDeepLinkConsumed = { pendingDeepLink.value = null },
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
                    strings = strings,
                    coroutineScope = newViewModelScope(compositionRoot.dispatcherProvider),
                )
            }
        },
    )[ExploreViewModel::class.java]

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
                        locationService = AndroidRegistrationLocationService(applicationContext),
                        notificationPermissionPolicy = AndroidNotificationPermissionPolicy(applicationContext),
                        notificationPrimingStore = SharedPreferencesNotificationPrimingStore(applicationContext),
                        authJourneyStore = SharedPreferencesAuthJourneyStore(applicationContext),
                        clockProvider = configuredApp.compositionRoot.clockProvider,
                        applyObservabilityConsent = applicationState.observability::updateConsent,
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
                    launchDecision = applicationState.introMediaManager.launchDecision,
                    track = applicationState.observability::track,
                    coroutineScope = newViewModelScope(dispatcherProvider),
                )
            }
        },
    )[OnboardingViewModel::class.java]

    private fun newViewModelScope(dispatcherProvider: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcherProvider.main)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDeepLink(intent)
    }

    private fun acceptDeepLink(sourceIntent: Intent) {
        pendingDeepLink.value = sourceIntent.dataString
        sourceIntent.data = null
    }
}

private data class ConfiguredApp(
    val compositionRoot: KwaborCompositionRoot,
    val authPresenters: AuthPresenters,
)

private data class AuthPresenters(
    val auth: AuthPresenter,
    val passwordRecovery: PasswordRecoveryPresenter,
    val registration: RegistrationPresenter,
)
