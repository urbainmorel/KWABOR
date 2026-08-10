package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionConfirmation
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionSubmitOutcome
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.i18n.FavoritesStrings
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.interaction.InteractionReconciliationSignal
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val FAVORITES_RUNTIME_HYDRATION_WINDOW_SIZE = 1_000

internal class FavoritesDurableInteractions(
    private val coordinator: InteractionCoordinator,
    strings: FavoritesStrings,
    context: FavoritesRuntimeContext,
) {
    private val lifecycleMutex: Mutex = context.lifecycleMutex
    private val sessionState: FavoritesSessionState = context.sessionState
    private val durableState = FavoritesDurableState(strings, context)
    private val reconciliationBarrier = FavoritesDurableReconciliationBarrier(
        coordinator = coordinator,
        lifecycleMutex = lifecycleMutex,
        durableState = durableState,
        hydrate = ::hydrate,
        refreshActionLocked = { pageCoordinator ->
            refreshOrMarkDirtyLocked(sessionState, pageCoordinator)
        },
    )
    private val supersededReconciler = FavoritesSupersededReconciler(
        coordinator = coordinator,
        lifecycleMutex = lifecycleMutex,
        durableState = durableState,
        hydrate = ::hydrate,
        resolveActionLocked = { request ->
            request.pending?.let { pending ->
                actionForPendingLocked(pending, request.scope, request.state, request.pageCoordinator)
            } ?: refreshOrMarkDirtyLocked(sessionState, request.pageCoordinator)
        },
    )

    fun resetForViewerLocked() {
        durableState.resetForViewerLocked()
    }

    fun preparePageRequestLocked() {
        durableState.preparePageRequestLocked()
    }

    fun onScreenAppeared() {
        coordinator.onScreenAppeared()
    }

    fun retryManually(scope: ViewerSessionScope) {
        if (scope.isAuthenticated) coordinator.retryManually(scope.toInteractionScope())
    }

    suspend fun hydratePage(scope: ViewerSessionScope, state: FavoritesUiState): FavoritesDurableHydration? {
        val request = lifecycleMutex.withLock {
            durableState.reconciliation.prepareLocked(
                scope = scope,
                additionalListingIds = state.items.map(FavoriteListingItem::id),
            )
        } ?: return null
        return hydrate(request)
    }

    fun projectPageResultLocked(
        scope: ViewerSessionScope,
        result: FavoritesUiState,
        hydration: FavoritesDurableHydration?,
    ): FavoritesUiState = durableState.projectPageResultLocked(scope, result, hydration)

    suspend fun removeFavorite(
        rawListingId: String,
        sourceScope: ViewerSessionScope,
        pageCoordinator: FavoritesPageCoordinator,
    ) {
        val request = lifecycleMutex.withLock {
            durableState.prepareRemovalLocked(rawListingId.trim(), sourceScope)
        } ?: return
        val interactionScope = request.scope.toInteractionScope()
        val commitFence = coordinator.captureFavoritesDirectCommitFence(interactionScope) ?: return
        when (
            val result = coordinator.submit(
                expectedScope = interactionScope,
                listingId = request.listingId,
                kind = InteractionKind.Favorite,
                desiredSelected = false,
            )
        ) {
            is DomainResult.Failure -> coordinator.runIfFavoritesDirectCommitCurrent(
                commitFence,
                lifecycleMutex,
            ) {
                durableState.completeSubmitFailureLocked(request)
            }
            is DomainResult.Success -> completeSubmit(request, result.value, commitFence, pageCoordinator)
        }
    }

    suspend fun handleEvent(event: InteractionCoordinatorEvent, pageCoordinator: FavoritesPageCoordinator) {
        if (!event.hasConsistentFavoritePayload()) return
        when (reconciliationBarrier.validatePendingEvent(coordinator, event, pageCoordinator)) {
            FavoritesPendingEventValidation.Continue -> Unit
            FavoritesPendingEventValidation.Stop -> return
        }
        if (event is InteractionCoordinatorEvent.Superseded) {
            supersededReconciler.reconcile(event.toSupersededRequest(pageCoordinator))
            return
        }
        var action: FavoritesPageAction = FavoritesPageAction.None
        val committed = coordinator.deliveryCommitGate.runIfEventDeliveryValid(event) {
            action = when (event) {
                is InteractionCoordinatorEvent.Queued -> applyPendingEvent(
                    event.pending,
                    event.scope,
                    pageCoordinator,
                )
                is InteractionCoordinatorEvent.Retrying -> applyPendingEvent(
                    event.pending,
                    event.scope,
                    pageCoordinator,
                )
                is InteractionCoordinatorEvent.Confirmed -> applyConfirmationEvent(event, pageCoordinator)
                is InteractionCoordinatorEvent.Rejected -> applyRejectedEvent(event, pageCoordinator)
                is InteractionCoordinatorEvent.Superseded -> FavoritesPageAction.None
            }
        }
        if (committed) pageCoordinator.runAction(action)
    }

    suspend fun handleReconciliation(
        signal: InteractionReconciliationSignal,
        pageCoordinator: FavoritesPageCoordinator,
    ): Boolean = reconciliationBarrier.handle(signal, pageCoordinator)

    private suspend fun completeSubmit(
        request: DurableFavoriteRemovalRequest,
        outcome: InteractionSubmitOutcome,
        commitFence: FavoritesDirectCommitFence,
        pageCoordinator: FavoritesPageCoordinator,
    ) {
        when (outcome) {
            is InteractionSubmitOutcome.Queued -> {
                var action: FavoritesPageAction? = null
                val committed = coordinator.runIfFavoritesDirectCommitCurrent(
                    commitFence,
                    lifecycleMutex,
                ) {
                    if (outcome.isValidFor(request) && durableState.hasExactScopeLocked(request.scope)) {
                        val updated = durableState.applyPendingLocked(outcome.pending, request.scope)
                        action = actionForPendingLocked(outcome.pending, request.scope, updated, pageCoordinator)
                    }
                }
                val committedAction = action ?: return
                if (committed) pageCoordinator.runAction(committedAction)
            }
            is InteractionSubmitOutcome.Superseded -> if (outcome.command.matches(request)) {
                supersededReconciler.reconcile(
                    FavoritesSupersededRequest(
                        scope = request.scope,
                        listingId = request.listingId,
                        operationId = outcome.operationId,
                        pageCoordinator = pageCoordinator,
                        lifecycleGeneration = commitFence.generation,
                    ),
                )
            }
        }
    }

    private suspend fun applyPendingEvent(
        pending: PendingInteraction,
        interactionScope: InteractionAccountScope,
        pageCoordinator: FavoritesPageCoordinator,
    ): FavoritesPageAction = lifecycleMutex.withLock {
        val scope = durableState.currentScopeForLocked(interactionScope)
            ?: return@withLock FavoritesPageAction.None
        val updated = durableState.applyPendingLocked(pending, scope)
        actionForPendingLocked(pending, scope, updated, pageCoordinator)
    }

    private suspend fun applyConfirmationEvent(
        event: InteractionCoordinatorEvent.Confirmed,
        pageCoordinator: FavoritesPageCoordinator,
    ): FavoritesPageAction {
        val confirmation = event.confirmation as InteractionConfirmation.Favorite
        return lifecycleMutex.withLock {
            val scope = durableState.currentScopeForLocked(event.scope)
                ?: return@withLock FavoritesPageAction.None
            val mutation = durableState.applyConfirmationLocked(confirmation, scope)
                ?: return@withLock FavoritesPageAction.None
            mutation.newerPending?.let { pending ->
                actionForPendingLocked(pending, scope, mutation.state, pageCoordinator)
            } ?: if (confirmation.favorited) {
                refreshOrMarkDirtyLocked(sessionState, pageCoordinator)
            } else {
                removalBackfillLocked(scope, mutation.state, pageCoordinator)
            }
        }
    }

    private suspend fun applyRejectedEvent(
        event: InteractionCoordinatorEvent.Rejected,
        pageCoordinator: FavoritesPageCoordinator,
    ): FavoritesPageAction = lifecycleMutex.withLock {
        val scope = durableState.currentScopeForLocked(event.scope)
            ?: return@withLock FavoritesPageAction.None
        val mutation = durableState.applyRejectionLocked(event, scope)
            ?: return@withLock FavoritesPageAction.None
        mutation.newerPending?.let { pending ->
            actionForPendingLocked(pending, scope, mutation.state, pageCoordinator)
        } ?: refreshOrMarkDirtyLocked(sessionState, pageCoordinator)
    }

    private suspend fun hydrate(request: FavoritesDurableReconciliationRequest): FavoritesDurableHydration? {
        if (!request.scope.isAuthenticated) return null
        val interactionScope = request.scope.toInteractionScope()
        val pending = mutableListOf<PendingInteraction>()
        for (window in request.listingIds.chunked(FAVORITES_RUNTIME_HYDRATION_WINDOW_SIZE)) {
            val hydration = when (
                val result = coordinator.hydrate(
                    expectedScope = interactionScope,
                    listingIds = window,
                )
            ) {
                is DomainResult.Failure -> return null
                is DomainResult.Success -> result.value
            }
            if (!hydration.scope.matches(request.scope)) return null
            pending += hydration.pending
        }
        return FavoritesDurableHydration(
            scope = interactionScope,
            pending = pending,
            requestedListingIds = request.listingIds.toSet(),
            expectedOperationIds = request.expectedOperationIds,
        )
    }
}

