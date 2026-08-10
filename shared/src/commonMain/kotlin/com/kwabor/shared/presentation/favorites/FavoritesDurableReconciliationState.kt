package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.presentation.interaction.InteractionReconciliationSignal
import com.kwabor.shared.presentation.session.ViewerSessionScope

internal class FavoritesDurableReconciliationState(
    private val stateStore: FavoritesStateStore,
    private val sessionState: FavoritesSessionState,
    private val ledger: FavoritesDurableOperationLedger,
    private val snapshots: FavoriteRemovalSnapshots,
) {
    fun prepareLocked(
        scope: ViewerSessionScope,
        additionalListingIds: List<String> = emptyList(),
    ): FavoritesDurableReconciliationRequest? {
        if (!hasExactScopeLocked(scope)) return null
        return FavoritesDurableReconciliationRequest(
            scope = scope,
            listingIds = listingIdsForRehydrationLocked(additionalListingIds),
            expectedOperationIds = ledger.pendingOperationIds(),
        )
    }

    fun applyLocked(
        request: FavoritesDurableReconciliationRequest,
        hydration: FavoritesDurableHydration,
    ): FavoritesUiState? {
        if (!hasExactScopeLocked(request.scope)) return null
        mergeHydrationLocked(request.scope, hydration)
        synchronizeRemovedListingIdsLocked()
        return stateStore.updateForScope(request.scope) { current ->
            ledger.pendingOperations().fold(current) { state, pending ->
                state.applyDurableSelection(
                    listingId = pending.listingId,
                    selected = pending.desiredSelected,
                    sessionState = sessionState,
                    snapshots = snapshots,
                )
            }.copy(
                removingListingIds = ledger.pendingRemovalIds(),
            ).withDurableStatus(scope = request.scope, ledger = ledger)
        }
    }

    fun applyWatermarksLocked(signal: InteractionReconciliationSignal, scope: ViewerSessionScope): FavoritesUiState? {
        if (!hasExactScopeLocked(scope) || !signal.scope.matches(scope)) return null
        signal.terminalWatermarks.forEach { (watermark, operationId) ->
            if (watermark.kind == InteractionKind.Favorite) {
                ledger.settle(watermark.listingId, operationId)
            }
        }
        synchronizeRemovedListingIdsLocked()
        return stateStore.updateForScope(scope) { current ->
            current.copy(
                removingListingIds = ledger.pendingRemovalIds(),
            ).withDurableStatus(scope = scope, ledger = ledger)
        }
    }

    fun settleSupersededLocked(listingId: String, operationId: Long) {
        ledger.settle(listingId, operationId)
    }

    fun mergeHydrationLocked(scope: ViewerSessionScope, hydration: FavoritesDurableHydration?) {
        val validHydration = hydration?.takeIf { candidate -> candidate.scope.matches(scope) } ?: return
        ledger.reconcile(
            expectedOperationIds = validHydration.expectedOperationIds
                .filterKeys(validHydration.requestedListingIds::contains),
            hydrated = validHydration.pending,
        )
    }

    fun synchronizeRemovedListingIdsLocked() {
        sessionState.removedListingIds.clear()
        sessionState.removedListingIds += ledger.pendingRemovalIds()
    }

    private fun hasExactScopeLocked(scope: ViewerSessionScope): Boolean = scope.isAuthenticated &&
        sessionState.activeViewerScope == scope &&
        stateStore.value.viewerScope == scope

    private fun listingIdsForRehydrationLocked(additionalListingIds: List<String>): List<String> = (
        stateStore.value.items.map(FavoriteListingItem::id) +
            snapshots.listingIds() +
            ledger.listingIds() +
            additionalListingIds
        ).map(String::trim).filter(String::isNotEmpty).distinct()
}
