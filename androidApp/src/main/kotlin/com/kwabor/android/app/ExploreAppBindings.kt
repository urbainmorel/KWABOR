package com.kwabor.android.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kwabor.android.presentation.auth.AuthIntent
import com.kwabor.android.presentation.auth.AuthSoftWallContext
import com.kwabor.android.presentation.explore.ExploreEffect
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.shared.presentation.favorites.FavoritesIntent

@Composable
internal fun ExploreEffectHandler(dependencies: HomeShellDependencies) {
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        dependencies.exploreViewModel.onIntent(ExploreIntent.LocationPermissionResult(granted))
    }
    LaunchedEffect(
        dependencies.exploreViewModel,
        dependencies.favoritesViewModel,
        dependencies.authViewModel,
        dependencies.viewerSessionScopeTracker,
    ) {
        dependencies.exploreViewModel.effects.collect { effect ->
            when (effect) {
                is ExploreEffect.AuthenticationRequired -> dependencies.handleAuthenticationRequired(effect)
                ExploreEffect.RequestLocationPermission -> {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                is ExploreEffect.FavoriteChanged -> dependencies.handleFavoriteChanged(effect)
            }
        }
    }
}

private fun HomeShellDependencies.handleAuthenticationRequired(effect: ExploreEffect.AuthenticationRequired) {
    if (effect.scope != viewerSessionScopeTracker.currentScope) return
    authViewModel.onIntent(
        AuthIntent.OpenSoftWall(
            AuthSoftWallContext(
                action = effect.kind.toProtectedAction(),
                suggestedCityId = effect.suggestedCityId,
            ),
        ),
    )
}

private fun HomeShellDependencies.handleFavoriteChanged(effect: ExploreEffect.FavoriteChanged) {
    if (effect.scope != viewerSessionScopeTracker.currentScope) return
    favoritesViewModel.onIntent(
        effect.toFavoritesIntent(),
    )
}

internal fun ExploreEffect.FavoriteChanged.toFavoritesIntent(): FavoritesIntent.ExternalFavoriteStateChanged =
    FavoritesIntent.ExternalFavoriteStateChanged(
        listingId = listingId,
        favorited = favorited,
        clientMutationSequence = clientMutationSequence,
        scope = scope,
    )
