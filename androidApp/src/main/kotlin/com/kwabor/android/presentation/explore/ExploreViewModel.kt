package com.kwabor.android.presentation.explore

import androidx.lifecycle.ViewModel
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.explore.ExploreChip
import com.kwabor.shared.presentation.explore.ExploreInteractionKind
import com.kwabor.shared.presentation.explore.ExploreLoadRequest
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.ExploreTab
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.explore.initialExploreUiState
import com.kwabor.shared.presentation.explore.loadingExploreUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal sealed interface ExploreIntent {
    data class SelectTab(val tab: ExploreTab) : ExploreIntent

    data class SelectChip(val chip: ExploreChip) : ExploreIntent

    data class ToggleLike(val listingId: String) : ExploreIntent

    data class ToggleFavorite(val listingId: String) : ExploreIntent

    data object Retry : ExploreIntent

    data object ReplayPendingInteraction : ExploreIntent

    data object ContinueAsGuest : ExploreIntent
}

internal sealed interface ExploreEffect {
    data object AuthenticationRequired : ExploreEffect
}

internal class ExploreViewModel(
    private val presenter: ExplorePresenter,
    private val strings: KwaborStrings,
    private val coroutineScope: CoroutineScope,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialExploreUiState(strings))
    val state: StateFlow<ExploreUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<ExploreEffect>(capacity = Channel.BUFFERED)
    val effects: Flow<ExploreEffect> = effectChannel.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        load(ExploreLoadRequest())
    }

    fun onIntent(intent: ExploreIntent) {
        when (intent) {
            is ExploreIntent.SelectTab -> selectTab(intent.tab)
            is ExploreIntent.SelectChip -> selectChip(intent.chip)
            is ExploreIntent.ToggleLike -> toggle(intent.listingId, ExploreInteractionKind.Like)
            is ExploreIntent.ToggleFavorite -> toggle(intent.listingId, ExploreInteractionKind.Favorite)
            ExploreIntent.Retry -> load(mutableState.value.toLoadRequest())
            ExploreIntent.ReplayPendingInteraction -> replayPendingInteraction()
            ExploreIntent.ContinueAsGuest -> clearPendingAuthentication()
        }
    }

    override fun onCleared() {
        effectChannel.close()
        coroutineScope.cancel()
        super.onCleared()
    }

    private fun selectTab(tab: ExploreTab) {
        load(ExploreLoadRequest(selectedTab = tab))
    }

    private fun selectChip(chip: ExploreChip) {
        load(mutableState.value.toLoadRequest().copy(selectedChipId = chip.id))
    }

    private fun load(request: ExploreLoadRequest) {
        loadJob?.cancel()
        mutableState.value = loadingExploreUiState(strings = strings, request = request)
        loadJob = coroutineScope.launch {
            mutableState.value = presenter.load(request = request, strings = strings)
        }
    }

    private fun toggle(listingId: String, kind: ExploreInteractionKind) {
        coroutineScope.launch {
            val currentState = mutableState.value
            val updatedState = when (kind) {
                ExploreInteractionKind.Like -> presenter.toggleLike(currentState, listingId, strings)
                ExploreInteractionKind.Favorite -> presenter.toggleFavorite(currentState, listingId, strings)
            }
            mutableState.value = updatedState
            if (updatedState.pendingAuthInteraction != null) {
                effectChannel.send(ExploreEffect.AuthenticationRequired)
            }
        }
    }

    private fun replayPendingInteraction() {
        val pending = mutableState.value.pendingAuthInteraction ?: return
        toggle(listingId = pending.listingId, kind = pending.kind)
    }

    private fun clearPendingAuthentication() {
        mutableState.value = mutableState.value.copy(
            pendingAuthInteraction = null,
            interactionMessage = null,
        )
    }
}

private fun ExploreUiState.toLoadRequest(): ExploreLoadRequest = ExploreLoadRequest(
    selectedTab = selectedTab,
    selectedChipId = selectedChipId,
)
