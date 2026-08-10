package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.presentation.interaction.InteractionAccountLifecycleGeneration
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class FavoritesDirectCommitFence(
    val scope: InteractionAccountScope,
    val generation: InteractionAccountLifecycleGeneration,
)

internal suspend fun InteractionCoordinator.captureFavoritesDirectCommitFence(
    scope: InteractionAccountScope,
): FavoritesDirectCommitFence? = deliveryCommitGate.captureLifecycleGeneration(scope)?.let { generation ->
    FavoritesDirectCommitFence(scope, generation)
}

internal suspend fun InteractionCoordinator.runIfFavoritesDirectCommitCurrent(
    fence: FavoritesDirectCommitFence,
    lifecycleMutex: Mutex,
    commitLocked: () -> Unit,
): Boolean = deliveryCommitGate.runIfLifecycleGenerationCurrent(fence.scope, fence.generation) {
    lifecycleMutex.withLock(action = commitLocked)
}
