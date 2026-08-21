package com.kwabor.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kwabor.android.observability.AndroidExploreFirstUsableViewportReporter
import com.kwabor.android.presentation.search.SearchIntent
import com.kwabor.android.ui.screens.explore.ExploreScreen
import com.kwabor.android.ui.screens.explore.ExploreScreenActions
import com.kwabor.android.ui.screens.explore.ExploreScreenUiModel
import com.kwabor.android.ui.screens.explore.ExploreViewportPerformanceBinding
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.detail.CatalogDetailUiState
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
    val detailState by dependencies.catalogDetailViewModel.state.collectAsStateWithLifecycle()
    val performanceCollectionAllowed by
        dependencies.observabilityController.performanceCollectionAllowed.collectAsStateWithLifecycle()
    val performanceSampleGeneration = rememberExplorePerformanceSampleGeneration(
        reporter = dependencies.exploreFirstUsableViewportReporter,
        surfaceVisible =
        !searchState.isActive &&
            !exploreState.isCitySelectorOpen &&
            detailState is CatalogDetailUiState.Closed,
        performanceCollectionAllowed = performanceCollectionAllowed,
    )
    SearchExploreContextEffect(dependencies = dependencies, exploreState = exploreState)

    ExploreScreen(
        model = ExploreScreenUiModel(
            state = exploreState,
            searchState = searchState,
            isGuestSession = isGuestSession,
            showClosedBetaDemoDisclosure = dependencies.rootNavigationProfile ==
                com.kwabor.shared.presentation.navigation.RootNavigationProfile.ClosedBetaCatalog,
            showGuideDiscoveryEntry = dependencies.rootNavigationProfile ==
                com.kwabor.shared.presentation.navigation.RootNavigationProfile.Full,
            viewportPerformance = ExploreViewportPerformanceBinding(
                generation = performanceSampleGeneration,
                onCommitted = dependencies.exploreFirstUsableViewportReporter::onViewportCommitted,
            ),
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
private fun rememberExplorePerformanceSampleGeneration(
    reporter: AndroidExploreFirstUsableViewportReporter,
    surfaceVisible: Boolean,
    performanceCollectionAllowed: Boolean,
): Long? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var generation by remember(reporter) { mutableStateOf<Long?>(null) }
    DisposableEffect(lifecycle, reporter, surfaceVisible, performanceCollectionAllowed) {
        fun hideSurface() {
            reporter.onHidden()
            generation = null
        }

        fun reconcileSurface() {
            if (
                surfaceVisible &&
                performanceCollectionAllowed &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                generation = reporter.onVisible()
            } else {
                hideSurface()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> reconcileSurface()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> hideSurface()
                Lifecycle.Event.ON_CREATE,
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_ANY,
                -> Unit
            }
        }
        lifecycle.addObserver(observer)
        reconcileSurface()
        onDispose {
            lifecycle.removeObserver(observer)
            hideSurface()
        }
    }
    return generation
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
