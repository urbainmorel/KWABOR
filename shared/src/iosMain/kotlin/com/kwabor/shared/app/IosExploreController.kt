package com.kwabor.shared.app

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExploreEffect
import com.kwabor.shared.presentation.explore.ExploreIntent
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.ExploreRuntime
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.initialExploreUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class IosExploreEffect {
    RequireAuthentication,
    RequestLocation,
    ;

    val requiresAuthentication: Boolean
        get() = this == RequireAuthentication

    val requestsLocation: Boolean
        get() = this == RequestLocation
}

class IosExploreFeedActions internal constructor(
    private val dispatch: (ExploreIntent) -> Unit,
) {
    fun selectTab(tab: ExploreTab) {
        dispatch(ExploreIntent.SelectTab(tab))
    }

    fun selectChip(chipId: String) {
        dispatch(ExploreIntent.SelectChip(chipId))
    }

    fun retry() {
        dispatch(ExploreIntent.Retry)
    }

    fun refresh() {
        dispatch(ExploreIntent.Refresh)
    }

    fun loadNext() {
        dispatch(ExploreIntent.LoadNext)
    }
}

class IosExploreCityActions internal constructor(
    private val dispatch: (ExploreIntent) -> Unit,
) {
    fun openCitySelector() {
        dispatch(ExploreIntent.OpenCitySelector)
    }

    fun closeCitySelector() {
        dispatch(ExploreIntent.CloseCitySelector)
    }

    fun selectCity(cityId: String) {
        dispatch(ExploreIntent.SelectCity(cityId))
    }

    fun requestLocation() {
        dispatch(ExploreIntent.RequestLocation)
    }

    fun locationCoordinates(latitude: Double, longitude: Double) {
        dispatch(ExploreIntent.LocationCoordinates(latitude = latitude, longitude = longitude))
    }

    fun locationPermissionDenied() {
        dispatch(ExploreIntent.LocationPermissionDenied)
    }

    fun locationDisabled() {
        dispatch(ExploreIntent.LocationDisabled)
    }

    fun locationUnavailable() {
        dispatch(ExploreIntent.LocationUnavailable)
    }
}

class IosExploreInteractionActions internal constructor(
    private val dispatch: (ExploreIntent) -> Unit,
) {
    fun toggleLike(listingId: String) {
        dispatch(ExploreIntent.ToggleLike(listingId))
    }

    fun toggleFavorite(listingId: String) {
        dispatch(ExploreIntent.ToggleFavorite(listingId))
    }

    fun replayPendingInteraction() {
        dispatch(ExploreIntent.ReplayPendingInteraction)
    }

    fun updateViewerContext(viewerId: String?) {
        dispatch(ExploreIntent.ViewerContextChanged(viewerId))
    }
}

internal interface IosExploreRuntime {
    val state: StateFlow<ExploreUiState>
    val effects: Flow<ExploreEffect>

    fun dispatch(intent: ExploreIntent)

    fun close()
}

private class DefaultIosExploreRuntime(
    private val delegate: ExploreRuntime,
) : IosExploreRuntime {
    override val state: StateFlow<ExploreUiState> = delegate.state
    override val effects: Flow<ExploreEffect> = delegate.effects

    override fun dispatch(intent: ExploreIntent) {
        delegate.dispatch(intent)
    }

    override fun close() {
        delegate.close()
    }
}

class IosExploreController private constructor(
    runtimeProvider: (CoroutineScope, KwaborStrings) -> IosExploreRuntime?,
    dispatcherProvider: DispatcherProvider,
) {
    internal constructor(
        presenter: ExplorePresenter?,
        dispatcherProvider: DispatcherProvider,
    ) : this(
        runtimeProvider = { scope, strings ->
            presenter?.let { currentPresenter ->
                DefaultIosExploreRuntime(
                    ExploreRuntime(
                        presenter = currentPresenter,
                        strings = strings,
                        coroutineScope = scope,
                    ),
                )
            }
        },
        dispatcherProvider = dispatcherProvider,
    )

    internal constructor(
        runtime: IosExploreRuntime?,
        dispatcherProvider: DispatcherProvider,
    ) : this(
        runtimeProvider = { _, _ -> runtime },
        dispatcherProvider = dispatcherProvider,
    )

    val strings: KwaborStrings = stringsFor(AppLocale.French)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private val runtime = runtimeProvider(scope, strings)
    private var stateObserver: ((ExploreUiState) -> Unit)? = null
    private var effectObserver: ((IosExploreEffect) -> Unit)? = null
    private var observationVersion = 0L
    private var deliveredStateVersion = -1L
    private var deliveredState: ExploreUiState? = null
    private var isClosed = false

    val feedActions = IosExploreFeedActions(::dispatch)
    val cityActions = IosExploreCityActions(::dispatch)
    val interactionActions = IosExploreInteractionActions(::dispatch)

    var currentState: ExploreUiState = runtime?.state?.value ?: unavailableState(strings)
        private set

    val isConfigured: Boolean get() = runtime != null

    init {
        runtime?.let { currentRuntime ->
            scope.launch {
                currentRuntime.state.collect { updatedState ->
                    currentState = updatedState
                    publishStateIfNeeded(updatedState)
                }
            }
            scope.launch {
                currentRuntime.effects.collect { effect ->
                    effectObserver?.invoke(effect.toIosEffect())
                }
            }
        }
    }

    fun observe(stateObserver: (ExploreUiState) -> Unit, effectObserver: (IosExploreEffect) -> Unit) {
        if (isClosed) return
        observationVersion += 1
        this.stateObserver = stateObserver
        this.effectObserver = effectObserver
        deliveredStateVersion = -1L
        deliveredState = null
        val version = observationVersion
        scope.launch {
            if (!isClosed && version == observationVersion) {
                publishStateIfNeeded(currentState)
            }
        }
    }

    fun unobserve() {
        observationVersion += 1
        stateObserver = null
        effectObserver = null
        deliveredStateVersion = -1L
        deliveredState = null
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        unobserve()
        runtime?.close()
        scope.cancel()
    }

    private fun dispatch(intent: ExploreIntent) {
        if (!isClosed) {
            runtime?.dispatch(intent)
        }
    }

    private fun publishStateIfNeeded(state: ExploreUiState) {
        val observer = stateObserver ?: return
        if (deliveredStateVersion == observationVersion && deliveredState == state) return
        deliveredStateVersion = observationVersion
        deliveredState = state
        observer(state)
    }
}

private fun unavailableState(strings: KwaborStrings): ExploreUiState = initialExploreUiState(strings).copy(
    errorMessage = strings.configurationUnavailable,
)

private fun ExploreEffect.toIosEffect(): IosExploreEffect = when (this) {
    ExploreEffect.AuthenticationRequired -> IosExploreEffect.RequireAuthentication
    ExploreEffect.RequestLocation -> IosExploreEffect.RequestLocation
}
