package com.kwabor.android.presentation.detail

import androidx.lifecycle.ViewModel
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.detail.CatalogDetailIntent
import com.kwabor.shared.presentation.detail.CatalogDetailPresenter
import com.kwabor.shared.presentation.detail.CatalogDetailRuntime
import com.kwabor.shared.presentation.detail.CatalogDetailUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

internal class CatalogDetailViewModel internal constructor(
    private val runtime: CatalogDetailStateRuntime,
    private val coroutineScope: CoroutineScope,
) : ViewModel() {
    constructor(
        presenter: CatalogDetailPresenter,
        strings: KwaborStrings,
        coroutineScope: CoroutineScope,
        temporalTicks: Flow<Unit> = emptyFlow(),
    ) : this(
        runtime = SharedCatalogDetailStateRuntime(
            CatalogDetailRuntime(
                presenter = presenter,
                strings = strings,
                coroutineScope = coroutineScope,
                temporalTicks = temporalTicks,
            ),
        ),
        coroutineScope = coroutineScope,
    )

    val state: StateFlow<CatalogDetailUiState> = runtime.state

    fun onIntent(intent: CatalogDetailIntent) {
        runtime.dispatch(intent)
    }

    override fun onCleared() {
        runtime.close()
        coroutineScope.cancel()
        super.onCleared()
    }
}

internal interface CatalogDetailStateRuntime {
    val state: StateFlow<CatalogDetailUiState>

    fun dispatch(intent: CatalogDetailIntent)

    fun close()
}

private class SharedCatalogDetailStateRuntime(
    private val delegate: CatalogDetailRuntime,
) : CatalogDetailStateRuntime {
    override val state: StateFlow<CatalogDetailUiState> = delegate.state

    override fun dispatch(intent: CatalogDetailIntent) {
        delegate.dispatch(intent)
    }

    override fun close() {
        delegate.close()
    }
}
