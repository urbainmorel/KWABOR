package com.kwabor.android

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.kwabor.android.app.AndroidDeepLinkSlotViewModel
import com.kwabor.android.app.AndroidSensitiveAuthDeepLinkPolicy
import com.kwabor.android.app.KwaborApp
import com.kwabor.android.app.KwaborAppRuntimeState
import com.kwabor.android.app.KwaborUnavailableApp
import com.kwabor.android.auth.AndroidDeepLinkClassifier
import com.kwabor.android.auth.AndroidDeepLinkDestination
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
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
        val applicationState = application as KwaborApplication
        applicationState.observability.retryPendingMaintenance()
        notifyAuthenticationForeground(authViewModel?.let { it::onForeground })
        notifyInteractionForeground(applicationState.compositionRoot?.interactionCoordinator?.let { it::onForeground })
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
        val applicationState = application as KwaborApplication
        val appFactory = MainActivityAppFactory(
            activity = this,
            applicationState = applicationState,
            configuredApp = configuredApp,
            strings = stringsFor(AppLocale.French),
        )
        val configuredAuthViewModel = appFactory.createAuthViewModel()
        authViewModel = configuredAuthViewModel
        configuredAuthViewModel.attachGoogleIdentityActivity(this)
        dispatchPendingAuthCallback(configuredAuthViewModel)
        val dependencies = appFactory.createDependencies(configuredAuthViewModel)
        val runtimeState = KwaborAppRuntimeState(
            pendingDeepLink = deepLinkSlotViewModel.delivery,
            launchSplashExited = launchSplashExited,
            onDeepLinkAcknowledged = deepLinkSlotViewModel::acknowledge,
            onDeepLinksReset = deepLinkSlotViewModel::resetForSensitiveAuthTransition,
        )

        setContent {
            KwaborApp(dependencies = dependencies, runtimeState = runtimeState)
        }
    }

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

internal fun notifyInteractionForeground(onForeground: (() -> Unit)?) {
    onForeground?.invoke()
}

internal fun notifyAuthenticationForeground(onForeground: (() -> Unit)?) {
    onForeground?.invoke()
}
