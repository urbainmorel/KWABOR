package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.isWithinBeninBounds
import com.kwabor.shared.domain.catalog.nearestCity
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ExploreIntent {
    sealed interface Feed : ExploreIntent

    sealed interface Location : ExploreIntent

    sealed interface ViewerProtected : ExploreIntent

    data class SelectTab(val tab: ExploreTab) : Feed

    data class SelectChip(val chipId: String) : Feed

    data object Retry : Feed

    data object Refresh : Feed

    data object LoadNext : Feed

    data class SelectCity(val cityId: String) : Location

    data class ToggleLike(val listingId: String) : ViewerProtected

    data class ToggleFavorite(val listingId: String) : ViewerProtected

    data object OpenCitySelector : Location

    data object CloseCitySelector : Location

    data object RequestLocation : Location

    data class LocationCoordinates(
        val latitude: Double,
        val longitude: Double,
    ) : Location

    data object LocationPermissionDenied : Location

    data object LocationDisabled : Location

    data object LocationUnavailable : Location

    data object ReplayPendingInteraction : ViewerProtected

    data object ClearPendingAuthentication : ViewerProtected

    data class ViewerContextChanged(val scope: ViewerSessionScope) : ExploreIntent

    data class FavoriteStateChanged(
        val listingId: String,
        val favorited: Boolean,
        val scope: ViewerSessionScope,
    ) : ExploreIntent
}

sealed interface ExploreEffect {
    data class AuthenticationRequired(
        val kind: ExploreInteractionKind,
        val suggestedCityId: String?,
        val scope: ViewerSessionScope,
    ) : ExploreEffect

    data class ProtectedActionReplayed(
        val kind: ExploreInteractionKind,
        val listingId: String,
        val analyticsEvent: AnalyticsEvent?,
        val scope: ViewerSessionScope,
    ) : ExploreEffect

    data class FavoriteChanged(
        val listingId: String,
        val favorited: Boolean,
        val scope: ViewerSessionScope,
    ) : ExploreEffect

    data object RequestLocation : ExploreEffect
}

private data class QueuedExploreIntent(
    val intent: ExploreIntent,
    val sourceScope: ViewerSessionScope?,
)

