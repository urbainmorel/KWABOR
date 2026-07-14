package com.kwabor.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kwabor.android.app.KwaborApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val compositionRoot = (application as KwaborApplication).compositionRoot
        setContent {
            KwaborApp(
                catalogRepository = compositionRoot?.catalogRepository,
                clockProvider = compositionRoot?.clockProvider,
                authRepository = compositionRoot?.authRepository,
            )
        }
    }
}