private enum class FavoritesPendingEventValidation {
    Continue,
    Stop,
}

private suspend fun FavoritesDurableReconciliationBarrier.validatePendingEvent(
    coordinator: InteractionCoordinator,
    event: InteractionCoordinatorEvent,
    pageCoordinator: FavoritesPageCoordinator,
): FavoritesPendingEventValidation {
    var shouldRevalidate = false
    val valid = coordinator.deliveryCommitGate.runIfEventDeliveryValid(event) {
        shouldRevalidate = shouldRevalidatePendingEvent(event)
    }
    if (!valid) return FavoritesPendingEventValidation.Stop
    if (!shouldRevalidate) return FavoritesPendingEventValidation.Continue
    if (!revalidatePendingEvent(event, pageCoordinator)) {
        coordinator.deliveryCommitGate.requestReconciliationFor(event)
    }
    return FavoritesPendingEventValidation.Stop
}

private fun InteractionCoordinatorEvent.Superseded.toSupersededRequest(
    pageCoordinator: FavoritesPageCoordinator,
): FavoritesSupersededRequest = FavoritesSupersededRequest(
    scope = scope.toViewerScope(),
    listingId = command.listingId,
    operationId = operationId,
    pageCoordinator = pageCoordinator,
    deliveryEvent = this,
)

private fun InteractionAccountScope.toViewerScope(): ViewerSessionScope =
    ViewerSessionScope(accountId = accountId, epoch = epoch)

private fun actionForPendingLocked(
    pending: PendingInteraction,
    scope: ViewerSessionScope,
    state: FavoritesUiState?,
    pageCoordinator: FavoritesPageCoordinator,
): FavoritesPageAction {
    if (state == null || pending.desiredSelected) return FavoritesPageAction.None
    return removalBackfillLocked(scope, state, pageCoordinator)
}

private fun removalBackfillLocked(
    scope: ViewerSessionScope,
    state: FavoritesUiState,
    pageCoordinator: FavoritesPageCoordinator,
): FavoritesPageAction = pageCoordinator
    .registerRemovalBackfillLocked(scope, state)
    ?.let(FavoritesPageAction::Append)
    ?: FavoritesPageAction.None

private fun refreshOrMarkDirtyLocked(
    sessionState: FavoritesSessionState,
    pageCoordinator: FavoritesPageCoordinator,
): FavoritesPageAction {
    if (sessionState.isScreenVisible) return FavoritesPageAction.Refresh
    pageCoordinator.markExternalAdditionDirtyLocked()
    return FavoritesPageAction.None
}