class ExploreRuntime(
    private val presenter: ExplorePresenter,
    private val strings: KwaborStrings,
    coroutineScope: CoroutineScope,
) {
    private val runtimeJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(coroutineScope.coroutineContext + runtimeJob)
    private val stateStore = ExploreStateStore(
        loadingExploreUiState(strings = strings, request = ExploreLoadRequest()),
    )
    val state: StateFlow<ExploreUiState> = stateStore.state

    private val intentChannel = Channel<QueuedExploreIntent>(capacity = Channel.UNLIMITED)
    private val effectChannel = Channel<ExploreEffect>(capacity = Channel.BUFFERED)
    val effects: Flow<ExploreEffect> = effectChannel.receiveAsFlow()

    private val feedLifecycleMutex = Mutex()
    private var feedJob: Job? = null
    private var feedGeneration = 0L
    private var activeFeedRequest = ExploreLoadRequest()

    private val cityLifecycleMutex = Mutex()
    private val citySelectionMutex = Mutex()
    private var citySelectionJob: Job? = null
    private var cityGeneration = 0L

    private val locationCoordinator = ExploreLocationCoordinator(
        stateStore = stateStore,
        strings = strings,
        publishEffect = effectChannel::send,
        selectCity = ::selectCity,
    )

    private val viewerSessionCoordinator = ExploreViewerSessionCoordinator(
        presenter = presenter,
        strings = strings,
        coroutineScope = runtimeScope,
        stateStore = stateStore,
        callbacks = ExploreViewerSessionCallbacks(
            invalidateFeed = ::invalidateFeed,
            reloadFeed = ::load,
            publishEffect = effectChannel::send,
        ),
    )

    init {
        runtimeJob.invokeOnCompletion { effectChannel.close() }
        runtimeScope.launch { load(ExploreLoadRequest()) }
        runtimeScope.launch {
            for (queuedIntent in intentChannel) {
                handleIntent(queuedIntent)
            }
        }
    }

    fun dispatch(intent: ExploreIntent) {
        if (!runtimeJob.isActive) return
        if (intent is ExploreIntent.ViewerContextChanged && !stateStore.publishViewerScope(intent.scope)) {
            return
        }
        val sourceScope = if (intent is ExploreIntent.ViewerProtected) stateStore.value.viewerScope else null
        intentChannel.trySend(QueuedExploreIntent(intent = intent, sourceScope = sourceScope))
    }

    fun close() {
        intentChannel.close()
        viewerSessionCoordinator.close()
        runtimeJob.cancel()
    }

    private suspend fun handleIntent(queuedIntent: QueuedExploreIntent) {
        val intent = queuedIntent.intent
        when (intent) {
            is ExploreIntent.Feed -> handleFeedIntent(intent)
            is ExploreIntent.Location -> locationCoordinator.handle(intent)
            is ExploreIntent.ViewerProtected -> handleViewerProtectedIntent(
                coordinator = viewerSessionCoordinator,
                intent = intent,
                sourceScope = queuedIntent.sourceScope ?: return,
            )
            is ExploreIntent.ViewerContextChanged -> viewerSessionCoordinator.updateViewerContext(intent.scope)
            is ExploreIntent.FavoriteStateChanged -> viewerSessionCoordinator.applyFavoriteState(intent)
        }
    }

    private suspend fun handleFeedIntent(intent: ExploreIntent.Feed) {
        val current = stateStore.value
        val requested = activeFeedRequestSnapshot()
        when (intent) {
            is ExploreIntent.SelectTab -> if (intent.tab != requested.selectedTab) {
                viewerSessionCoordinator.invalidateFeedContext()
                load(
                    ExploreLoadRequest(
                        selectedTab = intent.tab,
                        selectedCityId = requested.selectedCityId ?: current.selectedCityId,
                    ),
                )
            }
            is ExploreIntent.SelectChip -> {
                viewerSessionCoordinator.invalidateFeedContext()
                load(
                    requested.copy(
                        selectedChipId = intent.chipId.takeUnless { chipId ->
                            chipId == requested.selectedChipId
                        },
                        selectedCityId = requested.selectedCityId ?: current.selectedCityId,
                    ),
                )
            }
            ExploreIntent.Retry -> load(current.toLoadRequest())
            ExploreIntent.Refresh -> refresh()
            ExploreIntent.LoadNext -> loadNext()
        }
    }

    private suspend fun load(request: ExploreLoadRequest) {
        feedLifecycleMutex.withLock {
            feedJob?.cancel()
            val generation = ++feedGeneration
            activeFeedRequest = request
            feedJob = runtimeScope.launch {
                val prepared = presenter.prepareInitialState(request, strings)
                updateStateForFeedGeneration(generation) { current ->
                    prepared.forNewRequest(current).copy(isLoading = true)
                } ?: return@launch

                val cacheBaseline = stateStore.feedBaseline()
                val cached = presenter.loadCached(cacheBaseline.state.copy(isLoading = false), strings)
                val hasCachedSnapshot = cached.feedSnapshot != null
                commitFeedForGeneration(
                    generation = generation,
                    result = cached.copy(
                        isLoading = !hasCachedSnapshot,
                        isRefreshing = hasCachedSnapshot,
                    ),
                    baselineInteractionRevisions = cacheBaseline.interactionRevisions,
                    baselineViewerScope = cacheBaseline.state.viewerScope,
                ) ?: return@launch

                val refreshBaseline = stateStore.feedBaseline()
                val refreshed = presenter.refresh(refreshBaseline.state, strings)
                commitFeedForGeneration(
                    generation = generation,
                    result = refreshed,
                    baselineInteractionRevisions = refreshBaseline.interactionRevisions,
                    baselineViewerScope = refreshBaseline.state.viewerScope,
                )
            }
        }
    }

    private suspend fun activeFeedRequestSnapshot(): ExploreLoadRequest = feedLifecycleMutex.withLock {
        activeFeedRequest
    }

    private suspend fun refresh() {
        startFeedOperation(
            canStart = { state -> !state.isLoading && !state.isRefreshing },
            markStarted = { state ->
                state.copy(
                    isRefreshing = true,
                    refreshMessage = null,
                    appendErrorMessage = null,
                )
            },
            operation = { state -> presenter.refresh(state, strings) },
        )
    }

    private suspend fun loadNext() {
        startFeedOperation(
            canStart = ExploreUiState::canAttemptAppend,
            markStarted = { state ->
                state.copy(
                    isAppending = true,
                    isOffline = state.contentIsOffline || state.queuedInteractions.isNotEmpty(),
                    appendErrorMessage = null,
                )
            },
            operation = { state -> presenter.append(state, strings) },
        )
    }

    private suspend fun startFeedOperation(
        canStart: (ExploreUiState) -> Boolean,
        markStarted: (ExploreUiState) -> ExploreUiState,
        operation: suspend (ExploreUiState) -> ExploreUiState,
    ) {
        feedLifecycleMutex.withLock {
            val operationBaseline = stateStore.prepareFeed(
                predicate = canStart,
                transform = markStarted,
            ) ?: return
            feedJob?.cancel()
            val generation = ++feedGeneration
            activeFeedRequest = operationBaseline.state.toLoadRequest()
            feedJob = runtimeScope.launch {
                val result = operation(operationBaseline.state)
                commitFeedForGeneration(
                    generation = generation,
                    result = result,
                    baselineInteractionRevisions = operationBaseline.interactionRevisions,
                    baselineViewerScope = operationBaseline.state.viewerScope,
                )
            }
        }
    }

    private suspend fun invalidateFeed() {
        feedLifecycleMutex.withLock {
            feedGeneration += 1
            feedJob?.cancel()
            feedJob = null
            activeFeedRequest = stateStore.value.toLoadRequest()
        }
    }

    private suspend fun updateStateForFeedGeneration(
        generation: Long,
        transform: (ExploreUiState) -> ExploreUiState,
    ): ExploreUiState? = feedLifecycleMutex.withLock {
        if (generation != feedGeneration) return@withLock null
        val updated = stateStore.update(transform) ?: return@withLock null
        activeFeedRequest = updated.toLoadRequest()
        updated
    }

    private suspend fun commitFeedForGeneration(
        generation: Long,
        result: ExploreUiState,
        baselineInteractionRevisions: Map<String, Long>,
        baselineViewerScope: ViewerSessionScope,
    ): ExploreUiState? = feedLifecycleMutex.withLock {
        if (generation != feedGeneration) return@withLock null
        val committed = stateStore.commitFeed(
            result = result,
            baselineInteractionRevisions = baselineInteractionRevisions,
            expectedScope = baselineViewerScope,
        ) ?: return@withLock null
        activeFeedRequest = committed.toLoadRequest()
        committed
    }

    private suspend fun selectCity(cityId: String) {
        if (cityId != stateStore.value.selectedCityId) {
            viewerSessionCoordinator.invalidateFeedContext()
        }
        cityLifecycleMutex.withLock {
            citySelectionJob?.cancel()
            val generation = ++cityGeneration
            citySelectionJob = runtimeScope.launch {
                citySelectionMutex.withLock {
                    val before = stateStore.snapshot()
                    val result = presenter.selectCity(before, cityId, strings).copy(isLocating = false)
                    val committed = commitCityForGeneration(generation, result) ?: return@withLock
                    if (committed.selectedCityId != before.selectedCityId) {
                        load(committed.toLoadRequest())
                    }
                }
            }
        }
    }

    private suspend fun commitCityForGeneration(generation: Long, result: ExploreUiState): ExploreUiState? =
        cityLifecycleMutex.withLock {
            if (generation != cityGeneration) return@withLock null
            stateStore.update { current -> current.mergeCityResult(result) }
        }
}

private suspend fun handleViewerProtectedIntent(
    coordinator: ExploreViewerSessionCoordinator,
    intent: ExploreIntent.ViewerProtected,
    sourceScope: ViewerSessionScope,
) {
    when (intent) {
        is ExploreIntent.ToggleLike -> coordinator.toggle(
            intent.listingId,
            ExploreInteractionKind.Like,
            sourceScope,
        )
        is ExploreIntent.ToggleFavorite -> coordinator.toggle(
            intent.listingId,
            ExploreInteractionKind.Favorite,
            sourceScope,
        )
        ExploreIntent.ReplayPendingInteraction -> coordinator.replayPendingInteraction(sourceScope)
        ExploreIntent.ClearPendingAuthentication -> coordinator.pendingAuthentication.clear(sourceScope)
    }
}

