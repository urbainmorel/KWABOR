package com.kwabor.shared.app

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.observability.AnalyticsContext
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.i18n.SearchStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.search.SearchEffect
import com.kwabor.shared.presentation.search.SearchIntent
import com.kwabor.shared.presentation.search.SearchPresenter
import com.kwabor.shared.presentation.search.SearchRuntime
import com.kwabor.shared.presentation.search.SearchUiState
import com.kwabor.shared.presentation.search.toSearchContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class IosSearchEffectKind {
    QuerySubmitted,
    OpenCatalogDetail,
    OpenAssistant,
}

data class IosSearchEffect(
    val kind: IosSearchEffectKind,
    val listingId: String? = null,
    val querySubmittedEvent: AnalyticsEvent? = null,
) {
    init {
        require((kind == IosSearchEffectKind.OpenCatalogDetail) == (listingId != null)) {
            "Only catalog-detail effects carry a listing id."
        }
        require((kind == IosSearchEffectKind.QuerySubmitted) == (querySubmittedEvent != null)) {
            "Only query-submitted effects carry analytics metadata."
        }
    }

    val submitsQuery: Boolean
        get() = kind == IosSearchEffectKind.QuerySubmitted

    val opensCatalogDetail: Boolean
        get() = kind == IosSearchEffectKind.OpenCatalogDetail

    val opensAssistant: Boolean
        get() = kind == IosSearchEffectKind.OpenAssistant
}

class IosSearchActions internal constructor(
    private val dispatch: (SearchIntent) -> Unit,
) {
    fun activate(exploreState: ExploreUiState) {
        dispatch(SearchIntent.Activate(exploreState.toSearchContext()))
    }

    fun updateExploreContext(exploreState: ExploreUiState) {
        dispatch(SearchIntent.UpdateContext(exploreState.toSearchContext()))
    }

    fun queryChanged(text: String) {
        dispatch(SearchIntent.QueryChanged(text))
    }

    fun selectActiveTabScope() {
        dispatch(SearchIntent.SelectScope(com.kwabor.shared.presentation.search.SearchScope.ActiveTab))
    }

    fun selectAllScope() {
        dispatch(SearchIntent.SelectScope(com.kwabor.shared.presentation.search.SearchScope.All))
    }

    fun submit() {
        dispatch(SearchIntent.Submit)
    }

    fun clear() {
        dispatch(SearchIntent.Clear)
    }

    fun close() {
        dispatch(SearchIntent.Close)
    }

    fun retry() {
        dispatch(SearchIntent.Retry)
    }

    fun refresh() {
        dispatch(SearchIntent.Refresh)
    }

    fun loadNext() {
        dispatch(SearchIntent.LoadNext)
    }

    fun openListing(listingId: String) {
        dispatch(SearchIntent.OpenListing(listingId))
    }

    fun openAssistant() {
        dispatch(SearchIntent.OpenAssistant)
    }
}

internal interface IosSearchRuntime {
    val state: StateFlow<SearchUiState>
    val effects: Flow<SearchEffect>

    fun dispatch(intent: SearchIntent)

    fun close()
}

private class DefaultIosSearchRuntime(
    private val delegate: SearchRuntime,
) : IosSearchRuntime {
    override val state: StateFlow<SearchUiState> = delegate.state
    override val effects: Flow<SearchEffect> = delegate.effects

    override fun dispatch(intent: SearchIntent) {
        delegate.dispatch(intent)
    }

    override fun close() {
        delegate.close()
    }
}

class IosSearchController private constructor(
    runtimeProvider: (CoroutineScope, SearchStrings) -> IosSearchRuntime?,
    dispatcherProvider: DispatcherProvider,
) {
    internal constructor(
        presenter: SearchPresenter?,
        dispatcherProvider: DispatcherProvider,
    ) : this(
        runtimeProvider = { scope, strings ->
            presenter?.let { currentPresenter ->
                DefaultIosSearchRuntime(
                    SearchRuntime(
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
        runtime: IosSearchRuntime?,
        dispatcherProvider: DispatcherProvider,
    ) : this(
        runtimeProvider = { _, _ -> runtime },
        dispatcherProvider = dispatcherProvider,
    )

    val strings: SearchStrings = stringsFor(AppLocale.French).search
    val actions = IosSearchActions(::dispatch)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private val runtime = runtimeProvider(scope, strings)
    private var stateObserver: ((SearchUiState) -> Unit)? = null
    private var effectObserver: ((IosSearchEffect) -> Unit)? = null
    private var observationVersion = 0L
    private var deliveredStateVersion = -1L
    private var deliveredState: SearchUiState? = null
    private var isClosed = false

    var currentState: SearchUiState = runtime?.state?.value ?: unavailableState(strings)
        private set

    val isConfigured: Boolean
        get() = runtime != null

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

    fun observe(stateObserver: (SearchUiState) -> Unit, effectObserver: (IosSearchEffect) -> Unit) {
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

    private fun dispatch(intent: SearchIntent) {
        if (!isClosed) {
            runtime?.dispatch(intent)
        }
    }

    private fun publishStateIfNeeded(state: SearchUiState) {
        val observer = stateObserver ?: return
        if (deliveredStateVersion == observationVersion && deliveredState == state) return
        deliveredStateVersion = observationVersion
        deliveredState = state
        observer(state)
    }
}

private fun unavailableState(strings: SearchStrings): SearchUiState = SearchUiState(
    errorMessage = strings.loadFailed,
)

private fun SearchEffect.toIosEffect(): IosSearchEffect = when (this) {
    is SearchEffect.QuerySubmitted -> IosSearchEffect(
        kind = IosSearchEffectKind.QuerySubmitted,
        querySubmittedEvent = AnalyticsEvent(
            name = AnalyticsEventName.SearchQuery,
            context = AnalyticsContext(displayCurrency = displayCurrency),
        ),
    )
    is SearchEffect.OpenCatalogDetail -> IosSearchEffect(
        kind = IosSearchEffectKind.OpenCatalogDetail,
        listingId = listingId,
    )
    SearchEffect.OpenAssistant -> IosSearchEffect(IosSearchEffectKind.OpenAssistant)
}
