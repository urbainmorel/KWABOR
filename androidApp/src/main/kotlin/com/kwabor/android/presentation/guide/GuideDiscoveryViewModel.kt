package com.kwabor.android.presentation.guide

import androidx.lifecycle.ViewModel
import com.kwabor.shared.i18n.GuideDiscoveryStrings
import com.kwabor.shared.presentation.guide.GuideDiscoveryEffect
import com.kwabor.shared.presentation.guide.GuideDiscoveryIntent
import com.kwabor.shared.presentation.guide.GuideDiscoveryPresenter
import com.kwabor.shared.presentation.guide.GuideDiscoveryRuntime
import com.kwabor.shared.presentation.guide.GuideDiscoveryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

internal class GuideDiscoveryViewModel(
    presenter: GuideDiscoveryPresenter,
    strings: GuideDiscoveryStrings,
    private val coroutineScope: CoroutineScope,
) : ViewModel() {
    private val runtime = GuideDiscoveryRuntime(
        presenter = presenter,
        strings = strings,
        coroutineScope = coroutineScope,
    )

    val state: StateFlow<GuideDiscoveryUiState> = runtime.state
    val effects: Flow<GuideDiscoveryEffect> = runtime.effects

    fun onIntent(intent: GuideDiscoveryIntent) {
        runtime.dispatch(intent)
    }

    override fun onCleared() {
        runtime.close()
        coroutineScope.cancel()
        super.onCleared()
    }
}