private class ExploreLocationCoordinator(
    private val stateStore: ExploreStateStore,
    private val strings: KwaborStrings,
    private val publishEffect: suspend (ExploreEffect) -> Unit,
    private val selectCity: suspend (String) -> Unit,
) {
    suspend fun handle(intent: ExploreIntent.Location) {
        when (intent) {
            is ExploreIntent.SelectCity -> selectCity(intent.cityId)
            ExploreIntent.OpenCitySelector -> setCitySelector(open = true)
            ExploreIntent.CloseCitySelector -> setCitySelector(open = false)
            ExploreIntent.RequestLocation -> requestLocation()
            is ExploreIntent.LocationCoordinates -> selectNearestCity(intent.latitude, intent.longitude)
            ExploreIntent.LocationPermissionDenied -> setLocationFailure(strings.exploreLocationPermissionDenied)
            ExploreIntent.LocationDisabled -> setLocationFailure(strings.exploreLocationDisabled)
            ExploreIntent.LocationUnavailable -> setLocationFailure(strings.exploreLocationUnavailable)
        }
    }

    private suspend fun requestLocation() {
        val locatingState = stateStore.updateIf(
            predicate = { current -> !current.isLocating },
            transform = { current -> current.copy(isLocating = true, locationMessage = null) },
        ) ?: return
        if (locatingState.isLocating) {
            publishEffect(ExploreEffect.RequestLocation)
        }
    }

    private suspend fun selectNearestCity(latitude: Double, longitude: Double) {
        if (!stateStore.value.isLocating) return
        if (!latitude.isFinite() || !longitude.isFinite()) {
            setLocationFailure(strings.exploreLocationUnavailable)
            return
        }
        val location = GeoPoint(latitude = latitude, longitude = longitude)
        if (!location.isWithinBeninBounds) {
            setLocationFailure(strings.exploreLocationOutsideBenin)
            return
        }
        val city = nearestCity(
            cities = stateStore.snapshot().feedSnapshot?.cities.orEmpty(),
            location = location,
        )
        if (city == null) {
            setLocationFailure(strings.exploreLocationUnavailable)
            return
        }
        selectCity(city.id)
    }

    private suspend fun setLocationFailure(message: String) {
        stateStore.updateIf(
            predicate = ExploreUiState::isLocating,
            transform = { current -> current.copy(isLocating = false, locationMessage = message) },
        )
    }

    private suspend fun setCitySelector(open: Boolean) {
        stateStore.update { current ->
            current.copy(
                isCitySelectorOpen = open,
                isLocating = if (open) current.isLocating else false,
                locationMessage = null,
            )
        }
    }
}

private sealed interface ExploreViewerContext {
    data object Uninitialized : ExploreViewerContext

    data class Guest(val scope: ViewerSessionScope) : ExploreViewerContext

    data class Authenticated(val scope: ViewerSessionScope) : ExploreViewerContext

    val sessionScope: ViewerSessionScope?
        get() = when (this) {
            Uninitialized -> null
            is Guest -> scope
            is Authenticated -> scope
        }

    companion object {
        fun fromScope(scope: ViewerSessionScope): ExploreViewerContext =
            if (scope.isAuthenticated) Authenticated(scope) else Guest(scope)
    }
}

private data class ExploreViewerSessionCallbacks(
    val invalidateFeed: suspend () -> Unit,
    val reloadFeed: suspend (ExploreLoadRequest) -> Unit,
    val publishEffect: suspend (ExploreEffect) -> Unit,
)

