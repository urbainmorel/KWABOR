package com.kwabor.android

import android.app.Application
import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.shared.app.createAndroidKwaborCompositionRootOrNull

class KwaborApplication : Application() {
    lateinit var observability: AndroidObservabilityController
        private set

    override fun onCreate() {
        super.onCreate()
        observability = AndroidObservabilityController.create(applicationContext)
        observability.start()
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
