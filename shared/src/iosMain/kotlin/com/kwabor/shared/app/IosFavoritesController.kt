package com.kwabor.shared.app

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.FavoritesStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.favorites.FavoritesEffect
import com.kwabor.shared.presentation.favorites.FavoritesFilter
import com.kwabor.shared.presentation.favorites.FavoritesIntent
import com.kwabor.shared.presentation.favorites.FavoritesPresenter
import com.kwabor.shared.presentation.favorites.FavoritesRuntime
import com.kwabor.shared.presentation.favorites.FavoritesUiState
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class IosFavoritesActions internal constructor(
    private val dispatch: (FavoritesIntent) -> Unit,
) {
    fun screenAppeared() {
        dispatch(FavoritesIntent.ScreenAppeared)
    }

    fun screenDisappeared() {
        dispatch(FavoritesIntent.ScreenDisappeared)
    }

    fun updateViewerContext(scope: ViewerSessionScope) {
        dispatch(FavoritesIntent.ViewerContextChanged(scope))
    }

    fun applyExternalFavoriteState(
        listingId: String,
        favorited: Boolean,
        clientMutationSequence: Long,
        scope: ViewerSessionScope,
    ) {
        dispatch(
            FavoritesIntent.ExternalFavoriteStateChanged(
                listingId = listingId,
                favorited = favorited,
                clientMutationSequence = clientMutationSequence,
                scope = scope,
            ),
        )
    }

    fun selectAll() {
        selectFilter(FavoritesFilter.All)
    }

    fun selectPlaces() {
        selectFilter(FavoritesFilter.Places)
    }

    fun selectEvents() {
        selectFilter(FavoritesFilter.Events)
    }

    fun selectHotelsRestaurants() {
        selectFilter(FavoritesFilter.HotelsRestaurants)
    }

    fun retry() {
        dispatch(FavoritesIntent.Retry)
    }

    fun refresh() {
        dispatch(FavoritesIntent.Refresh)
    }

    fun loadNext() {
        dispatch(FavoritesIntent.LoadNext)
    }

    fun removeFavorite(listingId: String) {
        dispatch(FavoritesIntent.RemoveFavorite(listingId))
    }

    fun openListing(listingId: String) {
        dispatch(FavoritesIntent.OpenListing(listingId))
    }

    private fun selectFilter(filter: FavoritesFilter) {
        dispatch(FavoritesIntent.SelectFilter(filter))
    }
}

internal interface IosFavoritesRuntime {
    val state: StateFlow<FavoritesUiState>
    val effects: Flow<FavoritesEffect>

    fun dispatch(intent: FavoritesIntent)

    fun close()
}

private class DefaultIosFavoritesRuntime(
    private val delegate: FavoritesRuntime,
) : IosFavoritesRuntime {
    override val state: StateFlow<FavoritesUiState> = delegate.state
    override val effects: Flow<FavoritesEffect> = delegate.effects

    override fun dispatch(intent: FavoritesIntent) {
        delegate.dispatch(intent)
    }

    override fun close() {
        delegate.close()
    }
}

class IosFavoritesController private constructor(
    runtimeProvider: (CoroutineScope, FavoritesStrings) -> IosFavoritesRuntime?,
    dispatcherProvider: DispatcherProvider,
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
) {
    internal constructor(
        presenter: FavoritesPresenter?,
        dispatcherProvider: DispatcherProvider,
        viewerSessionScopeTracker: ViewerSessionScopeTracker,
    ) : this(
        runtimeProvider = { scope, strings ->
            presenter?.let { currentPresenter ->
                DefaultIosFavoritesRuntime(
                    FavoritesRuntime(
                        presenter = currentPresenter,
                        strings = strings,
                        coroutineScope = scope,
                    ),
                )
            }
        },
        dispatcherProvider = dispatcherProvider,
        viewerSessionScopeTracker = viewerSessionScopeTracker,
    )

    internal constructor(
        runtime: IosFavoritesRuntime?,
        dispatcherProvider: DispatcherProvider,
        viewerSessionScopeTracker: ViewerSessionScopeTracker,
    ) : this(
        runtimeProvider = { _, _ -> runtime },
        dispatcherProvider = dispatcherProvider,
        viewerSessionScopeTracker = viewerSessionScopeTracker,
    )

    val strings: FavoritesStrings = stringsFor(AppLocale.French).favorites
    val actions = IosFavoritesActions(::dispatch)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private val runtime = runtimeProvider(scope, strings)
    private var stateObserver: ((FavoritesUiState) -> Unit)? = null
    private var detailObserver: ((String, ViewerSessionScope) -> Unit)? = null
    private var favoriteObserver: ((String, Boolean, Long, ViewerSessionScope) -> Unit)? = null
    private var observationVersion = 0L
    private var deliveredStateVersion = -1L
    private var deliveredState: FavoritesUiState? = null
    private var isClosed = false

    var currentState: FavoritesUiState = runtime?.state?.value ?: unavailableState(strings)
        private set

    val isConfigured: Boolean
        get() = runtime != null

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
                        is FavoritesEffect.OpenCatalogDetail -> if (
                            effect.scope == viewerSessionScopeTracker.currentScope
                        ) {
                            detailObserver?.invoke(
                                effect.listingId,
                                effect.scope,
                            )
                        }
                        is FavoritesEffect.FavoriteChanged -> if (
                            effect.scope == viewerSessionScopeTracker.currentScope
                        ) {
                            favoriteObserver?.invoke(
                                effect.listingId,
                                effect.favorited,
                                effect.clientMutationSequence,
                                effect.scope,
                            )
                        }
                    }
                }
            }
        }
    }

    fun observe(
        stateObserver: (FavoritesUiState) -> Unit,
        detailObserver: (String, ViewerSessionScope) -> Unit,
        favoriteObserver: (String, Boolean, Long, ViewerSessionScope) -> Unit,
    ) {
        if (isClosed) return
        observationVersion += 1
        this.stateObserver = stateObserver
        this.detailObserver = detailObserver
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
        detailObserver = null
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

    private fun dispatch(intent: FavoritesIntent) {
        if (isClosed) return
        val currentRuntime = runtime ?: return
        currentRuntime.dispatch(intent)
        if (intent is FavoritesIntent.ViewerContextChanged) {
            val resetState = currentRuntime.state.value
            if (resetState.viewerScope == viewerSessionScopeTracker.currentScope) {
                currentState = resetState
                publishStateIfNeeded(resetState)
            }
        }
    }

    private fun publishStateIfNeeded(state: FavoritesUiState) {
        val observer = stateObserver ?: return
        if (deliveredStateVersion == observationVersion && deliveredState == state) return
        deliveredStateVersion = observationVersion
        deliveredState = state
        observer(state)
    }
}

private fun unavailableState(strings: FavoritesStrings): FavoritesUiState = FavoritesUiState(
    isLoading = false,
    errorMessage = strings.loadFailed,
)