private class ExploreViewerSessionCoordinator(
    private val presenter: ExplorePresenter,
    private val strings: KwaborStrings,
    private val coroutineScope: CoroutineScope,
    private val stateStore: ExploreStateStore,
    private val callbacks: ExploreViewerSessionCallbacks,
) {
    private val interactionMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private var viewerContext: ExploreViewerContext = ExploreViewerContext.Uninitialized
    private val scopeValidator = ExploreViewerScopeValidator(
        lifecycleMutex = lifecycleMutex,
        stateStore = stateStore,
        currentContext = { viewerContext },
    )
    private val effectPublisher = ExploreInteractionEffectPublisher(callbacks)
    val pendingAuthentication = ExplorePendingAuthenticationCoordinator(
        lifecycleMutex = lifecycleMutex,
        scopeValidator = scopeValidator,
        stateStore = stateStore,
    )
    private var interactionSupervisor = SupervisorJob(coroutineScope.coroutineContext[Job])
    private var interactionScope = CoroutineScope(coroutineScope.coroutineContext + interactionSupervisor)
    private var viewerContextJob: Job? = null
    private var viewerGeneration = 0L
    private var interactionContextGeneration = 0L
    private var claimedPendingReplay: PendingExploreAuthInteraction? = null

    suspend fun toggle(
        listingId: String,
        kind: ExploreInteractionKind,
        sourceScope: ViewerSessionScope,
        replay: PendingExploreAuthInteraction? = null,
    ) {
        val launchContext = lifecycleMutex.withLock {
            if (!scopeValidator.isCurrentSourceScope(sourceScope)) return@withLock null
            ExploreInteractionLaunchContext(
                scope = interactionScope,
                viewerScope = sourceScope,
                viewerGeneration = viewerGeneration,
                interactionContextGeneration = interactionContextGeneration,
            )
        } ?: return
        launchContext.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                performToggle(
                    ExploreToggleRequest(
                        listingId = listingId,
                        kind = kind,
                        viewerScopeAtRequest = launchContext.viewerScope,
                        viewerAtRequest = launchContext.viewerGeneration,
                        contextAtRequest = launchContext.interactionContextGeneration,
                        replay = replay,
                    ),
                )
            } finally {
                replay?.let { pending -> releasePendingReplay(pending) }
            }
        }
    }

    suspend fun invalidateFeedContext() {
        lifecycleMutex.withLock { interactionContextGeneration += 1 }
    }

    suspend fun replayPendingInteraction(sourceScope: ViewerSessionScope) {
        if (!scopeValidator.isCurrentSourceScopeLocked(sourceScope)) return
        val current = stateStore.value
        val pending = current.pendingAuthInteraction ?: return
        if (current.listings.none { listing -> listing.id == pending.listingId }) {
            stateStore.updateIf(
                predicate = { latest ->
                    latest.viewerScope == sourceScope && latest.pendingAuthInteraction == pending
                },
                transform = { latest ->
                    latest.copy(pendingAuthInteraction = null, interactionMessage = null)
                },
            )
            return
        }
        if (!claimPendingReplay(pending, sourceScope)) return
        toggle(
            listingId = pending.listingId,
            kind = pending.kind,
            sourceScope = sourceScope,
            replay = pending,
        )
    }

    suspend fun updateViewerContext(scope: ViewerSessionScope) {
        val nextContext = ExploreViewerContext.fromScope(scope)
        val transition = lifecycleMutex.withLock {
            val previousContext = viewerContext
            if (nextContext == previousContext) {
                return@withLock ExploreViewerTransition.Unchanged
            }
            val previousScope = previousContext.sessionScope
            if (previousScope != null && scope.epoch <= previousScope.epoch) {
                return@withLock ExploreViewerTransition.Ignored
            }

            viewerContext = nextContext
            viewerGeneration += 1
            interactionContextGeneration += 1
            claimedPendingReplay = null
            resetInteractionScope()
            viewerContextJob?.cancel()
            ExploreViewerTransition.Changed(
                previous = previousContext,
                next = nextContext,
                viewerGeneration = viewerGeneration,
                interactionContextGeneration = interactionContextGeneration,
            )
        }
        when (transition) {
            ExploreViewerTransition.Ignored -> Unit
            ExploreViewerTransition.Unchanged -> Unit
            is ExploreViewerTransition.Changed -> if (
                transition.previous != ExploreViewerContext.Uninitialized ||
                transition.next is ExploreViewerContext.Authenticated
            ) {
                startViewerTransition(transition)
            }
        }
    }

    suspend fun applyFavoriteState(intent: ExploreIntent.FavoriteStateChanged) {
        val launch = lifecycleMutex.withLock {
            if (viewerContext.sessionScope != intent.scope || viewerContext !is ExploreViewerContext.Authenticated) {
                return@withLock null
            }
            ExploreExternalFavoriteLaunch(
                scope = intent.scope,
                viewerGeneration = viewerGeneration,
                interactionContextGeneration = interactionContextGeneration,
            )
        } ?: return
        stateStore.applyFavoriteState(
            listingId = intent.listingId.trim(),
            favorited = intent.favorited,
            expectedScope = launch.scope,
            canCommit = {
                isCurrentInteraction(launch.viewerGeneration, launch.interactionContextGeneration) &&
                    scopeValidator.currentViewerScope() == launch.scope
            },
        )
    }

    fun close() {
        viewerContextJob?.cancel()
        interactionSupervisor.cancel()
    }

    private suspend fun startViewerTransition(transition: ExploreViewerTransition.Changed) {
        val pendingCandidate = stateStore.value.pendingAuthInteraction.takeIf {
            transition.previous is ExploreViewerContext.Guest &&
                transition.next is ExploreViewerContext.Authenticated
        }
        val targetScope = transition.next.sessionScope ?: return
        val pendingToReplay = if (
            pendingCandidate != null && claimPendingReplay(pendingCandidate, targetScope)
        ) {
            pendingCandidate
        } else {
            null
        }
        callbacks.invalidateFeed()
        val job = createViewerTransitionJob(transition, pendingToReplay)
        installViewerTransitionJob(transition, job)
    }

    private fun createViewerTransitionJob(
        transition: ExploreViewerTransition.Changed,
        pendingToReplay: PendingExploreAuthInteraction?,
    ): Job = coroutineScope.launch(start = CoroutineStart.LAZY) {
        try {
            val resetState = stateStore.resetViewerState(
                expectedScope = transition.next.sessionScope ?: return@launch,
                canReset = {
                    isCurrentInteraction(
                        transition.viewerGeneration,
                        transition.interactionContextGeneration,
                    )
                },
            ) ?: return@launch
            pendingToReplay
                ?.takeIf { pending -> resetState.listings.any { listing -> listing.id == pending.listingId } }
                ?.let { pending ->
                    performToggle(
                        ExploreToggleRequest(
                            listingId = pending.listingId,
                            kind = pending.kind,
                            viewerScopeAtRequest = transition.next.sessionScope,
                            viewerAtRequest = transition.viewerGeneration,
                            contextAtRequest = transition.interactionContextGeneration,
                            replay = pending,
                        ),
                    )
                }
            if (!isCurrentInteraction(transition.viewerGeneration, transition.interactionContextGeneration)) {
                return@launch
            }
            callbacks.reloadFeed(stateStore.value.toLoadRequest())
        } finally {
            pendingToReplay?.let { pending -> releasePendingReplay(pending) }
        }
    }

    private suspend fun installViewerTransitionJob(transition: ExploreViewerTransition.Changed, job: Job) {
        lifecycleMutex.withLock {
            if (
                transition.viewerGeneration == viewerGeneration &&
                transition.interactionContextGeneration == interactionContextGeneration
            ) {
                viewerContextJob = job
                job.start()
            } else {
                job.cancel()
            }
        }
    }

    private suspend fun performToggle(request: ExploreToggleRequest) {
        interactionMutex.withLock {
            val prepared = prepareExploreToggle(
                request = request,
                stateStore = stateStore,
                scopeValidator = scopeValidator,
                isCurrentInteraction = ::isCurrentInteraction,
            ) ?: return@withLock
            val baseline = prepared.baseline
            val before = baseline.state
            val result = toggleListing(presenter, strings, before, request.listingId, request.kind)
            val completedReplay = successfulReplay(
                request.replay,
                before,
                result,
                request.listingId,
                request.kind,
            )
            val committed = stateStore.commitInteraction(
                ExploreInteractionCommitRequest(
                    result = result,
                    baseline = before,
                    listingId = request.listingId,
                    kind = request.kind,
                    baselineKindRevision = baseline.kindRevision,
                    canCommit = {
                        isCurrentInteraction(request.viewerAtRequest, request.contextAtRequest) &&
                            stateStore.value.listings.any { listing -> listing.id == request.listingId }
                    },
                ),
            ) ?: return@withLock
            val effectContext = ExploreInteractionEffectContext(prepared, request, result, committed)
            effectPublisher.publish(effectContext, completedReplay)
        }
    }

    private suspend fun claimPendingReplay(
        pending: PendingExploreAuthInteraction,
        sourceScope: ViewerSessionScope,
    ): Boolean = lifecycleMutex.withLock {
        if (!scopeValidator.isCurrentSourceScope(sourceScope) || claimedPendingReplay != null) {
            false
        } else {
            claimedPendingReplay = pending
            true
        }
    }

    private suspend fun releasePendingReplay(pending: PendingExploreAuthInteraction) {
        lifecycleMutex.withLock {
            if (claimedPendingReplay == pending) {
                claimedPendingReplay = null
            }
        }
    }

    private suspend fun isCurrentInteraction(viewerAtRequest: Long, contextAtRequest: Long): Boolean =
        lifecycleMutex.withLock {
            viewerAtRequest == viewerGeneration && contextAtRequest == interactionContextGeneration
        }

    private fun resetInteractionScope() {
        interactionSupervisor.cancel()
        interactionSupervisor = SupervisorJob(coroutineScope.coroutineContext[Job])
        interactionScope = CoroutineScope(coroutineScope.coroutineContext + interactionSupervisor)
    }
}

