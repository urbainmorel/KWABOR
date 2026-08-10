package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.presentation.session.ViewerSessionScope

internal class FavoriteRemovalSnapshots {
    private val snapshots = mutableMapOf<String, RemovedFavoriteSnapshot>()

    fun reset() {
        snapshots.clear()
    }

    fun capture(items: List<FavoriteListingItem>, listingId: String) {
        val index = items.indexOfFirst { item -> item.id == listingId }
        if (index >= 0) snapshots[listingId] = RemovedFavoriteSnapshot(items[index], index)
    }

    fun restore(
        items: List<FavoriteListingItem>,
        listingId: String,
        filter: FavoritesFilter,
    ): List<FavoriteListingItem> {
        if (items.any { item -> item.id == listingId }) return items
        val snapshot = snapshots[listingId] ?: return items
        val expectedType = filter.toListingType()
        if (expectedType != null && snapshot.item.type != expectedType) return items
        val restored = items.toMutableList()
        restored.add(snapshot.index.coerceIn(0, restored.size), snapshot.item)
        return restored
    }

    fun remove(listingId: String) {
        snapshots.remove(listingId)
    }

    fun listingIds(): Set<String> = snapshots.keys.toSet()
}

private data class RemovedFavoriteSnapshot(
    val item: FavoriteListingItem,
    val index: Int,
)

internal fun FavoritesUiState.applyDurableSelection(
    listingId: String,
    selected: Boolean,
    sessionState: FavoritesSessionState,
    snapshots: FavoriteRemovalSnapshots,
): FavoritesUiState {
    if (!selected) {
        snapshots.capture(items, listingId)
        sessionState.removedListingIds += listingId
        return copy(
            items = items.filterNot { item -> item.id == listingId },
            removingListingIds = removingListingIds + listingId,
        )
    }
    sessionState.removedListingIds -= listingId
    return copy(
        items = snapshots.restore(items, listingId, selectedFilter),
        removingListingIds = removingListingIds - listingId,
    )
}

internal fun FavoritesUiState.withDurableStatus(
    scope: ViewerSessionScope,
    ledger: FavoritesDurableOperationLedger,
    clearMessageForListingId: String? = null,
    failureMessage: String? = null,
): FavoritesUiState {
    val clearsTargetMessage = mutationMessageListingId == clearMessageForListingId
    val legacyMutationIsOffline = mutationMessageIsOffline && !clearsTargetMessage
    val retryIds = ledger.retryListingIds()
    return copy(
        isOffline = contentIsOffline || legacyMutationIsOffline || retryIds.isNotEmpty(),
        mutationMessage = failureMessage ?: mutationMessage.takeUnless { clearsTargetMessage },
        mutationMessageListingId = when {
            failureMessage != null -> clearMessageForListingId
            clearsTargetMessage -> null
            else -> mutationMessageListingId
        },
        mutationMessageIsOffline = legacyMutationIsOffline,
        durableRetryListingIds = retryIds,
        viewerScope = scope,
    )
}
