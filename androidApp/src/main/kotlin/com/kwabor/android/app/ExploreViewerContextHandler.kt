package com.kwabor.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.android.presentation.explore.ExploreViewModel

@Composable
internal fun ExploreViewerContextHandler(viewerId: String?, exploreViewModel: ExploreViewModel) {
    LaunchedEffect(exploreViewModel, viewerId) {
        exploreViewModel.onIntent(ExploreIntent.ViewerContextChanged(viewerId))
    }
}
