package com.kwabor.shared.presentation.guide

import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.i18n.GuideDiscoveryStrings
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

class GuideDiscoveryRuntime(
    private val presenter: GuideDiscoveryPresenter,
    private val strings: GuideDiscoveryStrings,
    coroutineScope: CoroutineScope,
) {
    private val runtimeJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(coroutineScope.coroutineContext + runtimeJob)
    private val lifecycleMutex = Mutex()
    private val intentChannel = Channel<GuideDiscoveryIntent>(capacity = Channel.UNLIMITED)
    private val effectChannel = Channel<GuideDiscoveryEffect>(capacity = Channel.BUFFERED)
    private val mutableState = MutableStateFlow(GuideDiscoveryUiState())
    val state: StateFlow<GuideDiscoveryUiState> = mutableState.asStateFlow()
    val effects: Flow<GuideDiscoveryEffect> = effectChannel.receiveAsFlow()

    private var operationJob: Job? = null
    private var generation = 0L
    private var hasStarted = false

    init {
        runtimeJob.invokeOnCompletion { effectChannel.close() }
        runtimeScope.launch {
            for (intent in intentChannel) {
                handle(intent)
            }
        }
    }

    fun dispatch(intent: GuideDiscoveryIntent) {
        intentChannel.trySend(intent)
    }

    fun close() {
        intentChannel.close()
        runtimeJob.cancel()
    }

    private suspend fun handle(intent: GuideDiscoveryIntent) {
        when (intent) {
            GuideDiscoveryIntent.Start -> startIfNeeded()
            GuideDiscoveryIntent.Retry -> startLoad(mutableState.value.filters)
            GuideDiscoveryIntent.Refresh -> startRefresh()
            GuideDiscoveryIntent.LoadNext -> startAppend()
            is GuideDiscoveryIntent.SelectCity -> selectFilter(
                mutableState.value.filters.copy(cityId = intent.cityId),
            )
            is GuideDiscoveryIntent.SelectLanguage -> selectFilter(
                mutableState.value.filters.copy(languageId = intent.languageId),
            )
            is GuideDiscoveryIntent.SelectSpecialty -> selectFilter(
                mutableState.value.filters.copy(specialtyId = intent.specialtyId),
            )
            GuideDiscoveryIntent.ClearFilters -> selectFilter(GuideDiscoveryFilters())
            is GuideDiscoveryIntent.OpenGuide -> openGuide(intent.guideId)
        }
    }

    private suspend fun startIfNeeded() {
        if (!hasStarted) {
            startLoad(GuideDiscoveryFilters())
        }
    }

    private suspend fun selectFilter(filters: GuideDiscoveryFilters) {
        if (filters != mutableState.value.filters) {
            startLoad(filters)
        }
    }

    private suspend fun startLoad(filters: GuideDiscoveryFilters) {
        lifecycleMutex.withLock {
            hasStarted = true
            operationJob?.cancel()
            val nextGeneration = ++generation
            val current = mutableState.value
            mutableState.value = current.copy(
                filters = filters,
                guides = emptyList(),
                nextCursor = null,
                resultCountLabel = "",
                isLoading = true,
                isRefreshing = false,
                isAppending = false,
                isOffline = false,
                errorMessage = null,
                refreshMessage = null,
                appendErrorMessage = null,
            )
            operationJob = runtimeScope.launch {
                val loaded = presenter.load(filters = filters, strings = strings)
                commit(nextGeneration, loaded)
            }
        }
    }

    private suspend fun startRefresh() {
        lifecycleMutex.withLock {
            val current = mutableState.value
            if (current.isLoading || current.isRefreshing || current.isAppending) return@withLock
            operationJob?.cancel()
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
            operationJob?.cancel()
            val nextGeneration = ++generation
            mutableState.value = current.copy(
                isAppending = true,
                appendErrorMessage = null,
            )
            operationJob = runtimeScope.launch {
                val appended = presenter.append(current, strings)
                commit(nextGeneration, appended)
            }
        }
    }

    private suspend fun commit(requestGeneration: Long, state: GuideDiscoveryUiState) {
        lifecycleMutex.withLock {
            if (requestGeneration == generation) {
                mutableState.value = state
                operationJob = null
            }
        }
    }

    private suspend fun openGuide(guideId: String) {
        val normalizedId = guideId.trim()
        val exists = lifecycleMutex.withLock {
            mutableState.value.guides.any { guide -> guide.id == normalizedId }
        }
        if (exists) {
            effectChannel.send(GuideDiscoveryEffect.OpenCatalogDetail(normalizedId))
        }
    }
}
