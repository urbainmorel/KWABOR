package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.i18n.FavoritesStrings
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val FAVORITES_DURABLE_EVENT_BUFFER_CAPACITY = 64

class FavoritesRuntime(
    private val presenter: FavoritesPresenter,
    private val strings: FavoritesStrings,
    coroutineScope: CoroutineScope,
    private val interactionCoordinator: InteractionCoordinator? = null,
) {
    private val runtimeJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(coroutineScope.coroutineContext + runtimeJob)
    private val lifecycleMutex = Mutex()
    private val commandChannel = Channel<FavoritesRuntimeCommand>(capacity = Channel.UNLIMITED)
    private val durableEventChannel = Channel<InteractionCoordinatorEvent>(
        capacity = FAVORITES_DURABLE_EVENT_BUFFER_CAPACITY,
    )
    private val durableOverflowAccumulator = FavoritesDurableOverflowAccumulator()
    private val stateStore = FavoritesStateStore()
    private val sessionState = FavoritesSessionState()
    private val runtimeContext = FavoritesRuntimeContext(
        runtimeScope = runtimeScope,
        lifecycleMutex = lifecycleMutex,
        stateStore = stateStore,
        sessionState = sessionState,
    )
    private val durableInteractions = interactionCoordinator?.let { coordinator ->
        FavoritesDurableInteractions(
            coordinator = coordinator,
            strings = strings,
            context = runtimeContext,
        )
    }
    private val pageCoordinator = FavoritesPageCoordinator(
        presenter = presenter,
        strings = strings,
        context = runtimeContext,
        durableInteractions = durableInteractions,
    )
    private val mutationCoordinator = FavoritesMutationCoordinator(
        presenter = presenter,
        strings = strings,
        context = runtimeContext,
        pageCoordinator = pageCoordinator,
    )
    val state: StateFlow<FavoritesUiState> = stateStore.state
    val effects: Flow<FavoritesEffect> = mutationCoordinator.effects

    init {
        interactionCoordinator?.let { coordinator ->
            runtimeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.events.collect { event ->
                    val request = durableOverflowAccumulator.offer(
                        event = event,
                        currentScope = stateStore.value.viewerScope.toInteractionScopeOrNull(),
                        tryEnqueue = { durableEventChannel.trySend(event).isSuccess },
                    )
                    request?.let { current -> publishOverflowRequest(coordinator, current) }
                }
            }
            runtimeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                for (event in durableEventChannel) {
                    try {
                        durableInteractions?.handleEvent(event, pageCoordinator)
                    } finally {
                        durableOverflowAccumulator.eventHandled()?.let { request ->
                            publishOverflowRequest(coordinator, request)
                        }
                    }
                }
            }
            runtimeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.reconciliationSignals.collect { signal ->
                    signal?.let { current ->
                        val acknowledged = durableInteractions?.handleReconciliation(current, pageCoordinator) == true
                        if (acknowledged) {
                            durableOverflowAccumulator.acknowledge(current)?.let { request ->
                                publishOverflowRequest(coordinator, request)
                            }
                        }
                    }
                }
            }
        }
        runtimeScope.launch {
            for (command in commandChannel) {
                handle(command)
            }
        }
    }

    fun dispatch(intent: FavoritesIntent) {
        if (!runtimeJob.isActive) return
        when (intent) {
            is FavoritesIntent.ViewerContextChanged -> publishViewerContext(intent.scope)
            is FavoritesIntent.ExternalFavoriteStateChanged,
            is FavoritesIntent.Lifecycle,
            is FavoritesIntent.ListingAction,
            is FavoritesIntent.Page,
            -> {
                val sourceScope = if (
                    intent is FavoritesIntent.ListingAction || intent == FavoritesIntent.Retry
                ) {
                    stateStore.value.viewerScope
                } else {
                    null
                }
                commandChannel.trySend(FavoritesRuntimeCommand.Intent(intent, sourceScope))
            }
        }
    }

    fun close() {
        commandChannel.close()
        durableEventChannel.close()
        runtimeJob.cancel()
    }

    private suspend fun handle(command: FavoritesRuntimeCommand) {
        when (command) {
            is FavoritesRuntimeCommand.ViewerChanged -> updateViewerContext(command.scope)
            is FavoritesRuntimeCommand.Intent -> handleIntent(command.intent, command.sourceScope)
        }
    }

    private suspend fun handleIntent(intent: FavoritesIntent, sourceScope: ViewerSessionScope?) {
        when (intent) {
            is FavoritesIntent.Lifecycle -> handleLifecycleIntent(intent)
            is FavoritesIntent.Page -> handlePageIntent(intent, sourceScope)
            is FavoritesIntent.ListingAction -> handleListingAction(intent, sourceScope ?: return)
            is FavoritesIntent.ExternalFavoriteStateChanged -> if (durableInteractions == null) {
                mutationCoordinator.applyExternalFavoriteState(intent)
            }
            is FavoritesIntent.ViewerContextChanged -> updateViewerContext(intent.scope)
        }
    }

    private suspend fun handleLifecycleIntent(intent: FavoritesIntent.Lifecycle) {
        when (intent) {
            FavoritesIntent.ScreenAppeared -> screenAppeared()
            FavoritesIntent.ScreenDisappeared -> screenDisappeared()
        }
    }

    private suspend fun handlePageIntent(intent: FavoritesIntent.Page, sourceScope: ViewerSessionScope?) {
        when (intent) {
            is FavoritesIntent.SelectFilter -> selectFilter(intent.filter)
            FavoritesIntent.Retry -> {
                sourceScope?.let { scope -> durableInteractions?.retryManually(scope) }
                pageCoordinator.startLoad(stateStore.value.selectedFilter)
            }
            FavoritesIntent.Refresh -> pageCoordinator.startRefresh(force = false)
            FavoritesIntent.LoadNext -> pageCoordinator.startAppend()
        }
    }

    private suspend fun handleListingAction(intent: FavoritesIntent.ListingAction, sourceScope: ViewerSessionScope) {
        when (intent) {
            is FavoritesIntent.RemoveFavorite -> if (durableInteractions == null) {
                mutationCoordinator.removeFavorite(intent.listingId, sourceScope)
            } else {
                durableInteractions.removeFavorite(intent.listingId, sourceScope, pageCoordinator)
            }
            is FavoritesIntent.OpenListing -> mutationCoordinator.openListing(intent.listingId, sourceScope)
        }
    }

    private fun publishViewerContext(scope: ViewerSessionScope) {
        if (stateStore.publishViewerScope(scope)) {
            commandChannel.trySend(FavoritesRuntimeCommand.ViewerChanged(scope))
        }
    }

    private suspend fun updateViewerContext(scope: ViewerSessionScope) {
        durableOverflowAccumulator.resetScope(stateStore.value.viewerScope.toInteractionScopeOrNull())
        val filterToLoad = lifecycleMutex.withLock {
            if (stateStore.value.viewerScope != scope || sessionState.activeViewerScope == scope) {
                return@withLock null
            }
            sessionState.activeViewerScope = scope
            pageCoordinator.resetForViewerLocked()
            mutationCoordinator.resetForViewerLocked()
            durableInteractions?.resetForViewerLocked()
            sessionState.removedListingIds.clear()
            stateStore.value.selectedFilter.takeIf { sessionState.isScreenVisible && scope.isAuthenticated }
        }
        filterToLoad?.let { selectedFilter -> pageCoordinator.startLoad(selectedFilter) }
    }

    private suspend fun publishOverflowRequest(
        coordinator: InteractionCoordinator,
        request: FavoritesDurableOverflowRequest,
    ) {
        var current: FavoritesDurableOverflowRequest? = request
        while (current != null && runtimeJob.isActive) {
            val requested = coordinator.deliveryCommitGate.requestReconciliationFor(current.event)
            current = if (requested) null else durableOverflowAccumulator.requestRejected(current)
        }
    }

    private suspend fun screenAppeared() {
        durableInteractions?.onScreenAppeared()
        val action = lifecycleMutex.withLock {
            if (sessionState.isScreenVisible) return@withLock FavoritesPageAction.None
            sessionState.isScreenVisible = true
            pageCoordinator.screenAppearedActionLocked()
        }
        when (action) {
            FavoritesPageAction.None -> Unit
            FavoritesPageAction.Refresh -> pageCoordinator.startRefresh(force = true)
            is FavoritesPageAction.Append -> pageCoordinator.startAppend(action.backfill)
            is FavoritesPageAction.Load -> pageCoordinator.startLoad(action.filter)
        }
    }

    private suspend fun screenDisappeared() {
        lifecycleMutex.withLock { sessionState.isScreenVisible = false }
    }

    private suspend fun selectFilter(filter: FavoritesFilter) {
        val shouldLoad = lifecycleMutex.withLock {
            val current = stateStore.value
            if (filter == current.selectedFilter) return@withLock false
            pageCoordinator.resetForFilterLocked()
            if (!sessionState.hasCurrentActiveAccount(stateStore) || !sessionState.isScreenVisible) {
                stateStore.updateForScope(sessionState.activeViewerScope) { state ->
                    FavoritesUiState(
                        selectedFilter = filter,
                        isAccountReady = state.isAccountReady,
                        viewerScope = sessionState.activeViewerScope,
                    )
                }
                false
            } else {
                true
            }
        }
        if (shouldLoad) pageCoordinator.startLoad(filter)
    }
}

private fun ViewerSessionScope.toInteractionScopeOrNull() =
    takeIf { scope -> scope.isAuthenticated }?.toInteractionScope()

private sealed interface FavoritesRuntimeCommand {
    data class Intent(
        val intent: FavoritesIntent,
        val sourceScope: ViewerSessionScope?,
    ) : FavoritesRuntimeCommand

    data class ViewerChanged(val scope: ViewerSessionScope) : FavoritesRuntimeCommand
}
