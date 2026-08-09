package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.i18n.FavoritesStrings
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FavoritesRuntime(
    private val presenter: FavoritesPresenter,
    private val strings: FavoritesStrings,
    coroutineScope: CoroutineScope,
) {
    private val runtimeJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(coroutineScope.coroutineContext + runtimeJob)
    private val lifecycleMutex = Mutex()
    private val commandChannel = Channel<FavoritesRuntimeCommand>(capacity = Channel.UNLIMITED)
    private val stateStore = FavoritesStateStore()
    private val sessionState = FavoritesSessionState()
    private val runtimeContext = FavoritesRuntimeContext(
        runtimeScope = runtimeScope,
        lifecycleMutex = lifecycleMutex,
        stateStore = stateStore,
        sessionState = sessionState,
    )
    private val pageCoordinator = FavoritesPageCoordinator(
        presenter = presenter,
        strings = strings,
        context = runtimeContext,
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
                val sourceScope = if (intent is FavoritesIntent.ListingAction) {
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
        runtimeJob.cancel()
    }

    private suspend fun handle(command: FavoritesRuntimeCommand) {
        when (command) {
            is FavoritesRuntimeCommand.ViewerChanged -> updateViewerContext(command.scope)
            is FavoritesRuntimeCommand.Intent -> handleIntent(command.intent, command.sourceScope)
        }
    }

    private suspend fun handleIntent(
        intent: FavoritesIntent,
        sourceScope: ViewerSessionScope?,
    ) {
        when (intent) {
            is FavoritesIntent.Lifecycle -> handleLifecycleIntent(intent)
            is FavoritesIntent.Page -> handlePageIntent(intent)
            is FavoritesIntent.ListingAction -> handleListingAction(intent, sourceScope ?: return)
            is FavoritesIntent.ExternalFavoriteStateChanged -> mutationCoordinator.applyExternalFavoriteState(intent)
            is FavoritesIntent.ViewerContextChanged -> updateViewerContext(intent.scope)
        }
    }

    private suspend fun handleLifecycleIntent(intent: FavoritesIntent.Lifecycle) {
        when (intent) {
            FavoritesIntent.ScreenAppeared -> screenAppeared()
            FavoritesIntent.ScreenDisappeared -> screenDisappeared()
        }
    }

    private suspend fun handlePageIntent(intent: FavoritesIntent.Page) {
        when (intent) {
            is FavoritesIntent.SelectFilter -> selectFilter(intent.filter)
            FavoritesIntent.Retry -> pageCoordinator.startLoad(stateStore.value.selectedFilter)
            FavoritesIntent.Refresh -> pageCoordinator.startRefresh(force = false)
            FavoritesIntent.LoadNext -> pageCoordinator.startAppend()
        }
    }

    private suspend fun handleListingAction(
        intent: FavoritesIntent.ListingAction,
        sourceScope: ViewerSessionScope,
    ) {
        when (intent) {
            is FavoritesIntent.RemoveFavorite -> mutationCoordinator.removeFavorite(intent.listingId, sourceScope)
            is FavoritesIntent.OpenListing -> mutationCoordinator.openListing(intent.listingId, sourceScope)
        }
    }

    private fun publishViewerContext(scope: ViewerSessionScope) {
        if (stateStore.publishViewerScope(scope)) {
            commandChannel.trySend(FavoritesRuntimeCommand.ViewerChanged(scope))
        }
    }

    private suspend fun updateViewerContext(scope: ViewerSessionScope) {
        val filterToLoad = lifecycleMutex.withLock {
            if (stateStore.value.viewerScope != scope || sessionState.activeViewerScope == scope) {
                return@withLock null
            }
            sessionState.activeViewerScope = scope
            pageCoordinator.resetForViewerLocked()
            mutationCoordinator.resetForViewerLocked()
            sessionState.removedListingIds.clear()
            stateStore.value.selectedFilter.takeIf { sessionState.isScreenVisible && scope.isAuthenticated }
        }
        filterToLoad?.let { selectedFilter -> pageCoordinator.startLoad(selectedFilter) }
    }

    private suspend fun screenAppeared() {
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

private sealed interface FavoritesRuntimeCommand {
    data class Intent(
        val intent: FavoritesIntent,
        val sourceScope: ViewerSessionScope?,
    ) : FavoritesRuntimeCommand

    data class ViewerChanged(val scope: ViewerSessionScope) : FavoritesRuntimeCommand
}
