package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.presentation.interaction.InteractionAccountLifecycleGeneration
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface ExploreDirectCommitFence {
    data object NotRequired : ExploreDirectCommitFence

    data object Blocked : ExploreDirectCommitFence

    data class Captured(
        val scope: InteractionAccountScope,
        val generation: InteractionAccountLifecycleGeneration,
    ) : ExploreDirectCommitFence
}

internal suspend fun InteractionCoordinator?.captureExploreCommitFence(
    scope: ViewerSessionScope,
): ExploreDirectCommitFence {
    val coordinator = this ?: return ExploreDirectCommitFence.NotRequired
    val interactionScope = scope.toInteractionAccountScopeOrNull()
        ?: return ExploreDirectCommitFence.NotRequired
    val generation = coordinator.deliveryCommitGate.captureLifecycleGeneration(interactionScope)
        ?: return ExploreDirectCommitFence.Blocked
    return ExploreDirectCommitFence.Captured(interactionScope, generation)
}

internal suspend fun InteractionCoordinator?.runIfExploreCommitFenceCurrent(
    fence: ExploreDirectCommitFence,
    action: suspend () -> Unit,
): Boolean = when (fence) {
    ExploreDirectCommitFence.NotRequired -> {
        action()
        true
    }
    ExploreDirectCommitFence.Blocked -> false
    is ExploreDirectCommitFence.Captured -> checkNotNull(this).deliveryCommitGate.runIfLifecycleGenerationCurrent(
        scope = fence.scope,
        generation = fence.generation,
        action = action,
    )
}

internal data class ExploreDirectCommitRequest(
    val toggle: ExploreToggleRequest,
    val prepared: ExplorePreparedToggle,
    val execution: ExploreInteractionExecution,
    val isCurrentInteraction: suspend (Long, Long?) -> Boolean,
)

internal suspend fun commitExploreExecutionIfCurrent(
    coordinator: InteractionCoordinator?,
    fence: ExploreDirectCommitFence,
    interactionMutex: Mutex,
    stateStore: ExploreStateStore,
    request: ExploreDirectCommitRequest,
): ExploreUiState? {
    var committed: ExploreUiState? = null
    val currentCommit = coordinator.runIfExploreCommitFenceCurrent(fence) {
        committed = interactionMutex.withLock {
            stateStore.commitInteraction(
                ExploreInteractionCommitRequest(
                    result = request.execution.state,
                    baseline = request.prepared.baseline,
                    listingId = request.toggle.listingId,
                    kind = request.toggle.kind,
                    clientMutationSequence = request.execution.clientMutationSequence,
                    canCommit = { allowChangedFeedContext ->
                        request.isCurrentInteraction(
                            request.toggle.viewerAtRequest,
                            request.toggle.contextAtRequest.takeUnless { allowChangedFeedContext },
                        ) && (
                            allowChangedFeedContext ||
                                stateStore.value.listings.any { listing -> listing.id == request.toggle.listingId }
                            )
                    },
                ),
            )
        }
    }
    return committed.takeIf { currentCommit }
}
