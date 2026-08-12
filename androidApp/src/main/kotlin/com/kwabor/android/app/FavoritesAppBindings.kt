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
import com.kwabor.shared.presentation.navigation.RootNavigationProfile
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
                    effect.toExploreIntent(),
                )
            }
        }
    }
}

internal fun FavoritesEffect.FavoriteChanged.toExploreIntent(): ExploreIntent.FavoriteStateChanged =
    ExploreIntent.FavoriteStateChanged(
        listingId = listingId,
        favorited = favorited,
        clientMutationSequence = clientMutationSequence,
        scope = scope,
    )

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
    rootNavigationProfile: RootNavigationProfile,
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
            showClosedBetaDemoDisclosure = rootNavigationProfile == RootNavigationProfile.ClosedBetaCatalog,
        )
    }
}

internal class FavoritesAccountNavigationPolicy(initialAccountId: String?) {
    private var accountId: String? = initialAccountId
    private var isFirstEvaluation = true

    fun decisionFor(nextAccountId: String?): FavoritesNavigationPrivacyDecision {
        val decision = when {
            isFirstEvaluation && nextAccountId == null ->
                FavoritesNavigationPrivacyDecision.ResetToHomeAndPurge
            accountId != null && accountId != nextAccountId ->
                FavoritesNavigationPrivacyDecision.PurgePrivateChildren
            else -> FavoritesNavigationPrivacyDecision.None
        }
        isFirstEvaluation = false
        accountId = nextAccountId
        return decision
    }
}

internal enum class FavoritesNavigationPrivacyDecision {
    None,
    PurgePrivateChildren,
    ResetToHomeAndPurge,
}

internal fun NavHostController.applyFavoritesNavigationPrivacy(decision: FavoritesNavigationPrivacyDecision) {
    when (decision) {
        FavoritesNavigationPrivacyDecision.None -> Unit
        FavoritesNavigationPrivacyDecision.PurgePrivateChildren -> purgePrivateProfileChildren()
        FavoritesNavigationPrivacyDecision.ResetToHomeAndPurge -> resetToHomeAfterAuthenticationEnd()
    }
}

@Composable
internal fun FavoritesNavigationPrivacyEffect(accountId: String?, navController: NavHostController) {
    val policy = remember(navController) { FavoritesAccountNavigationPolicy(accountId) }
    LaunchedEffect(accountId, navController) {
        navController.applyFavoritesNavigationPrivacy(policy.decisionFor(accountId))
    }
}
