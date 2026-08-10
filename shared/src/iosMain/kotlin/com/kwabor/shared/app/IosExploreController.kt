package com.kwabor.shared.app

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExploreEffect
import com.kwabor.shared.presentation.explore.ExploreIntent
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.ExploreRuntime
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.initialExploreUiState
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class IosExploreEffectKind {
    RequireAuthentication,
    ProtectedActionReplayed,
    RequestLocation,
}

data class IosExploreEffect(
    val kind: IosExploreEffectKind,
    val replayAnalyticsEvent: AnalyticsEvent? = null,
    val scope: ViewerSessionScope? = null,
) {
    init {
        require(kind == IosExploreEffectKind.ProtectedActionReplayed || replayAnalyticsEvent == null) {
            "Only protected-action replay effects may carry analytics context."
        }
        require(
            when (kind) {
                IosExploreEffectKind.RequireAuthentication,
                IosExploreEffectKind.ProtectedActionReplayed,
                -> scope != null
                IosExploreEffectKind.RequestLocation -> scope == null
            },
        ) {
            "Authentication effects require a viewer scope; location effects must remain unscoped."
        }
    }

    val requiresAuthentication: Boolean
        get() = kind == IosExploreEffectKind.RequireAuthentication

    val requestsLocation: Boolean
        get() = kind == IosExploreEffectKind.RequestLocation

    val replaysProtectedAction: Boolean
        get() = kind == IosExploreEffectKind.ProtectedActionReplayed
}

