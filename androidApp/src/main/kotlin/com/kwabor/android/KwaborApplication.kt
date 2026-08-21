package com.kwabor.android

import android.app.Application
import com.kwabor.android.observability.AndroidExploreFirstUsableViewportReporter
import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.android.observability.createAndroidObservabilityController
import com.kwabor.android.onboarding.FirstLaunchStore
import com.kwabor.android.onboarding.SharedPreferencesFirstLaunchStore
import com.kwabor.android.onboarding.createLegacyRemoteIntroCleanup
import com.kwabor.android.presentation.auth.AccountDeletionPurgeRegistry
import com.kwabor.shared.app.DefaultDispatcherProvider
import com.kwabor.shared.app.createAndroidKwaborCompositionRootOrNull
import com.kwabor.shared.domain.observability.DiagnosticCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KwaborApplication : Application() {
    internal val launchProcessState = LaunchProcessState()

    lateinit var observability: AndroidObservabilityController
        private set
    internal val exploreFirstUsableViewportReporter by lazy(LazyThreadSafetyMode.NONE) {
        AndroidExploreFirstUsableViewportReporter(observability)
    }
    internal lateinit var firstLaunchStore: FirstLaunchStore
        private set
    private var legacyCleanupScope: CoroutineScope? = null
    internal lateinit var accountDeletionWorkerScope: CoroutineScope
        private set
    internal val accountDeletionPurgeRegistry = AccountDeletionPurgeRegistry()

    override fun onCreate() {
        super.onCreate()
        val configuredRoot = compositionRoot
        observability = createAndroidObservabilityController(
            context = applicationContext,
            sessionTracker = configuredRoot?.consentedAppSessionTracker,
        )
        observability.start()
        val dispatcherProvider = configuredRoot?.dispatcherProvider ?: DefaultDispatcherProvider()
        accountDeletionWorkerScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
        firstLaunchStore = SharedPreferencesFirstLaunchStore(applicationContext)
        legacyCleanupScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io).also { scope ->
            scope.launch {
                val cleanupSucceeded = runCatching {
                    createLegacyRemoteIntroCleanup(applicationContext).run()
                }.getOrDefault(false)
                if (!cleanupSucceeded) {
                    observability.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)
                }
            }
        }
    }

    override fun onTerminate() {
        legacyCleanupScope?.cancel()
        legacyCleanupScope = null
        if (::accountDeletionWorkerScope.isInitialized) accountDeletionWorkerScope.cancel()
        observability.close()
        compositionRoot?.close()
        super.onTerminate()
    }

    val compositionRoot by lazy(LazyThreadSafetyMode.NONE) {
        createAndroidKwaborCompositionRootOrNull(
            context = this,
            environmentName = BuildConfig.KWABOR_ENVIRONMENT,
            supabaseUrl = BuildConfig.KWABOR_SUPABASE_URL,
            supabasePublishableKey = BuildConfig.KWABOR_SUPABASE_PUBLISHABLE_KEY,
        )
    }
}
