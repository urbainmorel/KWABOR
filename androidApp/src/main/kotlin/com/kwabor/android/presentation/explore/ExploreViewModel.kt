package com.kwabor.android.presentation.explore

import androidx.lifecycle.ViewModel
import com.kwabor.android.auth.ApproximateLocationResult
import com.kwabor.android.auth.ApproximateLocationService
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.explore.ExploreChip
import com.kwabor.shared.presentation.explore.ExploreInteractionKind
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.ExploreRuntime
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import com.kwabor.shared.presentation.explore.ExploreEffect as SharedExploreEffect
import com.kwabor.shared.presentation.explore.ExploreIntent as SharedExploreIntent

internal sealed interface ExploreIntent {
    sealed interface Feed : ExploreIntent

    sealed interface Location : ExploreIntent

    sealed interface Viewer : ExploreIntent

    data class SelectTab(val tab: ExploreTab) : Feed

    data class SelectChip(val chip: ExploreChip) : Feed

    data object Retry : Feed

    data object Refresh : Feed

    data object LoadNext : Feed

    data class SelectCity(val cityId: String) : Location

    data class ToggleLike(val listingId: String) : Viewer

    data class ToggleFavorite(val listingId: String) : Viewer

    data class LocationPermissionResult(val granted: Boolean) : Location

    data object OpenCitySelector : Location

    data object CloseCitySelector : Location

    data object RequestLocation : Location

    data object ReplayPendingInteraction : Viewer

    data object ClearPendingAuthentication : Viewer

    data class ViewerContextChanged(val scope: ViewerSessionScope) : Viewer

    data class FavoriteStateChanged(
        val listingId: String,
        val favorited: Boolean,
        val scope: ViewerSessionScope,
    ) : Viewer
}

internal sealed interface ExploreEffect {
    data class AuthenticationRequired(
        val kind: ExploreInteractionKind,
        val suggestedCityId: String?,
        val scope: ViewerSessionScope,
    ) : ExploreEffect

    data class FavoriteChanged(
        val listingId: String,
        val favorited: Boolean,
        val scope: ViewerSessionScope,
    ) : ExploreEffect

    data object RequestLocationPermission : ExploreEffect
}