private class ExploreViewerScopeValidator(
    private val lifecycleMutex: Mutex,
    private val stateStore: ExploreStateStore,
    private val currentContext: () -> ExploreViewerContext,
) {
    suspend fun currentViewerScope(): ViewerSessionScope? = lifecycleMutex.withLock { currentContext().sessionScope }

    suspend fun isCurrentSourceScopeLocked(sourceScope: ViewerSessionScope): Boolean =
        lifecycleMutex.withLock { isCurrentSourceScope(sourceScope) }

    fun isCurrentSourceScope(sourceScope: ViewerSessionScope): Boolean =
        (currentContext().sessionScope ?: ViewerSessionScope.InitialGuest) == sourceScope &&
            stateStore.value.viewerScope == sourceScope
}

private class ExplorePendingAuthenticationCoordinator(
    private val lifecycleMutex: Mutex,
    private val scopeValidator: ExploreViewerScopeValidator,
    private val stateStore: ExploreStateStore,
) {
    suspend fun clear(sourceScope: ViewerSessionScope) {
        val canClear = lifecycleMutex.withLock {
            scopeValidator.isCurrentSourceScope(sourceScope) && !sourceScope.isAuthenticated
        }
        if (!canClear) return
        stateStore.updateIf(
            predicate = { current -> current.viewerScope == sourceScope },
            transform = { current ->
                current.copy(pendingAuthInteraction = null, interactionMessage = null)
            },
        )
    }
}

private class ExploreInteractionEffectPublisher(
    private val callbacks: ExploreViewerSessionCallbacks,
) {
    suspend fun publish(context: ExploreInteractionEffectContext, completedReplay: PendingExploreAuthInteraction?) {
        publishAuthenticationEffect(context)
        publishReplayEffect(completedReplay, context.before, context.scope)
        publishFavoriteChangedEffect(context)
    }

    private suspend fun publishAuthenticationEffect(context: ExploreInteractionEffectContext) {
        val attemptedPending = context.result.pendingAuthInteraction?.takeIf { pending ->
            pending.matches(context.listingId, context.kind)
        } ?: return
        if (attemptedPending == context.before.pendingAuthInteraction) return
        val pending = context.committed.pendingAuthInteraction?.takeIf { candidate ->
            candidate.matches(context.listingId, context.kind)
        } ?: return
        callbacks.publishEffect(
            ExploreEffect.AuthenticationRequired(
                kind = pending.kind,
                suggestedCityId = pending.suggestedCityId,
                scope = context.scope,
            ),
        )
    }

    private suspend fun publishReplayEffect(
        completedReplay: PendingExploreAuthInteraction?,
        sourceState: ExploreUiState,
        scope: ViewerSessionScope,
    ) {
        completedReplay ?: return
        callbacks.publishEffect(
            ExploreEffect.ProtectedActionReplayed(
                kind = completedReplay.kind,
                listingId = completedReplay.listingId,
                analyticsEvent = sourceState.protectedActionReplayedAnalyticsEvent(completedReplay.listingId),
                scope = scope,
            ),
        )
    }

    private suspend fun publishFavoriteChangedEffect(context: ExploreInteractionEffectContext) {
        if (!context.scope.isAuthenticated) return
        if (context.kind != ExploreInteractionKind.Favorite) return
        if (context.result.isOffline || context.result.pendingAuthInteraction != null) return
        val hasQueuedFavorite = context.result.queuedInteractions.any { queued ->
            queued.listingId == context.listingId && queued.kind == ExploreInteractionKind.Favorite
        }
        if (hasQueuedFavorite) return
        if (!context.result.hasInteractionChangeComparedTo(context.before, context.listingId, context.kind)) return
        val favorited = context.committed.listings
            .firstOrNull { listing -> listing.id == context.listingId }
            ?.favorited
            ?: return
        callbacks.publishEffect(
            ExploreEffect.FavoriteChanged(
                listingId = context.listingId,
                favorited = favorited,
                scope = context.scope,
            ),
        )
    }
}

private suspend fun prepareExploreToggle(
    request: ExploreToggleRequest,
    stateStore: ExploreStateStore,
    scopeValidator: ExploreViewerScopeValidator,
    isCurrentInteraction: suspend (Long, Long) -> Boolean,
): ExplorePreparedToggle? {
    val expectedScope = request.viewerScopeAtRequest ?: ViewerSessionScope.InitialGuest
    val launchIsCurrent = scopeValidator.isCurrentSourceScopeLocked(expectedScope) &&
        isCurrentInteraction(request.viewerAtRequest, request.contextAtRequest)
    if (!launchIsCurrent) return null
    val baseline = stateStore.interactionBaseline(request.listingId, request.kind)
    val baselineIsCurrent = baseline.state.viewerScope == expectedScope &&
        scopeValidator.isCurrentSourceScopeLocked(expectedScope)
    val listingIsVisible = baseline.state.listings.any { listing -> listing.id == request.listingId }
    if (!baselineIsCurrent || !listingIsVisible) return null
    return ExplorePreparedToggle(expectedScope = expectedScope, baseline = baseline)
}

private suspend fun toggleListing(
    presenter: ExplorePresenter,
    strings: KwaborStrings,
    state: ExploreUiState,
    listingId: String,
    kind: ExploreInteractionKind,
): ExploreUiState = when (kind) {
    ExploreInteractionKind.Like -> presenter.toggleLike(state, listingId, strings)
    ExploreInteractionKind.Favorite -> presenter.toggleFavorite(state, listingId, strings)
}

