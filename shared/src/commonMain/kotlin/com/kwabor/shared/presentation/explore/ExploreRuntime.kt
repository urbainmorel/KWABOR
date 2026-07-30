package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.isWithinBeninBounds
import com.kwabor.shared.domain.catalog.nearestCity
import com.kwabor.shared.i18n.KwaborStrings
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

    data class SelectTab(val tab: ExploreTab) : Feed

    data class SelectChip(val chipId: String) : Feed

    data object Retry : Feed

    data object Refresh : Feed

    data object LoadNext : Feed

    data class SelectCity(val cityId: String) : Location

    data class ToggleLike(val listingId: String) : ExploreIntent

    data class ToggleFavorite(val listingId: String) : ExploreIntent

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

    data object ReplayPendingInteraction : ExploreIntent

    data class ViewerContextChanged(val viewerId: String?) : ExploreIntent
}

sealed interface ExploreEffect {
    data class AuthenticationRequired(
        val kind: ExploreInteractionKind,
        val suggestedCityId: String?,
    ) : ExploreEffect

    data class ProtectedActionReplayed(
        val kind: ExploreInteractionKind,
        val listingId: String,
    ) : ExploreEffect

    data object RequestLocation : ExploreEffect
}

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

    private val intentChannel = Channel<ExploreIntent>(capacity = Channel.UNLIMITED)
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
            for (intent in intentChannel) {
                handleIntent(intent)
            }
        }
    }

    fun dispatch(intent: ExploreIntent) {
        intentChannel.trySend(intent)
    }

    fun close() {
        intentChannel.close()
        viewerSessionCoordinator.close()
        runtimeJob.cancel()
    }

    private suspend fun handleIntent(intent: ExploreIntent) {
        when (intent) {
            is ExploreIntent.Feed -> handleFeedIntent(intent)
            is ExploreIntent.Location -> locationCoordinator.handle(intent)
            is ExploreIntent.ToggleLike -> viewerSessionCoordinator.toggle(
                intent.listingId,
                ExploreInteractionKind.Like,
            )
            is ExploreIntent.ToggleFavorite -> viewerSessionCoordinator.toggle(
                intent.listingId,
                ExploreInteractionKind.Favorite,
            )
            ExploreIntent.ReplayPendingInteraction -> viewerSessionCoordinator.replayPendingInteraction()
            is ExploreIntent.ViewerContextChanged -> viewerSessionCoordinator.updateViewerContext(intent.viewerId)
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
                ) ?: return@launch

                val refreshBaseline = stateStore.feedBaseline()
                val refreshed = presenter.refresh(refreshBaseline.state, strings)
                commitFeedForGeneration(
                    generation = generation,
                    result = refreshed,
                    baselineInteractionRevisions = refreshBaseline.interactionRevisions,
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
                    isOffline = false,
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
        stateStore.update(transform).also { updated -> activeFeedRequest = updated.toLoadRequest() }
    }

    private suspend fun commitFeedForGeneration(
        generation: Long,
        result: ExploreUiState,
        baselineInteractionRevisions: Map<String, Long>,
    ): ExploreUiState? = feedLifecycleMutex.withLock {
        if (generation != feedGeneration) return@withLock null
        stateStore.commitFeed(
            result = result,
            baselineInteractionRevisions = baselineInteractionRevisions,
        ).also { committed -> activeFeedRequest = committed.toLoadRequest() }
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

    data object Guest : ExploreViewerContext

    data class Authenticated(val userId: String) : ExploreViewerContext

    companion object {
        fun fromViewerId(viewerId: String?): ExploreViewerContext =
            viewerId?.takeUnless(String::isBlank)?.let(::Authenticated) ?: Guest
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
    private var interactionSupervisor = SupervisorJob(coroutineScope.coroutineContext[Job])
    private var interactionScope = CoroutineScope(coroutineScope.coroutineContext + interactionSupervisor)
    private var viewerContextJob: Job? = null
    private var viewerGeneration = 0L
    private var interactionContextGeneration = 0L
    private var claimedPendingReplay: PendingExploreAuthInteraction? = null

    suspend fun toggle(
        listingId: String,
        kind: ExploreInteractionKind,
        replay: PendingExploreAuthInteraction? = null,
    ) {
        val launchContext = lifecycleMutex.withLock {
            ExploreInteractionLaunchContext(
                scope = interactionScope,
                viewerGeneration = viewerGeneration,
                interactionContextGeneration = interactionContextGeneration,
            )
        }
        launchContext.scope.launch {
            try {
                performToggle(
                    listingId = listingId,
                    kind = kind,
                    viewerAtRequest = launchContext.viewerGeneration,
                    contextAtRequest = launchContext.interactionContextGeneration,
                    replay = replay,
                )
            } finally {
                replay?.let { pending -> releasePendingReplay(pending) }
            }
        }
    }

    suspend fun invalidateFeedContext() {
        lifecycleMutex.withLock { interactionContextGeneration += 1 }
    }

    suspend fun replayPendingInteraction() {
        val current = stateStore.value
        val pending = current.pendingAuthInteraction ?: return
        if (current.listings.none { listing -> listing.id == pending.listingId }) {
            stateStore.update { latest ->
                if (latest.pendingAuthInteraction == pending) {
                    latest.copy(pendingAuthInteraction = null, interactionMessage = null)
                } else {
                    latest
                }
            }
            return
        }
        if (!claimPendingReplay(pending)) return
        toggle(listingId = pending.listingId, kind = pending.kind, replay = pending)
    }

    suspend fun updateViewerContext(viewerId: String?) {
        val nextContext = ExploreViewerContext.fromViewerId(viewerId)
        val transition = lifecycleMutex.withLock {
            val previousContext = viewerContext
            if (nextContext == previousContext) {
                return@withLock ExploreViewerTransition.Unchanged(nextContext)
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
            is ExploreViewerTransition.Unchanged -> clearGuestPendingInteraction(transition.context)
            is ExploreViewerTransition.Changed -> if (transition.previous != ExploreViewerContext.Uninitialized) {
                startViewerTransition(transition)
            }
        }
    }

    fun close() {
        viewerContextJob?.cancel()
        interactionSupervisor.cancel()
    }

    private suspend fun clearGuestPendingInteraction(context: ExploreViewerContext) {
        if (context != ExploreViewerContext.Guest) return
        stateStore.update { current ->
            current.copy(pendingAuthInteraction = null, interactionMessage = null)
        }
    }

    private suspend fun startViewerTransition(transition: ExploreViewerTransition.Changed) {
        val pendingCandidate = stateStore.value.pendingAuthInteraction.takeIf {
            transition.previous == ExploreViewerContext.Guest &&
                transition.next is ExploreViewerContext.Authenticated
        }
        val pendingToReplay = if (pendingCandidate != null && claimPendingReplay(pendingCandidate)) {
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
                        listingId = pending.listingId,
                        kind = pending.kind,
                        viewerAtRequest = transition.viewerGeneration,
                        contextAtRequest = transition.interactionContextGeneration,
                        replay = pending,
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

    private suspend fun performToggle(
        listingId: String,
        kind: ExploreInteractionKind,
        viewerAtRequest: Long,
        contextAtRequest: Long,
        replay: PendingExploreAuthInteraction? = null,
    ) {
        interactionMutex.withLock {
            if (!isCurrentInteraction(viewerAtRequest, contextAtRequest)) return@withLock
            val before = stateStore.snapshot()
            if (before.listings.none { listing -> listing.id == listingId }) return@withLock
            val result = when (kind) {
                ExploreInteractionKind.Like -> presenter.toggleLike(before, listingId, strings)
                ExploreInteractionKind.Favorite -> presenter.toggleFavorite(before, listingId, strings)
            }
            val authenticationRequired = result.pendingAuthInteraction != null &&
                result.pendingAuthInteraction != before.pendingAuthInteraction
            val replaySucceeded = replay != null &&
                result.pendingAuthInteraction == null &&
                !result.isOffline &&
                result.queuedInteractions.none { queued ->
                    queued.listingId == listingId && queued.kind == kind
                } &&
                result.hasInteractionChangeComparedTo(before, listingId)
            val committed = stateStore.commitInteraction(
                result = result,
                baseline = before,
                listingId = listingId,
                canCommit = {
                    isCurrentInteraction(viewerAtRequest, contextAtRequest) &&
                        stateStore.value.listings.any { listing -> listing.id == listingId }
                },
            ) ?: return@withLock
            if (authenticationRequired) {
                committed.pendingAuthInteraction?.let { pending ->
                    callbacks.publishEffect(
                        ExploreEffect.AuthenticationRequired(
                            kind = pending.kind,
                            suggestedCityId = pending.suggestedCityId,
                        ),
                    )
                }
            }
            if (replaySucceeded && replay != null) {
                callbacks.publishEffect(
                    ExploreEffect.ProtectedActionReplayed(
                        kind = replay.kind,
                        listingId = replay.listingId,
                    ),
                )
            }
        }
    }

    private suspend fun claimPendingReplay(pending: PendingExploreAuthInteraction): Boolean =
        lifecycleMutex.withLock {
            if (claimedPendingReplay != null) {
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

private sealed interface ExploreViewerTransition {
    data class Unchanged(val context: ExploreViewerContext) : ExploreViewerTransition

    data class Changed(
        val previous: ExploreViewerContext,
        val next: ExploreViewerContext,
        val viewerGeneration: Long,
        val interactionContextGeneration: Long,
    ) : ExploreViewerTransition
}

private data class ExploreInteractionLaunchContext(
    val scope: CoroutineScope,
    val viewerGeneration: Long,
    val interactionContextGeneration: Long,
)

private class ExploreStateStore(initialState: ExploreUiState) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(initialState)
    private var interactionRevision = 0L
    private val interactionRevisionsByListingId = mutableMapOf<String, Long>()
    private val interactionOverridesByListingId = mutableMapOf<String, ExploreListingItem>()
    val state: StateFlow<ExploreUiState> = mutableState.asStateFlow()
    val value: ExploreUiState get() = mutableState.value

    suspend fun snapshot(): ExploreUiState = mutex.withLock { mutableState.value }

    suspend fun update(transform: (ExploreUiState) -> ExploreUiState): ExploreUiState = mutex.withLock {
        transform(mutableState.value).also { updated -> mutableState.value = updated }
    }

    suspend fun updateIf(
        predicate: (ExploreUiState) -> Boolean,
        transform: (ExploreUiState) -> ExploreUiState,
    ): ExploreUiState? = mutex.withLock {
        val current = mutableState.value
        if (!predicate(current)) return@withLock null
        transform(current).also { updated -> mutableState.value = updated }
    }

    suspend fun resetViewerState(canReset: suspend () -> Boolean): ExploreUiState? = mutex.withLock {
        if (!canReset()) return@withLock null
        interactionRevision = 0L
        interactionRevisionsByListingId.clear()
        interactionOverridesByListingId.clear()
        mutableState.value.withoutViewerState().also { updated -> mutableState.value = updated }
    }

    suspend fun feedBaseline(): ExploreFeedBaseline = mutex.withLock {
        ExploreFeedBaseline(
            state = mutableState.value,
            interactionRevisions = interactionRevisionsByListingId.toMap(),
        )
    }

    suspend fun prepareFeed(
        predicate: (ExploreUiState) -> Boolean,
        transform: (ExploreUiState) -> ExploreUiState,
    ): ExploreFeedBaseline? = mutex.withLock {
        val current = mutableState.value
        if (!predicate(current)) return@withLock null
        val updated = transform(current)
        mutableState.value = updated
        ExploreFeedBaseline(
            state = updated,
            interactionRevisions = interactionRevisionsByListingId.toMap(),
        )
    }

    suspend fun commitInteraction(
        result: ExploreUiState,
        baseline: ExploreUiState,
        listingId: String,
        canCommit: suspend () -> Boolean,
    ): ExploreUiState? = mutex.withLock {
        if (!canCommit()) return@withLock null
        if (result.hasInteractionChangeComparedTo(baseline, listingId)) {
            interactionRevisionsByListingId[listingId] = ++interactionRevision
            result.listings.firstOrNull { listing -> listing.id == listingId }?.let { listing ->
                interactionOverridesByListingId[listingId] = listing
            }
        }
        mutableState.value.mergeInteractionResult(result, baseline, listingId).also { updated ->
            mutableState.value = updated
        }
    }

    suspend fun commitFeed(result: ExploreUiState, baselineInteractionRevisions: Map<String, Long>): ExploreUiState =
        mutex.withLock {
            val changedInteractionIds = interactionRevisionsByListingId.changedSince(baselineInteractionRevisions)
            result.mergeFeedRuntime(
                current = mutableState.value,
                changedInteractionIds = changedInteractionIds,
                interactionOverridesByListingId = interactionOverridesByListingId,
            ).also { updated -> mutableState.value = updated }
        }
}

private data class ExploreFeedBaseline(
    val state: ExploreUiState,
    val interactionRevisions: Map<String, Long>,
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
        isOffline = isOffline || (current.isOffline && current.queuedInteractions.isNotEmpty()),
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
    isLoading = false,
    isRefreshing = false,
    isAppending = false,
    refreshMessage = null,
    appendErrorMessage = null,
    interactionMessage = null,
    pendingAuthInteraction = null,
    queuedInteractions = emptyList(),
)

private fun Map<String, Long>.changedSince(baseline: Map<String, Long>): Set<String> =
    keys.filterTo(mutableSetOf()) { listingId -> this[listingId] != baseline[listingId] }

private fun ExploreListingItem.hasDifferentInteractionThan(other: ExploreListingItem): Boolean =
    liked != other.liked || favorited != other.favorited || likesCount != other.likesCount

private fun ExploreUiState.hasInteractionChangeComparedTo(baseline: ExploreUiState, listingId: String): Boolean {
    val resultListing = listings.firstOrNull { listing -> listing.id == listingId } ?: return false
    val baselineListing = baseline.listings.firstOrNull { listing -> listing.id == listingId } ?: return false
    return resultListing.hasDifferentInteractionThan(baselineListing)
}

private fun ExploreUiState.mergeInteractionResult(
    result: ExploreUiState,
    baseline: ExploreUiState,
    listingId: String,
): ExploreUiState {
    val resultListing = result.listings.firstOrNull { listing -> listing.id == listingId }
    val baselineListing = baseline.listings.firstOrNull { listing -> listing.id == listingId }
    val hasInteractionChange = resultListing != null && baselineListing != null &&
        resultListing.hasDifferentInteractionThan(baselineListing)
    return copy(
        listings = if (!hasInteractionChange) {
            listings
        } else {
            listings.map { current ->
                if (current.id == listingId) {
                    current.copy(
                        liked = resultListing.liked,
                        favorited = resultListing.favorited,
                        likesCount = resultListing.likesCount,
                    )
                } else {
                    current
                }
            }
        },
        isOffline = isOffline || result.isOffline,
        isLocalCacheUnavailable = isLocalCacheUnavailable || result.isLocalCacheUnavailable,
        interactionMessage = result.interactionMessage,
        pendingAuthInteraction = result.pendingAuthInteraction,
        queuedInteractions = result.queuedInteractions,
    )
}

private fun ExploreUiState.mergeCityResult(result: ExploreUiState): ExploreUiState = copy(
    cityLabel = result.cityLabel,
    selectedCityId = result.selectedCityId,
    isCitySelectorOpen = result.isCitySelectorOpen,
    isLocating = result.isLocating,
    locationMessage = result.locationMessage,
    isLocalCacheUnavailable = isLocalCacheUnavailable || result.isLocalCacheUnavailable,
)
