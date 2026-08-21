package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.i18n.KwaborStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CatalogDetailRuntime(
    private val presenter: CatalogDetailPresenter,
    private val strings: KwaborStrings,
    coroutineScope: CoroutineScope,
    private val temporalTicks: Flow<Unit> = emptyFlow(),
) {
    private val runtimeJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(coroutineScope.coroutineContext + runtimeJob)
    private val lifecycleMutex = Mutex()
    private val intentChannel = Channel<CatalogDetailIntent>(capacity = Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<CatalogDetailUiState>(CatalogDetailUiState.Closed)
    val state: StateFlow<CatalogDetailUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var temporalJob: Job? = null
    private var sourceDetail: CatalogDetail? = null
    private var generation = 0L
    private val openRequestIdGenerator = CatalogDetailOpenRequestIdGenerator()

    init {
        runtimeScope.launch {
            for (intent in intentChannel) {
                handle(intent)
            }
        }
    }

    fun dispatch(intent: CatalogDetailIntent) {
        intentChannel.trySend(intent)
    }

    fun close() {
        intentChannel.close()
        runtimeJob.cancel()
    }

    private suspend fun handle(intent: CatalogDetailIntent) {
        when (intent) {
            is CatalogDetailIntent.Open -> startLoad(intent.listingId, intent.openRequestId)
            CatalogDetailIntent.Retry -> retry()
            CatalogDetailIntent.Close -> dismiss()
            is CatalogDetailIntent.SelectMedia -> selectMedia(intent.index)
            CatalogDetailIntent.ToggleDescription -> toggleDescription()
        }
    }

    private suspend fun startLoad(
        listingId: String,
        requestedOpenRequestId: CatalogDetailOpenRequestId?,
    ) {
        val request = lifecycleMutex.withLock {
            val openRequestId = requestedOpenRequestId ?: openRequestIdGenerator.next() ?: return@withLock null
            loadJob?.cancel()
            temporalJob?.cancel()
            temporalJob = null
            val nextGeneration = ++generation
            val normalizedListingId = listingId.trim()
            sourceDetail = null
            mutableState.value = CatalogDetailUiState.Loading(normalizedListingId, openRequestId)
            LoadRequest(
                generation = nextGeneration,
                listingId = normalizedListingId,
                openRequestId = openRequestId,
            )
        } ?: return
        loadJob = runtimeScope.launch {
            val presentation = presenter.loadPresentation(request.listingId, request.openRequestId, strings)
            lifecycleMutex.withLock {
                if (request.generation == generation && mutableState.value !is CatalogDetailUiState.Closed) {
                    sourceDetail = presentation.source
                    mutableState.value = presentation.state
                    loadJob = null
                    if (presentation.source != null) {
                        startTemporalRefresh()
                    }
                }
            }
        }
    }

    private suspend fun retry() {
        val retryRequest = lifecycleMutex.withLock {
            when (val current = mutableState.value) {
                is CatalogDetailUiState.Failure -> RetryRequest(current.listingId, current.openRequestId)
                is CatalogDetailUiState.NotFound -> RetryRequest(current.listingId, current.openRequestId)
                is CatalogDetailUiState.OfflineFailure -> RetryRequest(current.listingId, current.openRequestId)
                CatalogDetailUiState.Closed,
                is CatalogDetailUiState.Content,
                is CatalogDetailUiState.Loading,
                -> null
            }
        }
        if (retryRequest != null) {
            startLoad(retryRequest.listingId, retryRequest.openRequestId)
        }
    }

    private suspend fun dismiss() {
        lifecycleMutex.withLock {
            generation += 1
            loadJob?.cancel()
            loadJob = null
            temporalJob?.cancel()
            temporalJob = null
            sourceDetail = null
            mutableState.value = CatalogDetailUiState.Closed
        }
    }

    private suspend fun selectMedia(index: Int) {
        lifecycleMutex.withLock {
            val current = mutableState.value as? CatalogDetailUiState.Content ?: return@withLock
            if (current.model.media.isEmpty()) return@withLock
            mutableState.value = current.copy(
                selectedMediaIndex = index.coerceIn(0, current.model.media.lastIndex),
            )
        }
    }

    private suspend fun toggleDescription() {
        lifecycleMutex.withLock {
            val current = mutableState.value as? CatalogDetailUiState.Content ?: return@withLock
            mutableState.value = current.copy(isDescriptionExpanded = !current.isDescriptionExpanded)
        }
    }

    private suspend fun refreshTemporalState() {
        lifecycleMutex.withLock {
            val source = sourceDetail ?: return@withLock
            val current = mutableState.value as? CatalogDetailUiState.Content ?: return@withLock
            val refreshed = presenter.present(source, current.openRequestId, strings)
            mutableState.value = current.copy(model = refreshed.model)
        }
    }

    private fun startTemporalRefresh() {
        temporalJob?.cancel()
        temporalJob = runtimeScope.launch {
            temporalTicks.collect {
                refreshTemporalState()
            }
        }
    }
}

private data class LoadRequest(
    val generation: Long,
    val listingId: String,
    val openRequestId: CatalogDetailOpenRequestId,
)

private data class RetryRequest(
    val listingId: String,
    val openRequestId: CatalogDetailOpenRequestId,
)