private fun successfulReplay(
    replay: PendingExploreAuthInteraction?,
    before: ExploreUiState,
    result: ExploreUiState,
    listingId: String,
    kind: ExploreInteractionKind,
): PendingExploreAuthInteraction? = replay?.takeIf {
    result.pendingAuthInteraction == null &&
        !result.isOffline &&
        result.queuedInteractions.none { queued -> queued.listingId == listingId && queued.kind == kind } &&
        result.hasInteractionChangeComparedTo(before, listingId, kind)
}

private data class ExploreInteractionEffectContext(
    val before: ExploreUiState,
    val result: ExploreUiState,
    val committed: ExploreUiState,
    val listingId: String,
    val kind: ExploreInteractionKind,
    val scope: ViewerSessionScope,
) {
    constructor(
        prepared: ExplorePreparedToggle,
        request: ExploreToggleRequest,
        result: ExploreUiState,
        committed: ExploreUiState,
    ) : this(
        before = prepared.baseline.state,
        result = result,
        committed = committed,
        listingId = request.listingId,
        kind = request.kind,
        scope = prepared.expectedScope,
    )
}

private sealed interface ExploreViewerTransition {
    data object Ignored : ExploreViewerTransition

    data object Unchanged : ExploreViewerTransition

    data class Changed(
        val previous: ExploreViewerContext,
        val next: ExploreViewerContext,
        val viewerGeneration: Long,
        val interactionContextGeneration: Long,
    ) : ExploreViewerTransition
}

private data class ExploreInteractionLaunchContext(
    val scope: CoroutineScope,
    val viewerScope: ViewerSessionScope?,
    val viewerGeneration: Long,
    val interactionContextGeneration: Long,
)

private data class ExploreToggleRequest(
    val listingId: String,
    val kind: ExploreInteractionKind,
    val viewerScopeAtRequest: ViewerSessionScope?,
    val viewerAtRequest: Long,
    val contextAtRequest: Long,
    val replay: PendingExploreAuthInteraction?,
)

private data class ExplorePreparedToggle(
    val expectedScope: ViewerSessionScope,
    val baseline: ExploreInteractionBaseline,
)

private data class ExploreExternalFavoriteLaunch(
    val scope: ViewerSessionScope,
    val viewerGeneration: Long,
    val interactionContextGeneration: Long,
)

private data class ExploreInteractionBaseline(
    val state: ExploreUiState,
    val kindRevision: Long,
)

private data class ExploreInteractionRevisionKey(
    val listingId: String,
    val kind: ExploreInteractionKind,
)

private class ExploreStateStore(initialState: ExploreUiState) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(initialState)
    private var interactionRevision = 0L
    private val interactionRevisionsByListingId = mutableMapOf<String, Long>()
    private val interactionKindRevisions = mutableMapOf<ExploreInteractionRevisionKey, Long>()
    private val interactionOverridesByListingId = mutableMapOf<String, ExploreListingItem>()
    private var interactionDataScope = initialState.viewerScope
    val state: StateFlow<ExploreUiState> = mutableState.asStateFlow()
    val value: ExploreUiState get() = mutableState.value

    fun publishViewerScope(scope: ViewerSessionScope): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.viewerScope == scope) return true
            if (scope.epoch <= current.viewerScope.epoch) return false
            val pendingGuestAction = current.pendingAuthInteraction.takeIf {
                !current.viewerScope.isAuthenticated && scope.isAuthenticated
            }
            val purged = current.withoutViewerState().copy(
                interactionMessage = current.interactionMessage.takeIf { pendingGuestAction != null },
                pendingAuthInteraction = pendingGuestAction,
                viewerScope = scope,
            )
            if (mutableState.compareAndSet(current, purged)) return true
        }
    }

    suspend fun snapshot(): ExploreUiState = mutex.withLock { mutableState.value }

    suspend fun interactionBaseline(listingId: String, kind: ExploreInteractionKind): ExploreInteractionBaseline =
        mutex.withLock {
            val key = ExploreInteractionRevisionKey(listingId, kind)
            val current = mutableState.value
            ExploreInteractionBaseline(
                state = current,
                kindRevision = if (interactionDataScope == current.viewerScope) {
                    interactionKindRevisions[key] ?: 0L
                } else {
                    0L
                },
            )
        }

    suspend fun update(transform: (ExploreUiState) -> ExploreUiState): ExploreUiState? = mutex.withLock {
        val current = mutableState.value
        val updated = transform(current).copy(viewerScope = current.viewerScope)
        updated.takeIf { mutableState.compareAndSet(current, it) }
    }

    suspend fun updateIf(
        predicate: (ExploreUiState) -> Boolean,
        transform: (ExploreUiState) -> ExploreUiState,
    ): ExploreUiState? = mutex.withLock {
        val current = mutableState.value
        if (!predicate(current)) return@withLock null
        val updated = transform(current).copy(viewerScope = current.viewerScope)
        updated.takeIf { mutableState.compareAndSet(current, it) }
    }

    suspend fun resetViewerState(expectedScope: ViewerSessionScope, canReset: suspend () -> Boolean): ExploreUiState? =
        mutex.withLock {
            if (!canReset()) return@withLock null
            val current = mutableState.value
            if (current.viewerScope != expectedScope) return@withLock null
            val updated = current.withoutViewerState()
            if (!mutableState.compareAndSet(current, updated)) return@withLock null
            interactionRevision = 0L
            interactionRevisionsByListingId.clear()
            interactionKindRevisions.clear()
            interactionOverridesByListingId.clear()
            interactionDataScope = expectedScope
            updated
        }

    suspend fun feedBaseline(): ExploreFeedBaseline = mutex.withLock {
        val current = mutableState.value
        ExploreFeedBaseline(
            state = current,
            interactionRevisions = interactionRevisionsFor(current.viewerScope),
        )
    }

    suspend fun prepareFeed(
        predicate: (ExploreUiState) -> Boolean,
        transform: (ExploreUiState) -> ExploreUiState,
    ): ExploreFeedBaseline? = mutex.withLock {
        val current = mutableState.value
        if (!predicate(current)) return@withLock null
        val updated = transform(current).copy(viewerScope = current.viewerScope)
        if (!mutableState.compareAndSet(current, updated)) return@withLock null
        ExploreFeedBaseline(
            state = updated,
            interactionRevisions = interactionRevisionsFor(updated.viewerScope),
        )
    }

    suspend fun commitInteraction(request: ExploreInteractionCommitRequest): ExploreUiState? = mutex.withLock {
        if (!request.canCommit()) return@withLock null
        val current = mutableState.value
        if (current.viewerScope != request.baseline.viewerScope) return@withLock null
        val revisionKey = ExploreInteractionRevisionKey(request.listingId, request.kind)
        val currentKindRevision = if (interactionDataScope == current.viewerScope) {
            interactionKindRevisions[revisionKey] ?: 0L
        } else {
            0L
        }
        if (currentKindRevision != request.baselineKindRevision) return@withLock null
        val updated = current.mergeInteractionResult(
            result = request.result,
            baseline = request.baseline,
            listingId = request.listingId,
            kind = request.kind,
        )
        val hasInteractionChange = request.result.hasInteractionChangeComparedTo(
            request.baseline,
            request.listingId,
            request.kind,
        )
        val updatedListing = updated.listings.firstOrNull { listing -> listing.id == request.listingId }
        if (!mutableState.compareAndSet(current, updated)) return@withLock null
        prepareInteractionDataFor(current.viewerScope)
        if (hasInteractionChange) {
            interactionRevisionsByListingId[request.listingId] = ++interactionRevision
            updatedListing?.let { listing ->
                interactionOverridesByListingId[request.listingId] = listing
            }
        }
        interactionKindRevisions[revisionKey] = request.baselineKindRevision + 1L
        updated
    }

    suspend fun commitFeed(
        result: ExploreUiState,
        baselineInteractionRevisions: Map<String, Long>,
        expectedScope: ViewerSessionScope,
    ): ExploreUiState? = mutex.withLock {
        val current = mutableState.value
        if (current.viewerScope != expectedScope || result.viewerScope != expectedScope) return@withLock null
        val scopedRevisions = interactionRevisionsFor(expectedScope)
        val changedInteractionIds = scopedRevisions.changedSince(baselineInteractionRevisions)
        result.mergeFeedRuntime(
            current = current,
            changedInteractionIds = changedInteractionIds,
            interactionOverridesByListingId = interactionOverridesFor(expectedScope),
        ).takeIf { updated -> mutableState.compareAndSet(current, updated) }
    }

    suspend fun applyFavoriteState(
        listingId: String,
        favorited: Boolean,
        expectedScope: ViewerSessionScope,
        canCommit: suspend () -> Boolean,
    ): ExploreUiState? = mutex.withLock {
        if (!canCommit()) return@withLock null
        val current = mutableState.value
        if (current.viewerScope != expectedScope) return@withLock null
        val update = current.applyExternalFavoriteUpdate(listingId, favorited) ?: return@withLock null
        val revisionKey = ExploreInteractionRevisionKey(listingId, ExploreInteractionKind.Favorite)
        val nextKindRevision = if (interactionDataScope == expectedScope) {
            (interactionKindRevisions[revisionKey] ?: 0L) + 1L
        } else {
            1L
        }
        if (!mutableState.compareAndSet(current, update.state)) return@withLock null
        prepareInteractionDataFor(expectedScope)
        interactionKindRevisions[revisionKey] = nextKindRevision
        interactionRevisionsByListingId[listingId] = ++interactionRevision
        interactionOverridesByListingId[listingId] = update.listing
        update.state
    }

    private fun interactionRevisionsFor(scope: ViewerSessionScope): Map<String, Long> =
        if (interactionDataScope == scope) interactionRevisionsByListingId.toMap() else emptyMap()

    private fun interactionOverridesFor(scope: ViewerSessionScope): Map<String, ExploreListingItem> =
        if (interactionDataScope == scope) interactionOverridesByListingId else emptyMap()

    private fun prepareInteractionDataFor(scope: ViewerSessionScope) {
        if (interactionDataScope == scope) return
        interactionRevision = 0L
        interactionRevisionsByListingId.clear()
        interactionKindRevisions.clear()
        interactionOverridesByListingId.clear()
        interactionDataScope = scope
    }
}

