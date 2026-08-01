package com.kwabor.android.presentation.explore

import androidx.lifecycle.ViewModel
import com.kwabor.android.auth.ApproximateLocationResult
import com.kwabor.android.auth.ApproximateLocationService
import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.isWithinBeninBounds
import com.kwabor.shared.domain.catalog.nearestCity
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.explore.ExploreChip
import com.kwabor.shared.presentation.explore.ExploreInteractionKind
import com.kwabor.shared.presentation.explore.ExploreListingItem
import com.kwabor.shared.presentation.explore.ExploreLoadRequest
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.loadingExploreUiState
import kotlinx.coroutines.CoroutineScope
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
import java.util.concurrent.atomic.AtomicLong

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
    data object AuthenticationRequired : ExploreEffect

    data object RequestLocationPermission : ExploreEffect
}

internal class ExploreViewModel(
    private val presenter: ExplorePresenter,
    private val locationService: ApproximateLocationService,
    private val strings: KwaborStrings,
    private val coroutineScope: CoroutineScope,
) : ViewModel() {
    private val stateStore = ExploreStateStore(
        loadingExploreUiState(strings = strings, request = ExploreLoadRequest()),
    )
    val state: StateFlow<ExploreUiState> = stateStore.state

    private val effectChannel = Channel<ExploreEffect>(capacity = Channel.BUFFERED)
    val effects: Flow<ExploreEffect> = effectChannel.receiveAsFlow()

    private val citySelectionMutex = Mutex()
    private var feedJob: Job? = null
    private var locationJob: Job? = null
    private var citySelectionJob: Job? = null
    private val feedGeneration = AtomicLong()
    private val cityGeneration = AtomicLong()
    private val viewerSessionCoordinator = ExploreViewerSessionCoordinator(
        presenter = presenter,
        strings = strings,
        coroutineScope = coroutineScope,
        stateStore = stateStore,
        callbacks = ExploreViewerSessionCallbacks(
            invalidateFeed = {
                feedJob?.cancel()
                feedGeneration.incrementAndGet()
            },
            reloadFeed = ::load,
            publishEffect = effectChannel::send,
        ),
    )

    init {
        load(ExploreLoadRequest())
    }

    fun onIntent(intent: ExploreIntent) {
        when (intent) {
            is ExploreIntent.Feed -> handleFeedIntent(intent)
            is ExploreIntent.SelectCity -> selectCity(intent.cityId)
            is ExploreIntent.ToggleLike -> viewerSessionCoordinator.toggle(
                intent.listingId,
                ExploreInteractionKind.Like,
            )
            is ExploreIntent.ToggleFavorite -> viewerSessionCoordinator.toggle(
                intent.listingId,
                ExploreInteractionKind.Favorite,
            )
            is ExploreIntent.LocationPermissionResult -> resolveLocationPermission(intent.granted)
            ExploreIntent.OpenCitySelector -> setCitySelector(open = true)
            ExploreIntent.CloseCitySelector -> setCitySelector(open = false)
            ExploreIntent.RequestLocation -> requestLocation()
            ExploreIntent.ReplayPendingInteraction -> viewerSessionCoordinator.replayPendingInteraction()
            is ExploreIntent.ViewerContextChanged -> viewerSessionCoordinator.updateViewerContext(intent.viewerId)
        }
    }

    private fun handleFeedIntent(intent: ExploreIntent.Feed) {
        val current = stateStore.value
        when (intent) {
            is ExploreIntent.SelectTab -> if (intent.tab != current.selectedTab) {
                viewerSessionCoordinator.invalidateFeedContext()
                load(
                    ExploreLoadRequest(
                        selectedTab = intent.tab,
                        selectedCityId = current.selectedCityId,
                    ),
                )
            }
            is ExploreIntent.SelectChip -> {
                viewerSessionCoordinator.invalidateFeedContext()
                load(
                    current.toLoadRequest().copy(
                        selectedChipId = intent.chip.id.takeUnless { chipId -> chipId == current.selectedChipId },
                    ),
                )
            }
            ExploreIntent.Retry -> load(current.toLoadRequest())
            ExploreIntent.Refresh -> refresh()
            ExploreIntent.LoadNext -> loadNext()
        }
    }

    override fun onCleared() {
        viewerSessionCoordinator.close()
        effectChannel.close()
        coroutineScope.cancel()
        super.onCleared()
    }

    private fun load(request: ExploreLoadRequest) {
        feedJob?.cancel()
        val generation = feedGeneration.incrementAndGet()
        feedJob = coroutineScope.launch {
            val prepared = presenter.prepareInitialState(request, strings)
            stateStore.updateIf(
                predicate = { generation == feedGeneration.get() },
                transform = { current -> prepared.forNewRequest(current).copy(isLoading = true) },
            ) ?: return@launch

            val cacheBaseline = stateStore.feedBaseline()
            val cached = presenter.loadCached(cacheBaseline.state.copy(isLoading = false), strings)
            val hasCachedSnapshot = cached.feedSnapshot != null
            stateStore.commitFeed(
                result = cached.copy(
                    isLoading = !hasCachedSnapshot,
                    isRefreshing = hasCachedSnapshot,
                ),
                baselineInteractionRevisions = cacheBaseline.interactionRevisions,
                canCommit = { generation == feedGeneration.get() },
            ) ?: return@launch

            val refreshBaseline = stateStore.feedBaseline()
            val refreshed = presenter.refresh(refreshBaseline.state, strings)
            stateStore.commitFeed(
                result = refreshed,
                baselineInteractionRevisions = refreshBaseline.interactionRevisions,
                canCommit = { generation == feedGeneration.get() },
            )
        }
    }

    private fun refresh() {
        if (stateStore.value.isLoading || stateStore.value.isRefreshing) return
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

    private fun loadNext() {
        if (!stateStore.value.canAttemptAppend()) return
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

    private fun startFeedOperation(
        canStart: (ExploreUiState) -> Boolean,
        markStarted: (ExploreUiState) -> ExploreUiState,
        operation: suspend (ExploreUiState) -> ExploreUiState,
    ) {
        feedJob?.cancel()
        val generation = feedGeneration.incrementAndGet()
        feedJob = coroutineScope.launch {
            val operationBaseline = stateStore.prepareFeed(
                predicate = { current -> generation == feedGeneration.get() && canStart(current) },
                transform = markStarted,
            ) ?: return@launch
            val result = operation(operationBaseline.state)
            stateStore.commitFeed(
                result = result,
                baselineInteractionRevisions = operationBaseline.interactionRevisions,
                canCommit = { generation == feedGeneration.get() },
            )
        }
    }

    private fun selectCity(cityId: String) {
        if (cityId != stateStore.value.selectedCityId) {
            viewerSessionCoordinator.invalidateFeedContext()
        }
        locationJob?.cancel()
        locationJob = null
        citySelectionJob?.cancel()
        val generation = cityGeneration.incrementAndGet()
        citySelectionJob = coroutineScope.launch {
            citySelectionMutex.withLock {
                val before = stateStore.snapshot()
                val result = presenter.selectCity(before, cityId, strings).copy(isLocating = false)
                if (generation != cityGeneration.get()) return@withLock
                val committed = stateStore.update { current ->
                    if (generation == cityGeneration.get()) current.mergeCityResult(result) else current
                }
                if (generation == cityGeneration.get() && committed.selectedCityId != before.selectedCityId) {
                    load(committed.toLoadRequest())
                }
            }
        }
    }

    private fun requestLocation() {
        if (stateStore.value.isLocating) return
        coroutineScope.launch {
            val locatingState = stateStore.updateIf(
                predicate = { current -> !current.isLocating },
                transform = { current -> current.copy(isLocating = true, locationMessage = null) },
            ) ?: return@launch
            if (locatingState.isLocating) {
                effectChannel.send(ExploreEffect.RequestLocationPermission)
            }
        }
    }

    private fun resolveLocationPermission(granted: Boolean) {
        if (!stateStore.value.isLocating) return
        if (!granted) {
            coroutineScope.launch { stateStore.setLocationFailure(strings.exploreLocationPermissionDenied) }
            return
        }
        locationJob?.cancel()
        locationJob = coroutineScope.launch {
            when (val result = locationService.currentApproximateLocation()) {
                is ApproximateLocationResult.Available -> selectNearestCity(result.latitude, result.longitude)
                ApproximateLocationResult.PermissionDenied,
                is ApproximateLocationResult.PermissionFailure,
                -> stateStore.setLocationFailure(strings.exploreLocationPermissionDenied)
                ApproximateLocationResult.LocationDisabled ->
                    stateStore.setLocationFailure(strings.exploreLocationDisabled)
                ApproximateLocationResult.Unavailable,
                is ApproximateLocationResult.UnavailableFailure,
                -> stateStore.setLocationFailure(strings.exploreLocationUnavailable)
            }
        }
    }

    private suspend fun selectNearestCity(latitude: Double, longitude: Double) {
        if (!latitude.isFinite() || !longitude.isFinite()) {
            stateStore.setLocationFailure(strings.exploreLocationUnavailable)
            return
        }
        val location = GeoPoint(latitude = latitude, longitude = longitude)
        if (!location.isWithinBeninBounds) {
            stateStore.setLocationFailure(strings.exploreLocationOutsideBenin)
            return
        }
        val city = nearestCity(
            cities = stateStore.snapshot().feedSnapshot?.cities.orEmpty(),
            location = location,
        )
        if (city == null) {
            stateStore.setLocationFailure(strings.exploreLocationUnavailable)
            return
        }
        locationJob = null
        selectCity(city.id)
    }

    private fun setCitySelector(open: Boolean) {
        if (!open) {
            locationJob?.cancel()
            locationJob = null
        }
        coroutineScope.launch {
            stateStore.update { current ->
                current.copy(
                    isCitySelectorOpen = open,
                    isLocating = if (open) current.isLocating else false,
                    locationMessage = null,
                )
            }
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
    val invalidateFeed: () -> Unit,
    val reloadFeed: (ExploreLoadRequest) -> Unit,
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
    private var viewerContext: ExploreViewerContext = ExploreViewerContext.Uninitialized
    private var interactionSupervisor: Job = SupervisorJob()
    private val parentCompletionHandle = coroutineScope.coroutineContext[Job]?.invokeOnCompletion {
        interactionSupervisor.cancel()
    }
    private var interactionScope = CoroutineScope(coroutineScope.coroutineContext + interactionSupervisor)
    private var viewerContextJob: Job? = null
    private val viewerGeneration = AtomicLong()
    private val interactionContextGeneration = AtomicLong()

    fun toggle(listingId: String, kind: ExploreInteractionKind) {
        val viewerAtRequest = viewerGeneration.get()
        val contextAtRequest = interactionContextGeneration.get()
        interactionScope.launch {
            performToggle(
                listingId = listingId,
                kind = kind,
                viewerAtRequest = viewerAtRequest,
                contextAtRequest = contextAtRequest,
            )
        }
    }

    fun invalidateFeedContext() {
        interactionContextGeneration.incrementAndGet()
    }

    fun replayPendingInteraction() {
        val current = stateStore.value
        val pending = current.pendingAuthInteraction ?: return
        if (current.listings.none { listing -> listing.id == pending.listingId }) {
            coroutineScope.launch {
                stateStore.update { latest ->
                    if (latest.pendingAuthInteraction == pending) {
                        latest.copy(pendingAuthInteraction = null, interactionMessage = null)
                    } else {
                        latest
                    }
                }
            }
            return
        }
        toggle(listingId = pending.listingId, kind = pending.kind)
    }

    fun updateViewerContext(viewerId: String?) {
        val nextContext = ExploreViewerContext.fromViewerId(viewerId)
        val previousContext = viewerContext
        if (nextContext == previousContext) {
            clearGuestPendingInteraction(nextContext)
            return
        }

        viewerContext = nextContext
        val viewerAtRequest = viewerGeneration.incrementAndGet()
        val contextAtRequest = interactionContextGeneration.incrementAndGet()
        resetInteractionScope()
        viewerContextJob?.cancel()
        if (previousContext == ExploreViewerContext.Uninitialized) return
        startViewerTransition(previousContext, nextContext, viewerAtRequest, contextAtRequest)
    }

    fun close() {
        viewerContextJob?.cancel()
        parentCompletionHandle?.dispose()
        interactionSupervisor.cancel()
    }

    private fun clearGuestPendingInteraction(context: ExploreViewerContext) {
        if (context != ExploreViewerContext.Guest) return
        coroutineScope.launch {
            stateStore.update { current ->
                current.copy(pendingAuthInteraction = null, interactionMessage = null)
            }
        }
    }

    private fun startViewerTransition(
        previousContext: ExploreViewerContext,
        nextContext: ExploreViewerContext,
        viewerAtRequest: Long,
        contextAtRequest: Long,
    ) {
        val pendingToReplay = stateStore.value.pendingAuthInteraction.takeIf {
            previousContext == ExploreViewerContext.Guest && nextContext is ExploreViewerContext.Authenticated
        }
        callbacks.invalidateFeed()
        viewerContextJob = coroutineScope.launch {
            val resetState = stateStore.resetViewerState(
                canReset = { viewerAtRequest == viewerGeneration.get() },
            ) ?: return@launch
            pendingToReplay
                ?.takeIf { pending -> resetState.listings.any { listing -> listing.id == pending.listingId } }
                ?.let { pending ->
                    performToggle(
                        listingId = pending.listingId,
                        kind = pending.kind,
                        viewerAtRequest = viewerAtRequest,
                        contextAtRequest = contextAtRequest,
                    )
                }
            if (!isCurrentInteraction(viewerAtRequest, contextAtRequest)) return@launch
            callbacks.reloadFeed(stateStore.value.toLoadRequest())
        }
    }

    private suspend fun performToggle(
        listingId: String,
        kind: ExploreInteractionKind,
        viewerAtRequest: Long,
        contextAtRequest: Long,
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
            val committed = stateStore.commitInteraction(
                result = result,
                baseline = before,
                listingId = listingId,
                canCommit = { current ->
                    isCurrentInteraction(viewerAtRequest, contextAtRequest) &&
                        current.listings.any { listing -> listing.id == listingId }
                },
            ) ?: return@withLock
            if (authenticationRequired && committed.pendingAuthInteraction != null) {
                callbacks.publishEffect(ExploreEffect.AuthenticationRequired)
            }
        }
    }

    private fun isCurrentInteraction(viewerAtRequest: Long, contextAtRequest: Long): Boolean =
        viewerAtRequest == viewerGeneration.get() && contextAtRequest == interactionContextGeneration.get()

    private fun resetInteractionScope() {
        interactionSupervisor.cancel()
        interactionSupervisor = SupervisorJob().also { supervisor ->
            if (coroutineScope.coroutineContext[Job]?.isActive != true) {
                supervisor.cancel()
            }
        }
        interactionScope = CoroutineScope(coroutineScope.coroutineContext + interactionSupervisor)
    }
}

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

    suspend fun setLocationFailure(message: String) {
        update { current -> current.copy(isLocating = false, locationMessage = message) }
    }

    suspend fun resetViewerState(canReset: () -> Boolean): ExploreUiState? = mutex.withLock {
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
        canCommit: (ExploreUiState) -> Boolean,
    ): ExploreUiState? = mutex.withLock {
        val current = mutableState.value
        if (!canCommit(current)) return@withLock null
        if (result.hasInteractionChangeComparedTo(baseline, listingId)) {
            interactionRevisionsByListingId[listingId] = ++interactionRevision
            result.listings.firstOrNull { listing -> listing.id == listingId }?.let { listing ->
                interactionOverridesByListingId[listingId] = listing
            }
        }
        current.mergeInteractionResult(result, baseline, listingId).also { updated ->
            mutableState.value = updated
        }
    }

    suspend fun commitFeed(
        result: ExploreUiState,
        baselineInteractionRevisions: Map<String, Long>,
        canCommit: () -> Boolean,
    ): ExploreUiState? = mutex.withLock {
        if (!canCommit()) return@withLock null
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
