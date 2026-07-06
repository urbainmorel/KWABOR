package com.kwabor.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.kwabor.shared.app.KwaborApp
import com.kwabor.shared.app.KwaborRuntimeDependencies

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dependencies = remember {
                KwaborRuntimeDependencies.createOrNull(
                    supabaseUrl = BuildConfig.KWABOR_SUPABASE_URL,
                    supabasePublishableKey = BuildConfig.KWABOR_SUPABASE_PUBLISHABLE_KEY,
                )
            }
            KwaborApp(
                catalogRepository = dependencies?.catalogRepository,
                clockProvider = dependencies?.clockProvider,
            )
        }
    }
}