private data class ExploreFeedBaseline(
    val state: ExploreUiState,
    val interactionRevisions: Map<String, Long>,
)

private data class ExploreInteractionCommitRequest(
    val result: ExploreUiState,
    val baseline: ExploreUiState,
    val listingId: String,
    val kind: ExploreInteractionKind,
    val baselineKindRevision: Long,
    val canCommit: suspend () -> Boolean,
)

private data class ExploreExternalFavoriteUpdate(
    val state: ExploreUiState,
    val listing: ExploreListingItem,
)

private fun ExploreUiState.toLoadRequest(): ExploreLoadRequest = ExploreLoadRequest(
    selectedTab = selectedTab,
    selectedChipId = selectedChipId,
    selectedCityId = selectedCityId,
)

private fun ExploreUiState.canAttemptAppend(): Boolean =
    nextCursor != null && !isLoading && !isRefreshing && !isAppending

private fun ExploreUiState.forNewRequest(current: ExploreUiState): ExploreUiState = copy(
    cityLabel = current.cityLabel,
    availableCities = current.availableCities,
    isLocalCacheUnavailable = isLocalCacheUnavailable || current.isLocalCacheUnavailable,
    isCitySelectorOpen = current.isCitySelectorOpen,
    isLocating = current.isLocating,
    locationMessage = current.locationMessage,
    interactionMessage = current.interactionMessage,
    pendingAuthInteraction = current.pendingAuthInteraction,
    queuedInteractions = current.queuedInteractions,
    isOffline = current.contentIsOffline || current.queuedInteractions.isNotEmpty(),
    contentIsOffline = current.contentIsOffline,
)

private fun ExploreUiState.mergeFeedRuntime(
    current: ExploreUiState,
    changedInteractionIds: Set<String>,
    interactionOverridesByListingId: Map<String, ExploreListingItem>,
): ExploreUiState {
    val currentListingsById = current.listings.associateBy { listing -> listing.id }
    val visiblePendingInteraction = current.pendingAuthInteraction?.takeIf { pending ->
        listings.any { listing -> listing.id == pending.listingId }
    }
    return copy(
        listings = listings.map { incoming ->
            val visible = currentListingsById[incoming.id] ?: interactionOverridesByListingId[incoming.id]
            if (visible != null && incoming.id in changedInteractionIds) {
                incoming.copy(liked = visible.liked, favorited = visible.favorited, likesCount = visible.likesCount)
            } else {
                incoming
            }
        },
        isOffline = contentIsOffline || current.queuedInteractions.isNotEmpty(),
        isLocalCacheUnavailable = isLocalCacheUnavailable || current.isLocalCacheUnavailable,
        isCitySelectorOpen = current.isCitySelectorOpen,
        isLocating = current.isLocating,
        locationMessage = current.locationMessage,
        interactionMessage = if (current.pendingAuthInteraction != null && visiblePendingInteraction == null) {
            null
        } else {
            current.interactionMessage
        },
        pendingAuthInteraction = visiblePendingInteraction,
        queuedInteractions = current.queuedInteractions,
    )
}

