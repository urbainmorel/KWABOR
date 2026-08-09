package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal data class FavoritesRuntimeContext(
    val runtimeScope: CoroutineScope,
    val lifecycleMutex: Mutex,
    val stateStore: FavoritesStateStore,
    val sessionState: FavoritesSessionState,
)

internal class FavoritesStateStore {
    private val mutableState = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = mutableState.asStateFlow()
    val value: FavoritesUiState
        get() = mutableState.value

    fun publishViewerScope(scope: ViewerSessionScope): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.viewerScope == scope || scope.epoch <= current.viewerScope.epoch) return false
            val next = FavoritesUiState(
                selectedFilter = current.selectedFilter,
                isAccountReady = scope.isAuthenticated,
                viewerScope = scope,
            )
            if (mutableState.compareAndSet(current, next)) return true
        }
    }

    fun updateForScope(
        scope: ViewerSessionScope,
        transform: (FavoritesUiState) -> FavoritesUiState,
    ): FavoritesUiState? {
        while (true) {
            val current = mutableState.value
            if (current.viewerScope != scope) return null
            val updated = transform(current)
            check(updated.viewerScope == scope) { "Favorites state updates cannot change viewer scope." }
            if (mutableState.compareAndSet(current, updated)) return updated
        }
    }
}

internal class FavoritesSessionState {
    var activeViewerScope: ViewerSessionScope = ViewerSessionScope.InitialGuest
    var isScreenVisible: Boolean = false
    val removedListingIds: MutableSet<String> = mutableSetOf()

    fun hasCurrentActiveAccount(stateStore: FavoritesStateStore): Boolean =
        activeViewerScope.isAuthenticated && stateStore.value.viewerScope == activeViewerScope
}
