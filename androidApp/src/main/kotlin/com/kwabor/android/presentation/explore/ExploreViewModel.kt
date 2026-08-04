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

    data class SelectTab(val tab: ExploreTab) : Feed

    data class SelectChip(val chip: ExploreChip) : Feed

    data object Retry : Feed

    data object Refresh : Feed

    data object LoadNext : Feed

    data class SelectCity(val cityId: String) : ExploreIntent

    data class ToggleLike(val listingId: String) : ExploreIntent

    data class ToggleFavorite(val listingId: String) : ExploreIntent

    data class LocationPermissionResult(val granted: Boolean) : ExploreIntent

    data object OpenCitySelector : ExploreIntent

    data object CloseCitySelector : ExploreIntent

    data object RequestLocation : ExploreIntent

    data object ReplayPendingInteraction : ExploreIntent

    data class ViewerContextChanged(val viewerId: String?) : ExploreIntent
}

internal sealed interface ExploreEffect {
    data class AuthenticationRequired(
        val kind: ExploreInteractionKind,
        val suggestedCityId: String?,
    ) : ExploreEffect

    data object RequestLocationPermission : ExploreEffect
}

internal class ExploreViewModel(
    presenter: ExplorePresenter,
    private val locationService: ApproximateLocationService,
    strings: KwaborStrings,
    private val coroutineScope: CoroutineScope,
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
            is SharedExploreEffect.AuthenticationRequired -> emit(
                ExploreEffect.AuthenticationRequired(
                    kind = effect.kind,
                    suggestedCityId = effect.suggestedCityId,
                ),
            )
            SharedExploreEffect.RequestLocation -> emit(ExploreEffect.RequestLocationPermission)
            is SharedExploreEffect.ProtectedActionReplayed -> effect.analyticsEvent?.let(track)
        }
    }

    private var locationJob: Job? = null

    fun onIntent(intent: ExploreIntent) {
        when (intent) {
            is ExploreIntent.Feed -> runtime.dispatch(intent.toSharedIntent())
            is ExploreIntent.SelectCity -> {
                cancelLocationRequest()
                runtime.dispatch(SharedExploreIntent.SelectCity(intent.cityId))
            }
            is ExploreIntent.ToggleLike -> runtime.dispatch(SharedExploreIntent.ToggleLike(intent.listingId))
            is ExploreIntent.ToggleFavorite -> runtime.dispatch(SharedExploreIntent.ToggleFavorite(intent.listingId))
            is ExploreIntent.LocationPermissionResult -> resolveLocationPermission(intent.granted)
            ExploreIntent.OpenCitySelector -> runtime.dispatch(SharedExploreIntent.OpenCitySelector)
            ExploreIntent.CloseCitySelector -> {
                cancelLocationRequest()
                runtime.dispatch(SharedExploreIntent.CloseCitySelector)
            }
            ExploreIntent.RequestLocation -> runtime.dispatch(SharedExploreIntent.RequestLocation)
            ExploreIntent.ReplayPendingInteraction -> runtime.dispatch(SharedExploreIntent.ReplayPendingInteraction)
            is ExploreIntent.ViewerContextChanged ->
                runtime.dispatch(SharedExploreIntent.ViewerContextChanged(intent.viewerId))
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