private fun ExploreUiState.withoutViewerState(): ExploreUiState = copy(
    listings = listings.map { listing -> listing.copy(liked = false, favorited = false) },
    isLoading = true,
    isRefreshing = false,
    isAppending = false,
    refreshMessage = null,
    appendErrorMessage = null,
    feedSnapshot = null,
    interactionMessage = null,
    pendingAuthInteraction = null,
    queuedInteractions = emptyList(),
    isOffline = contentIsOffline,
)

private fun Map<String, Long>.changedSince(baseline: Map<String, Long>): Set<String> =
    keys.filterTo(mutableSetOf()) { listingId -> this[listingId] != baseline[listingId] }

private fun ExploreListingItem.hasDifferentInteractionThan(
    other: ExploreListingItem,
    kind: ExploreInteractionKind,
): Boolean = when (kind) {
    ExploreInteractionKind.Like -> liked != other.liked || likesCount != other.likesCount
    ExploreInteractionKind.Favorite -> favorited != other.favorited
}

private fun ExploreUiState.hasInteractionChangeComparedTo(
    baseline: ExploreUiState,
    listingId: String,
    kind: ExploreInteractionKind,
): Boolean {
    val resultListing = listings.firstOrNull { listing -> listing.id == listingId } ?: return false
    val baselineListing = baseline.listings.firstOrNull { listing -> listing.id == listingId } ?: return false
    return resultListing.hasDifferentInteractionThan(baselineListing, kind)
}

private fun ExploreUiState.applyExternalFavoriteUpdate(
    listingId: String,
    favorited: Boolean,
): ExploreExternalFavoriteUpdate? {
    val listing = listings.firstOrNull { item -> item.id == listingId } ?: return null
    val updatedListing = listing.copy(favorited = favorited)
    val hasQueuedFavorite = queuedInteractions.any { queued ->
        queued.listingId == listingId && queued.kind == ExploreInteractionKind.Favorite
    }
    val clearsTargetMessage = pendingAuthInteraction.matches(
        listingId,
        ExploreInteractionKind.Favorite,
    ) || hasQueuedFavorite
    val remainingQueuedInteractions = queuedInteractions.filterNot { queued ->
        queued.listingId == listingId && queued.kind == ExploreInteractionKind.Favorite
    }
    return ExploreExternalFavoriteUpdate(
        state = copy(
            listings = listings.map { item -> if (item.id == listingId) updatedListing else item },
            isOffline = contentIsOffline || remainingQueuedInteractions.isNotEmpty(),
            interactionMessage = interactionMessage.takeUnless { clearsTargetMessage },
            pendingAuthInteraction = pendingAuthInteraction?.takeUnless { pending ->
                pending.listingId == listingId && pending.kind == ExploreInteractionKind.Favorite
            },
            queuedInteractions = remainingQueuedInteractions,
        ),
        listing = updatedListing,
    )
}

private fun ExploreUiState.mergeInteractionResult(
    result: ExploreUiState,
    baseline: ExploreUiState,
    listingId: String,
    kind: ExploreInteractionKind,
): ExploreUiState {
    val mergedQueuedInteractions = queuedInteractions.mergeInteractionQueue(
        result = result.queuedInteractions,
        listingId = listingId,
        kind = kind,
    )
    return copy(
        listings = listings.mergeInteractionListing(result.listings, baseline.listings, listingId, kind),
        isOffline = contentIsOffline || mergedQueuedInteractions.isNotEmpty(),
        isLocalCacheUnavailable = isLocalCacheUnavailable || result.isLocalCacheUnavailable,
        interactionMessage = result.interactionMessage,
        pendingAuthInteraction = mergePendingInteraction(
            current = pendingAuthInteraction,
            result = result.pendingAuthInteraction,
            listingId = listingId,
            kind = kind,
        ),
        queuedInteractions = mergedQueuedInteractions,
    )
}

private fun List<ExploreListingItem>.mergeInteractionListing(
    result: List<ExploreListingItem>,
    baseline: List<ExploreListingItem>,
    listingId: String,
    kind: ExploreInteractionKind,
): List<ExploreListingItem> {
    val resultListing = result.firstOrNull { listing -> listing.id == listingId } ?: return this
    val baselineListing = baseline.firstOrNull { listing -> listing.id == listingId } ?: return this
    if (!resultListing.hasDifferentInteractionThan(baselineListing, kind)) return this
    return map { current ->
        if (current.id != listingId) return@map current
        when (kind) {
            ExploreInteractionKind.Like -> current.copy(
                liked = resultListing.liked,
                likesCount = resultListing.likesCount,
            )
            ExploreInteractionKind.Favorite -> current.copy(favorited = resultListing.favorited)
        }
    }
}

private fun PendingExploreAuthInteraction?.matches(listingId: String, kind: ExploreInteractionKind): Boolean =
    this?.listingId == listingId && this.kind == kind

private fun mergePendingInteraction(
    current: PendingExploreAuthInteraction?,
    result: PendingExploreAuthInteraction?,
    listingId: String,
    kind: ExploreInteractionKind,
): PendingExploreAuthInteraction? {
    val resultTarget = result.takeIf { pending -> pending.matches(listingId, kind) }
    return resultTarget ?: current?.takeUnless { pending -> pending.matches(listingId, kind) }
}

private fun List<QueuedExploreInteraction>.mergeInteractionQueue(
    result: List<QueuedExploreInteraction>,
    listingId: String,
    kind: ExploreInteractionKind,
): List<QueuedExploreInteraction> = filterNot { queued -> queued.listingId == listingId && queued.kind == kind } +
    result.filter { queued -> queued.listingId == listingId && queued.kind == kind }

private fun ExploreUiState.mergeCityResult(result: ExploreUiState): ExploreUiState = copy(
    cityLabel = result.cityLabel,
    selectedCityId = result.selectedCityId,
    isCitySelectorOpen = result.isCitySelectorOpen,
    isLocating = result.isLocating,
    locationMessage = result.locationMessage,
    isLocalCacheUnavailable = isLocalCacheUnavailable || result.isLocalCacheUnavailable,
)
