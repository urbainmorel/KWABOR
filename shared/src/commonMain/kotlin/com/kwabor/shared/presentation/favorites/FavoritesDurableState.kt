package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionConfirmation
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.i18n.FavoritesStrings
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.session.ViewerSessionScope

internal class FavoritesDurableState(
    private val strings: FavoritesStrings,
    context: FavoritesRuntimeContext,
) {
    private val stateStore: FavoritesStateStore = context.stateStore
    private val sessionState: FavoritesSessionState = context.sessionState
    private val ledger = FavoritesDurableOperationLedger()
    private val snapshots = FavoriteRemovalSnapshots()
    val reconciliation = FavoritesDurableReconciliationState(
        stateStore = stateStore,
        sessionState = sessionState,
        ledger = ledger,
        snapshots = snapshots,
    )

    fun resetForViewerLocked() {
        ledger.reset()
        snapshots.reset()
    }

    fun preparePageRequestLocked() {
        sessionState.removedListingIds.clear()
        sessionState.removedListingIds += ledger.pendingRemovalIds()
    }

    fun projectPageResultLocked(
        scope: ViewerSessionScope,
        result: FavoritesUiState,
        hydration: FavoritesDurableHydration?,
    ): FavoritesUiState {
        if (!hasExactScopeLocked(scope)) return result
        reconciliation.mergeHydrationLocked(scope, hydration)
        reconciliation.synchronizeRemovedListingIdsLocked()
        var projected = result
        ledger.pendingOperations()
            .filter(PendingInteraction::desiredSelected)
            .forEach { pending ->
                projected = projected.applyDurableSelection(
                    listingId = pending.listingId,
                    selected = true,
                    sessionState = sessionState,
                    snapshots = snapshots,
                )
            }
        ledger.pendingOperations()
            .filterNot(PendingInteraction::desiredSelected)
            .forEach { pending ->
                projected = projected.applyDurableSelection(
                    listingId = pending.listingId,
                    selected = false,
                    sessionState = sessionState,
                    snapshots = snapshots,
                )
            }
        return projected.copy(
            items = projected.items.filterNot { item -> item.id in sessionState.removedListingIds },
            removingListingIds = ledger.pendingRemovalIds(),
        ).withDurableStatus(scope, ledger)
    }

    fun prepareRemovalLocked(listingId: String, sourceScope: ViewerSessionScope): DurableFavoriteRemovalRequest? {
        if (!hasExactScopeLocked(sourceScope)) return null
        if (listingId.isEmpty() || stateStore.value.items.none { item -> item.id == listingId }) return null
        return DurableFavoriteRemovalRequest(listingId = listingId, scope = sourceScope)
    }

    fun completeSubmitFailureLocked(request: DurableFavoriteRemovalRequest) {
        if (!hasExactScopeLocked(request.scope)) return
        stateStore.updateForScope(request.scope) { current ->
            current.copy(
                mutationMessage = strings.removeFailed,
                mutationMessageListingId = request.listingId,
                mutationMessageIsOffline = false,
                removingListingIds = current.removingListingIds - request.listingId,
            ).withDurableStatus(request.scope, ledger)
        }
    }

    fun applyPendingLocked(pending: PendingInteraction, scope: ViewerSessionScope): FavoritesUiState? {
        if (!ledger.upsert(pending)) return null
        return stateStore.updateForScope(scope) { current ->
            current.applyDurableSelection(
                listingId = pending.listingId,
                selected = pending.desiredSelected,
                sessionState = sessionState,
                snapshots = snapshots,
            ).withDurableStatus(
                scope = scope,
                ledger = ledger,
                clearMessageForListingId = pending.listingId,
            )
        }
    }

    fun applyConfirmationLocked(
        confirmation: InteractionConfirmation.Favorite,
        scope: ViewerSessionScope,
    ): DurableFavoriteMutationResult? {
        if (!ledger.canApplyTerminal(confirmation.listingId, confirmation.operationId)) return null
        if (!ledger.acceptConfirmationSequence(confirmation.listingId, confirmation.clientMutationSequence)) {
            return null
        }
        val newerPending = ledger.settle(confirmation.listingId, confirmation.operationId)
        val updated = stateStore.updateForScope(scope) { current ->
            val confirmed = current.applyDurableSelection(
                listingId = confirmation.listingId,
                selected = confirmation.favorited,
                sessionState = sessionState,
                snapshots = snapshots,
            ).copy(removingListingIds = current.removingListingIds - confirmation.listingId)
            val overlaid = newerPending?.let { pending ->
                confirmed.applyDurableSelection(
                    listingId = pending.listingId,
                    selected = pending.desiredSelected,
                    sessionState = sessionState,
                    snapshots = snapshots,
                )
            } ?: confirmed
            overlaid.withDurableStatus(
                scope = scope,
                ledger = ledger,
                clearMessageForListingId = confirmation.listingId,
            )
        } ?: return null
        if (newerPending == null) snapshots.remove(confirmation.listingId)
        return DurableFavoriteMutationResult(updated, newerPending)
    }

    fun applyRejectionLocked(
        event: InteractionCoordinatorEvent.Rejected,
        scope: ViewerSessionScope,
    ): DurableFavoriteMutationResult? {
        if (!ledger.canApplyTerminal(event.command.listingId, event.operationId)) return null
        val newerPending = ledger.settle(event.command.listingId, event.operationId)
        val updated = stateStore.updateForScope(scope) { current ->
            val selected = newerPending?.desiredSelected ?: !event.command.desiredSelected
            val reverted = current.applyDurableSelection(
                listingId = event.command.listingId,
                selected = selected,
                sessionState = sessionState,
                snapshots = snapshots,
            ).let { state ->
                if (newerPending == null) {
                    state.copy(removingListingIds = current.removingListingIds - event.command.listingId)
                } else {
                    state
                }
            }
            val showRemovalFailure = newerPending == null && !event.command.desiredSelected
            reverted.withDurableStatus(
                scope = scope,
                ledger = ledger,
                clearMessageForListingId = event.command.listingId,
                failureMessage = strings.removeFailed.takeIf { showRemovalFailure },
            )
        } ?: return null
        if (newerPending == null) snapshots.remove(event.command.listingId)
        return DurableFavoriteMutationResult(updated, newerPending)
    }

    fun mergeHydrationAndOverlayLocked(
        scope: ViewerSessionScope,
        hydration: FavoritesDurableHydration?,
        listingId: String,
    ): DurableFavoriteMutationResult? {
        if (!hasExactScopeLocked(scope)) return null
        reconciliation.mergeHydrationLocked(scope, hydration)
        reconciliation.synchronizeRemovedListingIdsLocked()
        val pending = ledger.pending(listingId)
        val updated = stateStore.updateForScope(scope) { current ->
            val overlaid = pending?.let { operation ->
                current.applyDurableSelection(
                    listingId = operation.listingId,
                    selected = operation.desiredSelected,
                    sessionState = sessionState,
                    snapshots = snapshots,
                )
            } ?: current
            overlaid.withDurableStatus(scope = scope, ledger = ledger)
        } ?: return null
        return DurableFavoriteMutationResult(updated, pending)
    }

    fun currentScopeForLocked(scope: InteractionAccountScope): ViewerSessionScope? {
        val viewerScope = sessionState.activeViewerScope
        return viewerScope.takeIf { candidate ->
            candidate.matches(scope) && stateStore.value.viewerScope == candidate
        }
    }

    fun hasExactScopeLocked(scope: ViewerSessionScope): Boolean = scope.isAuthenticated &&
        sessionState.activeViewerScope == scope &&
        stateStore.value.viewerScope == scope
}

internal data class DurableFavoriteMutationResult(
    val state: FavoritesUiState,
    val newerPending: PendingInteraction?,
)

internal data class DurableFavoriteRemovalRequest(
    val listingId: String,
    val scope: ViewerSessionScope,
)

internal fun ViewerSessionScope.matches(scope: InteractionAccountScope): Boolean =
    accountId == scope.accountId && epoch == scope.epoch

internal fun InteractionAccountScope.matches(scope: ViewerSessionScope): Boolean = scope.matches(this)
