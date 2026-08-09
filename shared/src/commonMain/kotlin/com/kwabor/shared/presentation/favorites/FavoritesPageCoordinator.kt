package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.i18n.FavoritesStrings
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FavoritesPageCoordinator(
    private val presenter: FavoritesPresenter,
    private val strings: FavoritesStrings,
    context: FavoritesRuntimeContext,
) {
    private val runtimeScope: CoroutineScope = context.runtimeScope
    private val lifecycleMutex: Mutex = context.lifecycleMutex
    private val stateStore: FavoritesStateStore = context.stateStore
    private val sessionState: FavoritesSessionState = context.sessionState
    private var hasLoadedForActiveScope = false
    private var hasExternalAdditionDirty = false
    private var operationGeneration = 0L
    private var operationJob: Job? = null
    private var pendingRemovalBackfill: FavoriteRemovalBackfill? = null
    private var refreshAfterOperation = false

    fun resetForViewerLocked() {
        hasLoadedForActiveScope = false
        hasExternalAdditionDirty = false
        operationGeneration += 1L
        operationJob?.cancel()
        operationJob = null
        pendingRemovalBackfill = null
        refreshAfterOperation = false
    }

    fun resetForFilterLocked() {
        pendingRemovalBackfill = null
    }

    fun screenAppearedActionLocked(): FavoritesPageAction = when {
        !sessionState.hasCurrentActiveAccount(stateStore) -> FavoritesPageAction.None
        operationJob?.isActive == true -> {
            refreshAfterOperation = true
            FavoritesPageAction.None
        }
        refreshAfterOperation -> {
            refreshAfterOperation = false
            FavoritesPageAction.Refresh
        }
        hasLoadedForActiveScope || hasExternalAdditionDirty -> FavoritesPageAction.Refresh
        else -> FavoritesPageAction.Load(stateStore.value.selectedFilter)
    }

    fun markExternalAdditionDirtyLocked() {
        hasExternalAdditionDirty = true
    }

    suspend fun runAction(action: FavoritesPageAction) {
        when (action) {
            FavoritesPageAction.None -> Unit
            FavoritesPageAction.Refresh -> startRefresh(force = true)
            is FavoritesPageAction.Append -> startAppend(action.backfill)
            is FavoritesPageAction.Load -> startLoad(action.filter)
        }
    }

    suspend fun startLoad(filter: FavoritesFilter) {
        lifecycleMutex.withLock {
            val scope = sessionState.activeViewerScope.takeIf { candidate -> candidate.isAuthenticated }
                ?: return@withLock
            if (!sessionState.isScreenVisible || !sessionState.hasCurrentActiveAccount(stateStore)) {
                return@withLock
            }
            operationJob?.cancel()
            val requestGeneration = ++operationGeneration
            refreshAfterOperation = false
            sessionState.removedListingIds.clear()
            hasExternalAdditionDirty = false
            val loading = stateStore.updateForScope(scope) { current ->
                FavoritesUiState(
                    selectedFilter = filter,
                    isAccountReady = true,
                    isLoading = true,
                    isOffline = current.contentIsOffline || current.mutationMessageIsOffline,
                    mutationMessage = current.mutationMessage,
                    removingListingIds = current.removingListingIds,
                    mutationMessageListingId = current.mutationMessageListingId,
                    mutationMessageIsOffline = current.mutationMessageIsOffline,
                    contentIsOffline = current.contentIsOffline,
                    viewerScope = scope,
                )
            } ?: return@withLock
            launchPageOperation(scope, requestGeneration, loading.selectedFilter) {
                presenter.load(filter = filter, strings = strings)
            }
        }
    }

    suspend fun startRefresh(force: Boolean) {
        lifecycleMutex.withLock {
            val scope = sessionState.activeViewerScope.takeIf { candidate -> candidate.isAuthenticated }
                ?: return@withLock
            val current = stateStore.value
            if (!sessionState.isScreenVisible) return@withLock
            if (!sessionState.hasCurrentActiveAccount(stateStore)) return@withLock
            if (!force && current.isLoading) return@withLock
            if (!force && current.isRefreshing) return@withLock
            if (!force && current.isAppending) return@withLock
            operationJob?.cancel()
            val requestGeneration = ++operationGeneration
            refreshAfterOperation = false
            sessionState.removedListingIds.clear()
            hasExternalAdditionDirty = false
            val refreshing = stateStore.updateForScope(scope) { latest ->
                latest.copy(
                    isLoading = latest.items.isEmpty(),
                    isRefreshing = latest.items.isNotEmpty(),
                    isAppending = false,
                    errorMessage = null,
                    refreshMessage = null,
                    appendErrorMessage = null,
                    viewerScope = scope,
                )
            } ?: return@withLock
            launchPageOperation(scope, requestGeneration, refreshing.selectedFilter) {
                presenter.refresh(state = refreshing, strings = strings)
            }
        }
    }

    suspend fun startAppend(expectedBackfill: FavoriteRemovalBackfill? = null) {
        lifecycleMutex.withLock {
            val scope = sessionState.activeViewerScope.takeIf { candidate -> candidate.isAuthenticated }
                ?: return@withLock
            val current = stateStore.value
            if (expectedBackfill != null && !canStartRemovalBackfill(expectedBackfill, current)) {
                return@withLock
            }
            if (
                !sessionState.isScreenVisible ||
                !sessionState.hasCurrentActiveAccount(stateStore) ||
                !current.canLoadMore
            ) {
                return@withLock
            }
            val requestGeneration = ++operationGeneration
            val appending = stateStore.updateForScope(scope) { latest ->
                latest.copy(
                    isAppending = true,
                    appendErrorMessage = null,
                    viewerScope = scope,
                )
            } ?: return@withLock
            launchPageOperation(scope, requestGeneration, appending.selectedFilter) {
                presenter.append(state = appending, strings = strings)
            }
        }
    }

    private fun launchPageOperation(
        requestScope: ViewerSessionScope,
        requestGeneration: Long,
        filter: FavoritesFilter,
        operation: suspend () -> FavoritesUiState,
    ) {
        val job = runtimeScope.launch(start = CoroutineStart.LAZY) {
            commitPageResult(
                requestScope = requestScope,
                requestGeneration = requestGeneration,
                filter = filter,
                result = operation(),
            )
        }
        operationJob = job
        job.start()
    }

    fun registerRemovalBackfillLocked(scope: ViewerSessionScope, state: FavoritesUiState): FavoriteRemovalBackfill? {
        val operationInFlight = operationJob?.isActive == true
        val hasCurrentVisibleScope = sessionState.isScreenVisible &&
            sessionState.activeViewerScope == scope &&
            state.viewerScope == scope
        if (!hasCurrentVisibleScope) return null
        if (!sessionState.hasCurrentActiveAccount(stateStore)) return null
        if (!operationInFlight && (state.items.isNotEmpty() || state.nextCursor == null)) return null
        val backfill = FavoriteRemovalBackfill(scope = scope, filter = state.selectedFilter)
        pendingRemovalBackfill = backfill
        return backfill.takeIf { candidate -> canStartRemovalBackfill(candidate, state) }
    }

    private suspend fun commitPageResult(
        requestScope: ViewerSessionScope,
        requestGeneration: Long,
        filter: FavoritesFilter,
        result: FavoritesUiState,
    ) {
        val nextAction = lifecycleMutex.withLock {
            if (sessionState.activeViewerScope != requestScope) return@withLock null
            if (operationGeneration != requestGeneration) return@withLock null
            if (stateStore.value.viewerScope != requestScope) return@withLock null
            if (stateStore.value.selectedFilter != filter) return@withLock null
            val committed = stateStore.updateForScope(requestScope) { current ->
                result.copy(
                    items = result.items.filterNot { item -> item.id in sessionState.removedListingIds },
                    isAccountReady = true,
                    isOffline = result.contentIsOffline || current.mutationMessageIsOffline,
                    mutationMessage = current.mutationMessage,
                    removingListingIds = current.removingListingIds,
                    mutationMessageListingId = current.mutationMessageListingId,
                    mutationMessageIsOffline = current.mutationMessageIsOffline,
                    contentIsOffline = result.contentIsOffline,
                    viewerScope = requestScope,
                )
            } ?: return@withLock null
            hasLoadedForActiveScope = !committed.isLoading && !committed.isRefreshing
            operationJob = null
            nextActionAfterOperation(committed)
        }
        runAction(nextAction ?: FavoritesPageAction.None)
    }

    private fun nextActionAfterOperation(state: FavoritesUiState): FavoritesPageAction {
        if (refreshAfterOperation && sessionState.isScreenVisible) {
            refreshAfterOperation = false
            return FavoritesPageAction.Refresh
        }
        val backfill = removalBackfillAfterOperation(state)
        return backfill?.let { request -> FavoritesPageAction.Append(request) } ?: FavoritesPageAction.None
    }

    private fun removalBackfillAfterOperation(state: FavoritesUiState): FavoriteRemovalBackfill? {
        val backfill = pendingRemovalBackfill ?: return null
        if (state.viewerScope != backfill.scope || state.selectedFilter != backfill.filter) {
            pendingRemovalBackfill = null
            return null
        }
        if (state.items.isNotEmpty() || state.nextCursor == null) {
            pendingRemovalBackfill = null
            return null
        }
        return backfill.takeIf { candidate -> canStartRemovalBackfill(candidate, state) }
    }

    private fun canStartRemovalBackfill(backfill: FavoriteRemovalBackfill, state: FavoritesUiState): Boolean {
        if (pendingRemovalBackfill != backfill) return false
        if (!sessionState.isScreenVisible) return false
        if (sessionState.activeViewerScope != backfill.scope) return false
        if (state.viewerScope != backfill.scope || state.selectedFilter != backfill.filter) return false
        if (!sessionState.hasCurrentActiveAccount(stateStore)) return false
        if (operationJob?.isActive == true || state.items.isNotEmpty()) return false
        if (state.errorMessage != null || state.appendErrorMessage != null) return false
        return state.canLoadMore
    }
}

internal sealed interface FavoritesPageAction {
    data object None : FavoritesPageAction

    data object Refresh : FavoritesPageAction

    data class Append(val backfill: FavoriteRemovalBackfill) : FavoritesPageAction

    data class Load(val filter: FavoritesFilter) : FavoritesPageAction
}

internal data class FavoriteRemovalBackfill(
    val scope: ViewerSessionScope,
    val filter: FavoritesFilter,
)
