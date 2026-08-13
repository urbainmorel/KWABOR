package com.kwabor.android.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthProtectedAction
import com.kwabor.android.presentation.auth.AuthSoftWallContext
import com.kwabor.android.presentation.auth.AuthViewModel
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import com.kwabor.shared.presentation.navigation.RootNavigationProfile
import com.kwabor.shared.presentation.navigation.isVisibleIn
import kotlinx.coroutines.launch

internal data class KwaborNavigationBindings(
    val pendingDestinationKey: String?,
    val requestDestination: (RootNavigationDestination) -> Unit,
    val effectActions: KwaborAppEffectActions,
)

internal data class RootEffectActions(
    val onDestinationRequested: (RootNavigationDestination) -> Unit,
    val onAuthenticatedDestinationRequested: (RootNavigationDestination) -> Unit,
    val onAuthenticationEnded: () -> Unit,
    val onDestinationResolved: () -> Unit,
    val onDeepLinksReset: () -> Unit,
)

internal data class KwaborAppEffectActions(
    val root: RootEffectActions,
    val deepLink: AndroidNavigationDeepLinkDispatchActions,
)

internal data class KwaborDeepLinkCallbacks(
    val onAcknowledged: (Long) -> Unit,
    val onReset: () -> Unit,
)

private data class KwaborAppEffectCallbacks(
    val onDestinationResolved: () -> Unit,
    val deepLink: KwaborDeepLinkCallbacks,
    val onUnavailableRoot: () -> Unit,
)

@Composable
internal fun rememberKwaborNavigationBindings(
    navController: NavHostController,
    state: HomeShellState,
    dependencies: HomeShellDependencies,
    snackbarHostState: SnackbarHostState,
    deepLinkCallbacks: KwaborDeepLinkCallbacks,
): KwaborNavigationBindings {
    val coroutineScope = rememberCoroutineScope()
    val unavailableRootMessage = stringsFor(AppLocale.French).rootDestinationUnavailable
    var pendingDestinationKey by rememberSaveable { mutableStateOf<String?>(null) }
    val requestDestination = rootDestinationRequester(
        navController,
        state.auth.isAuthenticated,
        dependencies.rootNavigationProfile,
        dependencies.authViewModel,
        { destination -> pendingDestinationKey = destination.routeKey },
    )
    val callbacks = KwaborAppEffectCallbacks(
        onDestinationResolved = { pendingDestinationKey = null },
        deepLink = deepLinkCallbacks,
        onUnavailableRoot = {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(unavailableRootMessage)
            }
        },
    )
    return KwaborNavigationBindings(
        pendingDestinationKey = pendingDestinationKey,
        requestDestination = requestDestination,
        effectActions = kwaborAppEffectActions(navController, dependencies, requestDestination, callbacks),
    )
}

private fun kwaborAppEffectActions(
    navController: NavHostController,
    dependencies: HomeShellDependencies,
    requestDestination: (RootNavigationDestination) -> Unit,
    callbacks: KwaborAppEffectCallbacks,
): KwaborAppEffectActions = KwaborAppEffectActions(
    root = RootEffectActions(
        onDestinationRequested = requestDestination,
        onAuthenticatedDestinationRequested = { destination ->
            navController.navigateToRoot(destination, dependencies.rootNavigationProfile)
        },
        onAuthenticationEnded = navController::resetToHomeAfterAuthenticationEnd,
        onDestinationResolved = callbacks.onDestinationResolved,
        onDeepLinksReset = callbacks.deepLink.onReset,
    ),
    deepLink = AndroidNavigationDeepLinkDispatchActions(
        onRootDestination = requestDestination,
        onHomeDestination = {
            navController.navigateToRoot(RootNavigationDestination.Home, dependencies.rootNavigationProfile)
        },
        onUnavailableRoot = callbacks.onUnavailableRoot,
        onCatalogDetailOpen = { listingId ->
            dependencies.catalogDetailViewModel.onIntent(CatalogDetailIntent.Open(listingId))
        },
        onAcknowledged = callbacks.deepLink.onAcknowledged,
    ),
)

private val rootDestinationRequester =
    {
            navController: NavHostController,
            isAuthenticated: Boolean,
            rootNavigationProfile: RootNavigationProfile,
            authViewModel: AuthViewModel,
            onAuthenticationRequired: (RootNavigationDestination) -> Unit,
        ->
        { destination: RootNavigationDestination ->
            if (!destination.isVisibleIn(rootNavigationProfile)) {
                navController.navigateToRoot(RootNavigationDestination.Home, rootNavigationProfile)
            } else if (destination == RootNavigationDestination.Home || isAuthenticated) {
                navController.navigateToRoot(destination, rootNavigationProfile)
            } else {
                onAuthenticationRequired(destination)
                authViewModel.onIntent(
                    AuthIntent.OpenSoftWall(
                        AuthSoftWallContext(
                            action = AuthProtectedAction.Other,
                            suggestedCityId = null,
                        ),
                    ),
                )
            }
        }
    }