class IosExploreFeedActions internal constructor(
    private val dispatch: (ExploreIntent) -> Unit,
) {
    fun selectPlacesTab() {
        selectTab(ExploreTab.Places)
    }

    fun selectEventsTab() {
        selectTab(ExploreTab.Events)
    }

    fun selectHotelsRestaurantsTab() {
        selectTab(ExploreTab.HotelsRestaurants)
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

    private fun selectTab(tab: ExploreTab) {
        dispatch(ExploreIntent.SelectTab(tab))
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

    fun updateViewerContext(scope: ViewerSessionScope) {
        dispatch(ExploreIntent.ViewerContextChanged(scope))
    }

    fun clearPendingAuthentication() {
        dispatch(ExploreIntent.ClearPendingAuthentication)
    }

    fun applyFavoriteState(
        listingId: String,
        favorited: Boolean,
        clientMutationSequence: Long,
        scope: ViewerSessionScope,
    ) {
        dispatch(
            ExploreIntent.FavoriteStateChanged(
                listingId = listingId,
                favorited = favorited,
                clientMutationSequence = clientMutationSequence,
                scope = scope,
            ),
        )
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

class IosExploreController internal constructor(
    runtimeProvider: (CoroutineScope, KwaborStrings, InteractionCoordinator?) -> IosExploreRuntime?,
    dispatcherProvider: DispatcherProvider,
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
    interactionCoordinator: InteractionCoordinator? = null,
) {
    internal constructor(
        presenter: ExplorePresenter?,
        dispatcherProvider: DispatcherProvider,
        viewerSessionScopeTracker: ViewerSessionScopeTracker,
        interactionCoordinator: InteractionCoordinator? = null,
    ) : this(
        runtimeProvider = { scope, strings, coordinator ->
            presenter?.let { currentPresenter ->
                DefaultIosExploreRuntime(
                    ExploreRuntime(
                        presenter = currentPresenter,
                        strings = strings,
                        coroutineScope = scope,
                        interactionCoordinator = coordinator,
                    ),
                )
            }
        },
        dispatcherProvider = dispatcherProvider,
        viewerSessionScopeTracker = viewerSessionScopeTracker,
        interactionCoordinator = interactionCoordinator,
    )

    internal constructor(
        runtime: IosExploreRuntime?,
        dispatcherProvider: DispatcherProvider,
        viewerSessionScopeTracker: ViewerSessionScopeTracker,
    ) : this(
        runtimeProvider = { _, _, _ -> runtime },
        dispatcherProvider = dispatcherProvider,
        viewerSessionScopeTracker = viewerSessionScopeTracker,
    )

    val strings: KwaborStrings = stringsFor(AppLocale.French)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private val runtime = runtimeProvider(scope, strings, interactionCoordinator)
    private var stateObserver: ((ExploreUiState) -> Unit)? = null
    private var effectObserver: ((IosExploreEffect) -> Unit)? = null
    private var favoriteObserver: ((String, Boolean, Long, ViewerSessionScope) -> Unit)? = null
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
                    if (updatedState.viewerScope == viewerSessionScopeTracker.currentScope) {
                        currentState = updatedState
                        publishStateIfNeeded(updatedState)
                    }
                }
            }
            scope.launch {
                currentRuntime.effects.collect { effect ->
                    when (effect) {
                        is ExploreEffect.AuthenticationRequired -> if (
                            effect.scope == viewerSessionScopeTracker.currentScope &&
                            publishLatestRuntimeState(currentRuntime, effect.scope)
                        ) {
                            effectObserver?.invoke(effect.toIosEffect())
                        }
                        is ExploreEffect.ProtectedActionReplayed -> if (
                            effect.scope == viewerSessionScopeTracker.currentScope &&
                            publishLatestRuntimeState(currentRuntime, effect.scope)
                        ) {
                            effectObserver?.invoke(effect.toIosEffect())
                        }
                        is ExploreEffect.FavoriteChanged -> if (
                            effect.scope == viewerSessionScopeTracker.currentScope
                        ) {
                            favoriteObserver?.invoke(
                                effect.listingId,
                                effect.favorited,
                                effect.clientMutationSequence,
                                effect.scope,
                            )
                        }
                        ExploreEffect.RequestLocation -> effectObserver?.invoke(
                            ExploreEffect.RequestLocation.toIosEffect(),
                        )
                    }
                }
            }
        }
    }

    fun observe(
        stateObserver: (ExploreUiState) -> Unit,
        effectObserver: (IosExploreEffect) -> Unit,
        favoriteObserver: (String, Boolean, Long, ViewerSessionScope) -> Unit,
    ) {
        if (isClosed) return
        observationVersion += 1
        this.stateObserver = stateObserver
        this.effectObserver = effectObserver
        this.favoriteObserver = favoriteObserver
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
        favoriteObserver = null
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
        if (isClosed) return
        val currentRuntime = runtime ?: return
        currentRuntime.dispatch(intent)
        if (intent is ExploreIntent.ViewerContextChanged) {
            publishLatestRuntimeState(currentRuntime, intent.scope)
        }
    }

    private fun publishStateIfNeeded(state: ExploreUiState) {
        val observer = stateObserver ?: return
        if (deliveredStateVersion == observationVersion && deliveredState == state) return
        deliveredStateVersion = observationVersion
        deliveredState = state
        observer(state)
    }

    private fun publishLatestRuntimeState(runtime: IosExploreRuntime, expectedScope: ViewerSessionScope): Boolean {
        if (expectedScope != viewerSessionScopeTracker.currentScope) return false
        val latestState = runtime.state.value
        if (latestState.viewerScope != expectedScope) return false
        currentState = latestState
        publishStateIfNeeded(latestState)
        return expectedScope == viewerSessionScopeTracker.currentScope
    }
}

private fun unavailableState(strings: KwaborStrings): ExploreUiState = initialExploreUiState(strings).copy(
    errorMessage = strings.configurationUnavailable,
)

private fun ExploreEffect.AuthenticationRequired.toIosEffect(): IosExploreEffect = IosExploreEffect(
    kind = IosExploreEffectKind.RequireAuthentication,
    scope = scope,
)

private fun ExploreEffect.ProtectedActionReplayed.toIosEffect(): IosExploreEffect = IosExploreEffect(
    kind = IosExploreEffectKind.ProtectedActionReplayed,
    replayAnalyticsEvent = analyticsEvent,
    scope = scope,
)

private fun ExploreEffect.RequestLocation.toIosEffect(): IosExploreEffect =
    IosExploreEffect(IosExploreEffectKind.RequestLocation)
