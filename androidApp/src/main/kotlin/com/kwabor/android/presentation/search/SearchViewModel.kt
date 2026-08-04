package com.kwabor.android.presentation.search

import androidx.lifecycle.ViewModel
import com.kwabor.shared.domain.observability.AnalyticsContext
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.i18n.SearchStrings
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.search.SearchPresenter
import com.kwabor.shared.presentation.search.SearchRuntime
import com.kwabor.shared.presentation.search.SearchScope
import com.kwabor.shared.presentation.search.SearchUiState
import com.kwabor.shared.presentation.search.toSearchContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transform
import com.kwabor.shared.presentation.search.SearchEffect as SharedSearchEffect
import com.kwabor.shared.presentation.search.SearchIntent as SharedSearchIntent

internal sealed interface SearchIntent {
    fun toSharedIntent(): SharedSearchIntent

    data class Activate(val exploreState: ExploreUiState) : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.Activate(exploreState.toSearchContext())
    }

    data class UpdateExploreContext(val exploreState: ExploreUiState) : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent =
            SharedSearchIntent.UpdateContext(exploreState.toSearchContext())
    }

    data class QueryChanged(val text: String) : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.QueryChanged(text)
    }

    data class SelectScope(val scope: SearchScope) : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.SelectScope(scope)
    }

    data object Submit : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.Submit
    }

    data object Clear : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.Clear
    }

    data object Close : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.Close
    }

    data object Retry : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.Retry
    }

    data object Refresh : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.Refresh
    }

    data object LoadNext : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.LoadNext
    }

    data class OpenListing(val listingId: String) : SearchIntent {
        override fun toSharedIntent(): SharedSearchIntent = SharedSearchIntent.OpenListing(listingId)
    }
}

internal sealed interface SearchEffect {
    data class OpenCatalogDetail(val listingId: String) : SearchEffect
}

internal class SearchViewModel(
    presenter: SearchPresenter,
    strings: SearchStrings,
    private val coroutineScope: CoroutineScope,
    private val track: (AnalyticsEvent) -> Unit = {},
) : ViewModel() {
    private val runtime = SearchRuntime(
        presenter = presenter,
        strings = strings,
        coroutineScope = coroutineScope,
    )
    val state: StateFlow<SearchUiState> = runtime.state
    val effects: Flow<SearchEffect> = runtime.effects.transform { effect ->
        when (effect) {
            is SharedSearchEffect.QuerySubmitted -> track(
                AnalyticsEvent(
                    name = AnalyticsEventName.SearchQuery,
                    context = AnalyticsContext(displayCurrency = effect.displayCurrency),
                ),
            )
            is SharedSearchEffect.OpenCatalogDetail -> emit(SearchEffect.OpenCatalogDetail(effect.listingId))
            SharedSearchEffect.OpenAssistant -> Unit
        }
    }

    fun onIntent(intent: SearchIntent) {
        runtime.dispatch(intent.toSharedIntent())
    }

    override fun onCleared() {
        runtime.close()
        coroutineScope.cancel()
        super.onCleared()
    }
}
