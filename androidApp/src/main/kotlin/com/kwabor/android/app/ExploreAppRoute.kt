package com.kwabor.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kwabor.android.presentation.search.SearchIntent
import com.kwabor.android.ui.screens.explore.ExploreScreen
import com.kwabor.android.ui.screens.explore.ExploreScreenActions
import com.kwabor.android.ui.screens.explore.ExploreScreenUiModel
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.explore.ExploreUiState

@Composable
internal fun ExploreRoute(
    dependencies: HomeShellDependencies,
    strings: KwaborStrings,
    isGuestSession: Boolean,
    modifier: Modifier = Modifier,
    onGuideDiscoveryRequested: () -> Unit,
) {
    val exploreState by dependencies.exploreViewModel.state.collectAsStateWithLifecycle()
    val searchState by dependencies.searchViewModel.state.collectAsStateWithLifecycle()
    SearchExploreContextEffect(dependencies = dependencies, exploreState = exploreState)

    ExploreScreen(
        model = ExploreScreenUiModel(
            state = exploreState,
            searchState = searchState,
            isGuestSession = isGuestSession,
        ),
        strings = strings,
        mediaUrlPolicy = dependencies.listingMediaUrlPolicy,
        modifier = modifier,
        actions = rememberExploreRouteActions(
            dependencies = dependencies,
            exploreState = exploreState,
            onGuideDiscoveryRequested = onGuideDiscoveryRequested,
        ),
    )
}

@Composable
private fun SearchExploreContextEffect(dependencies: HomeShellDependencies, exploreState: ExploreUiState) {
    LaunchedEffect(
        dependencies.searchViewModel,
        exploreState.selectedTab,
        exploreState.selectedChipId,
        exploreState.selectedCityId,
        exploreState.availableCities,
        exploreState.currency,
    ) {
        dependencies.searchViewModel.onIntent(SearchIntent.UpdateExploreContext(exploreState))
    }
}

@Composable
private fun rememberExploreRouteActions(
    dependencies: HomeShellDependencies,
    exploreState: ExploreUiState,
    onGuideDiscoveryRequested: () -> Unit,
): ExploreScreenActions = remember(
    dependencies.exploreViewModel,
    dependencies.searchViewModel,
    dependencies.catalogDetailViewModel,
    onGuideDiscoveryRequested,
    exploreState.selectedTab,
    exploreState.selectedChipId,
    exploreState.selectedCityId,
    exploreState.availableCities,
    exploreState.currency,
) {
    dependencies.exploreViewModel.detailEnabledScreenActions(
        onListingClick = { listingId ->
            dependencies.catalogDetailViewModel.onIntent(CatalogDetailIntent.Open(listingId))
        },
        onGuideDiscoveryClick = onGuideDiscoveryRequested,
    ).withSearch(
        searchViewModel = dependencies.searchViewModel,
        exploreState = exploreState,
    )
}
