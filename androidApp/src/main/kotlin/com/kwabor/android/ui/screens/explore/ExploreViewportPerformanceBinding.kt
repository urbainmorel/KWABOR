package com.kwabor.android.ui.screens.explore

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import com.kwabor.shared.domain.observability.PerformanceViewportState
import com.kwabor.shared.presentation.explore.ExploreUiState
import com.kwabor.shared.presentation.search.SearchUiState

data class ExploreViewportPerformanceBinding(
    val generation: Long?,
    val onCommitted: (Long, PerformanceViewportState) -> Unit,
) {
    companion object {
        val Disabled = ExploreViewportPerformanceBinding(generation = null, onCommitted = { _, _ -> })
    }
}

private data class ExploreViewportCommitToken(
    val generation: Long,
    val viewportState: PerformanceViewportState,
)

@Composable
internal fun ExploreSearchBackHandler(searchState: SearchUiState, actions: ExploreScreenActions) {
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = searchState.isActive) {
        focusManager.clearFocus()
        actions.onSearchClose()
    }
}

@Composable
internal fun Modifier.firstUsableViewportCommit(
    state: ExploreUiState,
    binding: ExploreViewportPerformanceBinding,
): Modifier {
    val token = binding.generation?.let { generation ->
        state.firstUsableViewportState?.let { viewportState ->
            ExploreViewportCommitToken(generation = generation, viewportState = viewportState)
        }
    }
    var laidOutToken by remember { mutableStateOf<ExploreViewportCommitToken?>(null) }
    LaunchedEffect(token, laidOutToken) {
        val committedToken = token?.takeIf { it == laidOutToken } ?: return@LaunchedEffect
        withFrameNanos { }
        binding.onCommitted(committedToken.generation, committedToken.viewportState)
    }
    return onGloballyPositioned { coordinates ->
        laidOutToken = token.takeIf {
            coordinates.size.width > 0 && coordinates.size.height > 0
        }
    }
}
