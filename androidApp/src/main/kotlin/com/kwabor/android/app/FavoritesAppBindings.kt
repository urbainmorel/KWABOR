package com.kwabor.android.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.kwabor.android.media.ListingMediaUrlPolicy
import com.kwabor.android.presentation.detail.CatalogDetailViewModel
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.android.presentation.explore.ExploreViewModel
import com.kwabor.android.presentation.favorites.FavoritesEffect
import com.kwabor.android.presentation.favorites.FavoritesViewModel
import com.kwabor.android.ui.screens.favorites.FavoritesScreen
import com.kwabor.android.ui.screens.favorites.FavoritesScreenActions
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.favorites.FavoritesIntent
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker

@Composable
internal fun FavoritesEffectHandler(
    favoritesViewModel: FavoritesViewModel,
    exploreViewModel: ExploreViewModel,
    catalogDetailViewModel: CatalogDetailViewModel,
    viewerSessionScopeTracker: ViewerSessionScopeTracker,
) {
    LaunchedEffect(
        favoritesViewModel,
        exploreViewModel,
        catalogDetailViewModel,
        viewerSessionScopeTracker,
    ) {
        favoritesViewModel.effects.collect { effect ->
            if (effect.scope != viewerSessionScopeTracker.currentScope) return@collect
            when (effect) {
                is FavoritesEffect.OpenCatalogDetail -> catalogDetailViewModel.onIntent(
                    CatalogDetailIntent.Open(effect.listingId),
                )
                is FavoritesEffect.FavoriteChanged -> exploreViewModel.onIntent(
                    ExploreIntent.FavoriteStateChanged(
                        listingId = effect.listingId,
                        favorited = effect.favorited,
                        scope = effect.scope,
                    ),
                )
            }
        }
    }
}

internal fun FavoritesViewModel.screenActions(onBack: () -> Unit): FavoritesScreenActions = FavoritesScreenActions(
    onBack = onBack,
    onFilterSelected = { filter -> onIntent(FavoritesIntent.SelectFilter(filter)) },
    onRetry = { onIntent(FavoritesIntent.Retry) },
    onRefresh = { onIntent(FavoritesIntent.Refresh) },
    onLoadNext = { onIntent(FavoritesIntent.LoadNext) },
    onOpenListing = { listingId -> onIntent(FavoritesIntent.OpenListing(listingId)) },
    onRemoveFavorite = { listingId -> onIntent(FavoritesIntent.RemoveFavorite(listingId)) },
)

internal fun NavGraphBuilder.favoritesChildRoute(
    navController: NavHostController,
    viewModel: FavoritesViewModel,
    strings: KwaborStrings,
    mediaUrlPolicy: ListingMediaUrlPolicy,
    paddingValues: PaddingValues,
) {
    composable<FavoritesRoute> {
        val state by viewModel.state.collectAsStateWithLifecycle()
        DisposableEffect(viewModel) {
            viewModel.onIntent(FavoritesIntent.ScreenAppeared)
            onDispose {
                viewModel.onIntent(FavoritesIntent.ScreenDisappeared)
            }
        }
        FavoritesScreen(
            state = state,
            strings = strings,
            mediaUrlPolicy = mediaUrlPolicy,
            actions = remember(viewModel, navController) {
                viewModel.screenActions(onBack = { navController.popBackStack() })
            },
            modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding()),
        )
    }
}

internal class FavoritesAccountNavigationPolicy(initialAccountId: String?) {
    private var accountId: String? = initialAccountId

    fun shouldPurgeFor(nextAccountId: String?): Boolean {
        val shouldPurge = accountId != null && accountId != nextAccountId
        accountId = nextAccountId
        return shouldPurge
    }
}

@Composable
internal fun FavoritesNavigationPrivacyEffect(
    accountId: String?,
    navController: NavHostController,
) {
    val policy = remember(navController) { FavoritesAccountNavigationPolicy(accountId) }
    LaunchedEffect(accountId, navController) {
        if (policy.shouldPurgeFor(accountId)) {
            navController.purgePrivateProfileChildren()
        }
    }
}
