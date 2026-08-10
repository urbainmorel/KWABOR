package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.presentation.interaction.InteractionAccountLifecycleGeneration
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FavoritesSupersededReconciler(
    private val coordinator: InteractionCoordinator,
    private val lifecycleMutex: Mutex,
    private val durableState: FavoritesDurableState,
    private val hydrate: suspend (FavoritesDurableReconciliationRequest) -> FavoritesDurableHydration?,
    private val resolveActionLocked: (FavoritesSupersededActionRequest) -> FavoritesPageAction,
) {
    suspend fun reconcile(request: FavoritesSupersededRequest) {
        val interactionScope = request.scope.toInteractionScope()
        val generation = request.lifecycleGeneration
            ?: coordinator.deliveryCommitGate.captureLifecycleGeneration(interactionScope)
            ?: return
        val hydrationRequest = prepare(request, interactionScope, generation) ?: return
        val hydration = hydrate(hydrationRequest)
        val action = commit(request, interactionScope, generation, hydration) ?: return
        request.pageCoordinator.runAction(action)
    }

    private suspend fun prepare(
        request: FavoritesSupersededRequest,
        interactionScope: InteractionAccountScope,
        generation: InteractionAccountLifecycleGeneration,
    ): FavoritesDurableReconciliationRequest? {
        var hydrationRequest: FavoritesDurableReconciliationRequest? = null
        val accepted = runIfCurrent(request, interactionScope, generation) {
            hydrationRequest = lifecycleMutex.withLock {
                if (!durableState.hasExactScopeLocked(request.scope)) return@withLock null
                durableState.reconciliation.settleSupersededLocked(request.listingId, request.operationId)
                durableState.reconciliation.prepareLocked(request.scope, listOf(request.listingId))
            }
        }
        return hydrationRequest.takeIf { accepted }
    }

    private suspend fun commit(
        request: FavoritesSupersededRequest,
        interactionScope: InteractionAccountScope,
        generation: InteractionAccountLifecycleGeneration,
        hydration: FavoritesDurableHydration?,
    ): FavoritesPageAction? {
        var action: FavoritesPageAction? = null
        val accepted = runIfCurrent(request, interactionScope, generation) {
            action = lifecycleMutex.withLock {
                val mutation = durableState.mergeHydrationAndOverlayLocked(
                    request.scope,
                    hydration,
                    request.listingId,
                ) ?: return@withLock null
                resolveActionLocked(
                    FavoritesSupersededActionRequest(
                        pending = mutation.newerPending,
                        scope = request.scope,
                        state = mutation.state,
                        pageCoordinator = request.pageCoordinator,
                    ),
                )
            }
        }
        return action.takeIf { accepted }
    }

    private suspend fun runIfCurrent(
        request: FavoritesSupersededRequest,
        interactionScope: InteractionAccountScope,
        generation: InteractionAccountLifecycleGeneration,
        action: suspend () -> Unit,
    ): Boolean = request.deliveryEvent?.let { event ->
        coordinator.deliveryCommitGate.runIfEventDeliveryValid(event, action)
    } ?: coordinator.deliveryCommitGate.runIfLifecycleGenerationCurrent(interactionScope, generation, action)
}

internal data class FavoritesSupersededRequest(
    val scope: ViewerSessionScope,
    val listingId: String,
    val operationId: Long,
    val pageCoordinator: FavoritesPageCoordinator,
    val deliveryEvent: InteractionCoordinatorEvent? = null,
    val lifecycleGeneration: InteractionAccountLifecycleGeneration? = null,
)

internal data class FavoritesSupersededActionRequest(
    val pending: PendingInteraction?,
    val scope: ViewerSessionScope,
    val state: FavoritesUiState?,
    val pageCoordinator: FavoritesPageCoordinator,
)
