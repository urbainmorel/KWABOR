package com.kwabor.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.kwabor.android.presentation.explore.ExploreIntent
import com.kwabor.shared.presentation.favorites.FavoritesIntent
import com.kwabor.shared.presentation.session.ViewerSessionScope

@Composable
internal fun ViewerSessionScopeHandler(
    accountId: String?,
    accountSetupComplete: Boolean,
    dependencies: HomeShellDependencies,
) {
    ViewerSessionScopeHandler(
        accountId = accountId,
        accountSetupComplete = accountSetupComplete,
        publishScope = { publishedAccountId, setupComplete ->
            dependencies.publishViewerSessionScope(publishedAccountId, setupComplete)
        },
    )
}

@Composable
internal fun ViewerSessionScopeHandler(
    accountId: String?,
    accountSetupComplete: Boolean,
    publishScope: (String?, Boolean) -> Unit,
) {
    val currentPublisher by rememberUpdatedState(publishScope)
    LaunchedEffect(accountId, accountSetupComplete) {
        currentPublisher(accountId, accountSetupComplete)
    }
}

internal fun HomeShellDependencies.publishViewerSessionScope(
    accountId: String?,
    accountSetupComplete: Boolean,
): ViewerSessionScope {
    val scope = viewerSessionScopeTracker.update(accountId, accountSetupComplete)
    exploreViewModel.onIntent(ExploreIntent.ViewerContextChanged(scope))
    favoritesViewModel.onIntent(FavoritesIntent.ViewerContextChanged(scope))
    return scope
}
