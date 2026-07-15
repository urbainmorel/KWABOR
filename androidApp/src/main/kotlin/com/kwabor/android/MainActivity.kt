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
import com.kwabor.android.onboarding.SharedPreferencesFirstLaunchStore
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.onboarding.OnboardingViewModel
import com.kwabor.shared.app.KwaborCompositionRoot
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingDeepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        acceptDeepLink(intent)
        val compositionRoot = (application as KwaborApplication).compositionRoot
        val authPresenter = compositionRoot?.authPresenter
        if (compositionRoot == null || authPresenter == null) {
            setContent { KwaborUnavailableApp() }
            return
        }

        showConfiguredApp(compositionRoot, authPresenter)
    }

    private fun showConfiguredApp(compositionRoot: KwaborCompositionRoot, authPresenter: AuthPresenter) {
        val strings = stringsFor(AppLocale.French)
        val applicationState = application as KwaborApplication
        val dependencies = KwaborAppDependencies(
            exploreViewModel = createExploreViewModel(compositionRoot, strings),
            authViewModel = createAuthViewModel(authPresenter, strings, compositionRoot.dispatcherProvider),
            onboardingViewModel = createOnboardingViewModel(applicationState, compositionRoot.dispatcherProvider),
        )

        setContent {
            KwaborApp(
                dependencies = dependencies,
                runtimeState = KwaborAppRuntimeState(
                    remoteIntroVideoFile = applicationState.introMediaManager.remoteVideoFile,
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
        authPresenter: AuthPresenter,
        strings: KwaborStrings,
        dispatcherProvider: DispatcherProvider,
    ): AuthViewModel = ViewModelProvider(
        owner = this,
        factory = viewModelFactory {
            initializer {
                AuthViewModel(
                    presenter = authPresenter,
                    strings = strings,
                    coroutineScope = newViewModelScope(dispatcherProvider),
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
                    firstLaunchStore = SharedPreferencesFirstLaunchStore(applicationContext),
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
