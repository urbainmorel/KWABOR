package com.kwabor.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kwabor.android.app.KwaborApp
import com.kwabor.android.app.KwaborUnavailableApp
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.shared.app.KwaborCompositionRoot
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val pendingDeepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
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
        val exploreViewModel = ViewModelProvider(
            owner = this,
            factory = viewModelFactory {
                initializer {
                    ExploreViewModel(
                        presenter = compositionRoot.explorePresenter,
                        strings = strings,
                        coroutineScope = MainScope(),
                    )
                }
            },
        )[ExploreViewModel::class.java]
        val authViewModel = ViewModelProvider(
            owner = this,
            factory = viewModelFactory {
                initializer {
                    AuthViewModel(
                        presenter = authPresenter,
                        strings = strings,
                        coroutineScope = MainScope(),
                    )
                }
            },
        )[AuthViewModel::class.java]

        setContent {
            KwaborApp(
                exploreViewModel = exploreViewModel,
                authViewModel = authViewModel,
                pendingDeepLink = pendingDeepLink,
                onDeepLinkConsumed = { pendingDeepLink.value = null },
            )
        }
    }

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
