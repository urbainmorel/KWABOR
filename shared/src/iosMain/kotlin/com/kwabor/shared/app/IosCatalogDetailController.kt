package com.kwabor.shared.app

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.detail.CatalogDetailOpenRequestId
import com.kwabor.shared.presentation.detail.CatalogDetailOpenRequestIdGenerator
import com.kwabor.shared.presentation.detail.CatalogDetailPresenter
import com.kwabor.shared.presentation.detail.CatalogDetailRuntime
import com.kwabor.shared.presentation.detail.CatalogDetailUiState
import com.kwabor.shared.presentation.detail.catalogDetailMinuteTicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class IosCatalogDetailActions internal constructor(
    private val dispatch: (CatalogDetailIntent) -> Unit,
) {
    private val openRequestIdGenerator = CatalogDetailOpenRequestIdGenerator()

    fun open(listingId: String): Long? {
        val openRequestId = openRequestIdGenerator.next() ?: return null
        dispatch(CatalogDetailIntent.Open(listingId, openRequestId))
        return openRequestId.value
    }

    fun openCorrelated(listingId: String, correlationSequence: Long): Long {
        val openRequestId = CatalogDetailOpenRequestId.correlated(correlationSequence)
        dispatch(CatalogDetailIntent.Open(listingId, openRequestId))
        return openRequestId.value
    }

    fun retry() {
        dispatch(CatalogDetailIntent.Retry)
    }

    fun close() {
        dispatch(CatalogDetailIntent.Close)
    }

    fun selectMedia(index: Int) {
        dispatch(CatalogDetailIntent.SelectMedia(index))
    }

    fun toggleDescription() {
        dispatch(CatalogDetailIntent.ToggleDescription)
    }
}

internal interface IosCatalogDetailRuntime {
    val state: StateFlow<CatalogDetailUiState>

    fun dispatch(intent: CatalogDetailIntent)

    fun close()
}

private class DefaultIosCatalogDetailRuntime(
    private val delegate: CatalogDetailRuntime,
) : IosCatalogDetailRuntime {
    override val state: StateFlow<CatalogDetailUiState> = delegate.state

    override fun dispatch(intent: CatalogDetailIntent) {
        delegate.dispatch(intent)
    }

    override fun close() {
        delegate.close()
    }
}

class IosCatalogDetailController private constructor(
    runtimeProvider: (CoroutineScope, KwaborStrings) -> IosCatalogDetailRuntime?,
    dispatcherProvider: DispatcherProvider,
) {
    internal constructor(
        presenter: CatalogDetailPresenter?,
        dispatcherProvider: DispatcherProvider,
    ) : this(
        runtimeProvider = { scope, strings ->
            presenter?.let { currentPresenter ->
                DefaultIosCatalogDetailRuntime(
                    CatalogDetailRuntime(
                        presenter = currentPresenter,
                        strings = strings,
                        coroutineScope = scope,
                        temporalTicks = catalogDetailMinuteTicks(),
                    ),
                )
            }
        },
        dispatcherProvider = dispatcherProvider,
    )

    internal constructor(
        runtime: IosCatalogDetailRuntime?,
        dispatcherProvider: DispatcherProvider,
    ) : this(
        runtimeProvider = { _, _ -> runtime },
        dispatcherProvider = dispatcherProvider,
    )

    val strings: KwaborStrings = stringsFor(AppLocale.French)
    val actions = IosCatalogDetailActions(::dispatch)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private val runtime = runtimeProvider(scope, strings)
    private var stateObserver: ((CatalogDetailUiState) -> Unit)? = null
    private var observationVersion = 0L
    private var deliveredStateVersion = -1L
    private var deliveredState: CatalogDetailUiState? = null
    private var isClosed = false

    var currentState: CatalogDetailUiState = runtime?.state?.value ?: CatalogDetailUiState.Closed
        private set

    val isConfigured: Boolean get() = runtime != null

    init {
        runtime?.let { currentRuntime ->
            scope.launch {
                currentRuntime.state.collect { updatedState ->
                    currentState = updatedState
                    publishStateIfNeeded(updatedState)
                }
            }
        }
    }

    fun observe(stateObserver: (CatalogDetailUiState) -> Unit) {
        if (isClosed) return
        observationVersion += 1
        this.stateObserver = stateObserver
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

    private fun dispatch(intent: CatalogDetailIntent) {
        if (isClosed) return
        val currentRuntime = runtime
        if (currentRuntime != null) {
            currentRuntime.dispatch(intent)
        } else {
            scope.launch {
                if (!isClosed) {
                    handleUnavailableRuntime(intent)
                }
            }
        }
    }

    private fun handleUnavailableRuntime(intent: CatalogDetailIntent) {
        val updatedState = when (intent) {
            is CatalogDetailIntent.Open -> CatalogDetailUiState.Failure(
                listingId = intent.listingId.trim(),
                openRequestId = requireNotNull(intent.openRequestId),
                message = strings.configurationUnavailable,
            )
            CatalogDetailIntent.Close -> CatalogDetailUiState.Closed
            CatalogDetailIntent.Retry,
            is CatalogDetailIntent.SelectMedia,
            CatalogDetailIntent.ToggleDescription,
            -> currentState
        }
        currentState = updatedState
        publishStateIfNeeded(updatedState)
    }

    private fun publishStateIfNeeded(state: CatalogDetailUiState) {
        val observer = stateObserver ?: return
        if (deliveredStateVersion == observationVersion && deliveredState == state) return
        deliveredStateVersion = observationVersion
        deliveredState = state
        observer(state)
    }
}
