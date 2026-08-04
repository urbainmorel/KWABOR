package com.kwabor.shared.app

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.GuideDiscoveryStrings
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.guide.GuideDiscoveryEffect
import com.kwabor.shared.presentation.guide.GuideDiscoveryIntent
import com.kwabor.shared.presentation.guide.GuideDiscoveryPresenter
import com.kwabor.shared.presentation.guide.GuideDiscoveryRuntime
import com.kwabor.shared.presentation.guide.GuideDiscoveryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class IosGuideDiscoveryActions internal constructor(
    private val dispatch: (GuideDiscoveryIntent) -> Unit,
) {
    fun start() {
        dispatch(GuideDiscoveryIntent.Start)
    }

    fun retry() {
        dispatch(GuideDiscoveryIntent.Retry)
    }

    fun refresh() {
        dispatch(GuideDiscoveryIntent.Refresh)
    }

    fun loadNext() {
        dispatch(GuideDiscoveryIntent.LoadNext)
    }

    fun selectCity(cityId: String?) {
        dispatch(GuideDiscoveryIntent.SelectCity(cityId))
    }

    fun selectLanguage(languageId: String?) {
        dispatch(GuideDiscoveryIntent.SelectLanguage(languageId))
    }

    fun selectSpecialty(specialtyId: String?) {
        dispatch(GuideDiscoveryIntent.SelectSpecialty(specialtyId))
    }

    fun clearFilters() {
        dispatch(GuideDiscoveryIntent.ClearFilters)
    }

    fun openGuide(guideId: String) {
        dispatch(GuideDiscoveryIntent.OpenGuide(guideId))
    }
}

internal interface IosGuideDiscoveryRuntime {
    val state: StateFlow<GuideDiscoveryUiState>
    val effects: Flow<GuideDiscoveryEffect>

    fun dispatch(intent: GuideDiscoveryIntent)

    fun close()
}

private class DefaultIosGuideDiscoveryRuntime(
    private val delegate: GuideDiscoveryRuntime,
) : IosGuideDiscoveryRuntime {
    override val state: StateFlow<GuideDiscoveryUiState> = delegate.state
    override val effects: Flow<GuideDiscoveryEffect> = delegate.effects

    override fun dispatch(intent: GuideDiscoveryIntent) {
        delegate.dispatch(intent)
    }

    override fun close() {
        delegate.close()
    }
}

class IosGuideDiscoveryController private constructor(
    runtimeProvider: (CoroutineScope, GuideDiscoveryStrings) -> IosGuideDiscoveryRuntime?,
    dispatcherProvider: DispatcherProvider,
) {
    internal constructor(
        presenter: GuideDiscoveryPresenter?,
        dispatcherProvider: DispatcherProvider,
    ) : this(
        runtimeProvider = { scope, strings ->
            presenter?.let { currentPresenter ->
                DefaultIosGuideDiscoveryRuntime(
                    GuideDiscoveryRuntime(
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
        runtime: IosGuideDiscoveryRuntime?,
        dispatcherProvider: DispatcherProvider,
    ) : this(
        runtimeProvider = { _, _ -> runtime },
        dispatcherProvider = dispatcherProvider,
    )

    val strings: GuideDiscoveryStrings = stringsFor(AppLocale.French).guideDiscovery
    val actions = IosGuideDiscoveryActions(::dispatch)
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private val runtime = runtimeProvider(scope, strings)
    private var stateObserver: ((GuideDiscoveryUiState) -> Unit)? = null
    private var detailObserver: ((String) -> Unit)? = null
    private var observationVersion = 0L
    private var deliveredStateVersion = -1L
    private var deliveredState: GuideDiscoveryUiState? = null
    private var isClosed = false

    var currentState: GuideDiscoveryUiState = runtime?.state?.value ?: unavailableState(strings)
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
                    when (effect) {
                        is GuideDiscoveryEffect.OpenCatalogDetail -> detailObserver?.invoke(effect.listingId)
                    }
                }
            }
        }
    }

    fun observe(stateObserver: (GuideDiscoveryUiState) -> Unit, detailObserver: (String) -> Unit) {
        if (isClosed) return
        observationVersion += 1
        this.stateObserver = stateObserver
        this.detailObserver = detailObserver
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

    private fun dispatch(intent: GuideDiscoveryIntent) {
        if (!isClosed) {
            runtime?.dispatch(intent)
        }
    }

    private fun publishStateIfNeeded(state: GuideDiscoveryUiState) {
        val observer = stateObserver ?: return
        if (deliveredStateVersion == observationVersion && deliveredState == state) return
        deliveredStateVersion = observationVersion
        deliveredState = state
        observer(state)
    }
}

private fun unavailableState(strings: GuideDiscoveryStrings): GuideDiscoveryUiState = GuideDiscoveryUiState(
    isLoading = false,
    errorMessage = strings.loadFailed,
)
