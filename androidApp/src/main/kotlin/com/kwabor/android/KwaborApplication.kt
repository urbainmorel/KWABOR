package com.kwabor.android

import android.app.Application
import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.android.onboarding.AndroidIntroMediaManager
import com.kwabor.android.onboarding.AndroidIntroVideoCache
import com.kwabor.shared.app.createAndroidKwaborCompositionRootOrNull
import com.kwabor.shared.domain.core.DefaultDispatcherProvider

class KwaborApplication : Application() {
    lateinit var observability: AndroidObservabilityController
        private set
    lateinit var introMediaManager: AndroidIntroMediaManager
        private set

    override fun onCreate() {
        super.onCreate()
        observability = AndroidObservabilityController.create(applicationContext)
        observability.start()
        val dispatcherProvider = compositionRoot?.dispatcherProvider ?: DefaultDispatcherProvider()
        introMediaManager = AndroidIntroMediaManager(
            observability = observability,
            cache = AndroidIntroVideoCache(
                context = applicationContext,
                dispatcherProvider = dispatcherProvider,
            ),
            dispatcherProvider = dispatcherProvider,
        )
        introMediaManager.start()
    }

    override fun onTerminate() {
        introMediaManager.close()
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