internal class ExploreViewModel(
    presenter: ExplorePresenter,
    private val locationService: ApproximateLocationService,
    strings: KwaborStrings,
    private val coroutineScope: CoroutineScope,
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
    private val track: (AnalyticsEvent) -> Unit = {},
) : ViewModel() {
    private val runtime = ExploreRuntime(
        presenter = presenter,
        strings = strings,
        coroutineScope = coroutineScope,
    )
    val state: StateFlow<ExploreUiState> = runtime.state
    val effects: Flow<ExploreEffect> = runtime.effects.transform { effect ->
        when (effect) {
            is SharedExploreEffect.AuthenticationRequired -> if (
                effect.scope == viewerSessionScopeTracker.currentScope
            ) {
                emit(
                    ExploreEffect.AuthenticationRequired(
                        kind = effect.kind,
                        suggestedCityId = effect.suggestedCityId,
                        scope = effect.scope,
                    ),
                )
            }
            is SharedExploreEffect.FavoriteChanged -> if (effect.scope == viewerSessionScopeTracker.currentScope) {
                emit(
                    ExploreEffect.FavoriteChanged(
                        listingId = effect.listingId,
                        favorited = effect.favorited,
                        scope = effect.scope,
                    ),
                )
            }
            SharedExploreEffect.RequestLocation -> emit(ExploreEffect.RequestLocationPermission)
            is SharedExploreEffect.ProtectedActionReplayed -> if (
                effect.scope == viewerSessionScopeTracker.currentScope
            ) {
                effect.analyticsEvent?.let(track)
            }
        }
    }

    private var locationJob: Job? = null

    fun onIntent(intent: ExploreIntent) {
        when (intent) {
            is ExploreIntent.Feed -> runtime.dispatch(intent.toSharedIntent())
            is ExploreIntent.Location -> handleLocationIntent(intent)
            is ExploreIntent.Viewer -> handleViewerIntent(intent)
        }
    }

    private fun handleLocationIntent(intent: ExploreIntent.Location) {
        when (intent) {
            is ExploreIntent.SelectCity -> {
                cancelLocationRequest()
                runtime.dispatch(SharedExploreIntent.SelectCity(intent.cityId))
            }
            is ExploreIntent.LocationPermissionResult -> resolveLocationPermission(intent.granted)
            ExploreIntent.OpenCitySelector -> runtime.dispatch(SharedExploreIntent.OpenCitySelector)
            ExploreIntent.CloseCitySelector -> {
                cancelLocationRequest()
                runtime.dispatch(SharedExploreIntent.CloseCitySelector)
            }
            ExploreIntent.RequestLocation -> runtime.dispatch(SharedExploreIntent.RequestLocation)
        }
    }

    private fun handleViewerIntent(intent: ExploreIntent.Viewer) {
        when (intent) {
            is ExploreIntent.ToggleLike -> runtime.dispatch(SharedExploreIntent.ToggleLike(intent.listingId))
            is ExploreIntent.ToggleFavorite -> runtime.dispatch(SharedExploreIntent.ToggleFavorite(intent.listingId))
            ExploreIntent.ReplayPendingInteraction -> runtime.dispatch(SharedExploreIntent.ReplayPendingInteraction)
            ExploreIntent.ClearPendingAuthentication ->
                runtime.dispatch(SharedExploreIntent.ClearPendingAuthentication)
            is ExploreIntent.ViewerContextChanged ->
                runtime.dispatch(SharedExploreIntent.ViewerContextChanged(intent.scope))
            is ExploreIntent.FavoriteStateChanged -> runtime.dispatch(
                SharedExploreIntent.FavoriteStateChanged(
                    listingId = intent.listingId,
                    favorited = intent.favorited,
                    scope = intent.scope,
                ),
            )
        }
    }

    override fun onCleared() {
        cancelLocationRequest()
        runtime.close()
        coroutineScope.cancel()
        super.onCleared()
    }

    private fun resolveLocationPermission(granted: Boolean) {
        if (!state.value.isLocating) return
        if (!granted) {
            runtime.dispatch(SharedExploreIntent.LocationPermissionDenied)
            return
        }
        cancelLocationRequest()
        locationJob = coroutineScope.launch {
            val intent = when (val result = locationService.currentApproximateLocation()) {
                is ApproximateLocationResult.Available -> SharedExploreIntent.LocationCoordinates(
                    latitude = result.latitude,
                    longitude = result.longitude,
                )
                ApproximateLocationResult.PermissionDenied,
                is ApproximateLocationResult.PermissionFailure,
                -> SharedExploreIntent.LocationPermissionDenied
                ApproximateLocationResult.LocationDisabled -> SharedExploreIntent.LocationDisabled
                ApproximateLocationResult.Unavailable,
                is ApproximateLocationResult.UnavailableFailure,
                -> SharedExploreIntent.LocationUnavailable
            }
            runtime.dispatch(intent)
        }
    }

    private fun cancelLocationRequest() {
        locationJob?.cancel()
        locationJob = null
    }
}

private fun ExploreIntent.Feed.toSharedIntent(): SharedExploreIntent.Feed = when (this) {
    is ExploreIntent.SelectTab -> SharedExploreIntent.SelectTab(tab)
    is ExploreIntent.SelectChip -> SharedExploreIntent.SelectChip(chip.id)
    ExploreIntent.Retry -> SharedExploreIntent.Retry
    ExploreIntent.Refresh -> SharedExploreIntent.Refresh
    ExploreIntent.LoadNext -> SharedExploreIntent.LoadNext
}
