package com.kwabor.android

import android.app.Application
import com.kwabor.shared.app.createAndroidKwaborCompositionRootOrNull

class KwaborApplication : Application() {
    val compositionRoot by lazy(LazyThreadSafetyMode.NONE) {
        createAndroidKwaborCompositionRootOrNull(
            context = this,
            environmentName = BuildConfig.KWABOR_ENVIRONMENT,
            supabaseUrl = BuildConfig.KWABOR_SUPABASE_URL,
            supabasePublishableKey = BuildConfig.KWABOR_SUPABASE_PUBLISHABLE_KEY,
        )
    }
}
