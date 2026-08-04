package com.kwabor.shared.presentation.search

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.search.SearchQuery
import com.kwabor.shared.i18n.SearchStrings
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

class SearchRuntime(
    private val presenter: SearchPresenter,
    private val strings: SearchStrings,
    coroutineScope: CoroutineScope,
) {
    private val runtimeJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(coroutineScope.coroutineContext + runtimeJob)
    private val lifecycleMutex = Mutex()
    private val intentChannel = Channel<SearchIntent>(capacity = Channel.UNLIMITED)
    private val effectChannel = Channel<SearchEffect>(capacity = Channel.BUFFERED)
    private val mutableState = MutableStateFlow(SearchUiState())
    private val intentHandler = IntentHandler()
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    val effects: Flow<SearchEffect> = effectChannel.receiveAsFlow()

    private var operationJob: Job? = null
    private var generation = 0L

    init {
        runtimeJob.invokeOnCompletion { effectChannel.close() }
        runtimeScope.launch {
            for (intent in intentChannel) {
                intentHandler.handle(intent)
            }
        }
    }

    fun dispatch(intent: SearchIntent) {
        intentChannel.trySend(intent)
    }

    fun close() {
        intentChannel.close()
        runtimeJob.cancel()
    }

    private suspend fun activate(context: SearchContext) {
        lifecycleMutex.withLock {
            mutableState.value = mutableState.value.copy(context = context, isActive = true)
        }
    }

    private suspend fun updateContext(context: SearchContext) {
        val shouldReload = lifecycleMutex.withLock {
            val current = mutableState.value
            val contextChanged = current.context != context
            mutableState.value = current.copy(context = context)
            contextChanged && current.hasSubmittedQuery
        }
        if (shouldReload) startLoad()
    }

    private suspend fun updateQuery(text: String) {
        lifecycleMutex.withLock {
            val current = mutableState.value
            if (text == current.queryText) return@withLock
            cancelOperationLocked()
            mutableState.value = current.copy(
                queryText = text,
                submittedQueryText = null,
                listings = emptyList(),
                nextCursor = null,
                resultSource = null,
                networkUnavailable = false,
                resultCountLabel = "",
                isLoading = false,
                isRefreshing = false,
                isAppending = false,
                queryErrorMessage = null,
                errorMessage = null,
                refreshMessage = null,
                appendErrorMessage = null,
            )
        }
    }

    private suspend fun selectScope(scope: SearchScope) {
        val shouldReload = lifecycleMutex.withLock {
            val current = mutableState.value
            if (scope == current.scope) return@withLock false
            mutableState.value = current.copy(scope = scope)
            current.hasSubmittedQuery
        }
        if (shouldReload) startLoad()
    }

    private suspend fun submit() {
        val submission = lifecycleMutex.withLock {
            val current = mutableState.value
            val canonicalQuery = when (
                val result = SearchQuery.from(current.queryText, current.context.filtersFor(current.scope))
            ) {
                is DomainResult.Success -> result.value.text
                is DomainResult.Failure -> null
            }
            PendingSearchSubmission(
                canonicalQuery = canonicalQuery,
                displayCurrency = current.context.currency,
            )
        }
        val submitted = startLoad(canonicalSubmittedQuery = submission.canonicalQuery)
        if (submitted && submission.canonicalQuery != null) {
            effectChannel.send(SearchEffect.QuerySubmitted(submission.displayCurrency))
        }
    }

    private suspend fun startLoad(canonicalSubmittedQuery: String? = null): Boolean = lifecycleMutex.withLock {
        val current = mutableState.value
        if (!current.isActive) return@withLock false
        cancelOperationLocked()
        val nextGeneration = ++generation
        val loadingState = current.copy(
            queryText = canonicalSubmittedQuery ?: current.queryText,
            submittedQueryText = canonicalSubmittedQuery ?: current.submittedQueryText,
            listings = emptyList(),
            nextCursor = null,
            resultSource = null,
            networkUnavailable = false,
            resultCountLabel = "",
            isLoading = true,
            isRefreshing = false,
            isAppending = false,
            queryErrorMessage = null,
            errorMessage = null,
            refreshMessage = null,
            appendErrorMessage = null,
        )
        mutableState.value = loadingState
        operationJob = runtimeScope.launch {
            val loaded = presenter.submit(loadingState, strings)
            commit(nextGeneration, loaded)
        }
        true
    }

    private suspend fun startRefresh() {
        lifecycleMutex.withLock {
            val current = mutableState.value
            if (!current.hasSubmittedQuery) return@withLock
            if (current.isLoading || current.isRefreshing) return@withLock
            if (current.isAppending) return@withLock
            cancelOperationLocked()
            val nextGeneration = ++generation
            mutableState.value = current.copy(
                isRefreshing = true,
                refreshMessage = null,
                appendErrorMessage = null,
            )
            operationJob = runtimeScope.launch {
                val refreshed = presenter.refresh(current, strings)
                commit(nextGeneration, refreshed)
            }
        }
    }

    private suspend fun startAppend() {
        lifecycleMutex.withLock {
            val current = mutableState.value
            if (!current.canLoadMore) return@withLock
            cancelOperationLocked()
            val nextGeneration = ++generation
            mutableState.value = current.copy(isAppending = true, appendErrorMessage = null)
            operationJob = runtimeScope.launch {
                val appended = presenter.append(current, strings)
                commit(nextGeneration, appended)
            }
        }
    }

    private suspend fun clear(keepActive: Boolean) {
        lifecycleMutex.withLock {
            val context = mutableState.value.context
            val scope = mutableState.value.scope
            cancelOperationLocked()
            mutableState.value = SearchUiState(
                context = context,
                scope = scope,
                isActive = keepActive,
            )
        }
    }

    private suspend fun openListing(listingId: String) {
        val normalizedId = listingId.trim()
        val exists = lifecycleMutex.withLock {
            mutableState.value.listings.any { listing -> listing.id == normalizedId }
        }
        if (exists) {
            effectChannel.send(SearchEffect.OpenCatalogDetail(normalizedId))
        }
    }

    private suspend fun commit(requestGeneration: Long, state: SearchUiState) {
        lifecycleMutex.withLock {
            if (requestGeneration == generation) {
                mutableState.value = state
                operationJob = null
            }
        }
    }

    private fun cancelOperationLocked() {
        operationJob?.cancel()
        operationJob = null
        generation += 1
    }

    private inner class IntentHandler {
        suspend fun handle(intent: SearchIntent) {
            when (intent) {
                is SearchIntent.Activate -> activate(intent.context)
                is SearchIntent.UpdateContext -> updateContext(intent.context)
                is SearchIntent.QueryChanged -> updateQuery(intent.text)
                is SearchIntent.SelectScope -> selectScope(intent.scope)
                is SearchIntent.OpenListing -> openListing(intent.listingId)
                SearchIntent.Submit,
                SearchIntent.Clear,
                SearchIntent.Close,
                SearchIntent.Retry,
                SearchIntent.Refresh,
                SearchIntent.LoadNext,
                SearchIntent.OpenAssistant,
                -> handleAction(intent)
            }
        }

        private suspend fun handleAction(intent: SearchIntent) {
            when (intent) {
                SearchIntent.Submit -> submit()
                SearchIntent.Clear -> clear(keepActive = true)
                SearchIntent.Close -> clear(keepActive = false)
                SearchIntent.Retry -> startLoad()
                SearchIntent.Refresh -> startRefresh()
                SearchIntent.LoadNext -> startAppend()
                SearchIntent.OpenAssistant -> effectChannel.send(SearchEffect.OpenAssistant)
                is SearchIntent.Activate,
                is SearchIntent.UpdateContext,
                is SearchIntent.QueryChanged,
                is SearchIntent.SelectScope,
                is SearchIntent.OpenListing,
                -> error(PARAMETERIZED_INTENT_AS_ACTION_ERROR)
            }
        }
    }
}

private data class PendingSearchSubmission(
    val canonicalQuery: String?,
    val displayCurrency: KwaborCurrency,
)

private const val PARAMETERIZED_INTENT_AS_ACTION_ERROR =
    "A parameterized search intent cannot be handled as an action."
