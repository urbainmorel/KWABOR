package com.kwabor.shared.presentation.explore

internal fun ExploreUiState.mergeFeedRuntime(
    current: ExploreUiState,
    changedInteractionKeys: Set<ExploreInteractionRevisionKey>,
    interactionOverridesByListingId: Map<String, ExploreListingItem>,
    confirmedLikeStatesByListingId: Map<String, ExploreConfirmedLikeState>,
    confirmedFavoriteStatesByListingId: Map<String, Boolean>,
): ExploreUiState {
    val mergeContext = ExploreFeedInteractionMergeContext(
        currentListingsById = current.listings.associateBy(ExploreListingItem::id),
        queuedInteractions = current.queuedInteractions,
        changedInteractionKeys = changedInteractionKeys,
        interactionOverridesByListingId = interactionOverridesByListingId,
        confirmedLikeStatesByListingId = confirmedLikeStatesByListingId,
        confirmedFavoriteStatesByListingId = confirmedFavoriteStatesByListingId,
    )
    val visiblePendingInteraction = current.pendingAuthInteraction?.takeIf { pending ->
        listings.any { listing -> listing.id == pending.listingId }
    }
    return copy(
        listings = listings.map(mergeContext::mergeListing),
        isOffline = contentIsOffline || current.queuedInteractions.hasNetworkRetry(),
        isLocalCacheUnavailable = isLocalCacheUnavailable || current.isLocalCacheUnavailable,
        isCitySelectorOpen = current.isCitySelectorOpen,
        isLocating = current.isLocating,
        locationMessage = current.locationMessage,
        interactionMessage = current.visibleInteractionMessage(visiblePendingInteraction),
        pendingAuthInteraction = visiblePendingInteraction,
        queuedInteractions = current.queuedInteractions,
    )
}

private data class ExploreFeedInteractionMergeContext(
    val currentListingsById: Map<String, ExploreListingItem>,
    val queuedInteractions: List<QueuedExploreInteraction>,
    val changedInteractionKeys: Set<ExploreInteractionRevisionKey>,
    val interactionOverridesByListingId: Map<String, ExploreListingItem>,
    val confirmedLikeStatesByListingId: Map<String, ExploreConfirmedLikeState>,
    val confirmedFavoriteStatesByListingId: Map<String, Boolean>,
) {
    fun mergeListing(incoming: ExploreListingItem): ExploreListingItem {
        val visible = currentListingsById[incoming.id] ?: interactionOverridesByListingId[incoming.id]
        return incoming
            .mergeLike(
                visible = visible,
                confirmed = confirmedLikeStatesByListingId[incoming.id],
                pending = queuedInteractions.forKey(incoming.id, ExploreInteractionKind.Like),
                changed = interactionChanged(incoming.id, ExploreInteractionKind.Like),
            ).mergeFavorite(
                visible = visible,
                confirmed = confirmedFavoriteStatesByListingId[incoming.id],
                pending = queuedInteractions.forKey(incoming.id, ExploreInteractionKind.Favorite),
                changed = interactionChanged(incoming.id, ExploreInteractionKind.Favorite),
            )
    }

    private fun interactionChanged(listingId: String, kind: ExploreInteractionKind): Boolean =
        ExploreInteractionRevisionKey(listingId, kind) in changedInteractionKeys
}

private fun ExploreListingItem.mergeLike(
    visible: ExploreListingItem?,
    confirmed: ExploreConfirmedLikeState?,
    pending: QueuedExploreInteraction?,
    changed: Boolean,
): ExploreListingItem = when {
    pending != null -> applyDurableSelection(ExploreInteractionKind.Like, pending.selected)
    changed && visible != null -> copy(liked = visible.liked, likesCount = visible.likesCount)
    changed && confirmed != null -> copy(
        liked = confirmed.liked,
        likesCount = confirmed.likesCount ?: likesCount,
    )
    else -> this
}

private fun ExploreListingItem.mergeFavorite(
    visible: ExploreListingItem?,
    confirmed: Boolean?,
    pending: QueuedExploreInteraction?,
    changed: Boolean,
): ExploreListingItem = copy(
    favorited = when {
        pending != null -> pending.selected
        changed -> visible?.favorited ?: confirmed ?: favorited
        else -> favorited
    },
)

private fun ExploreUiState.visibleInteractionMessage(
    visiblePendingInteraction: PendingExploreAuthInteraction?,
): String? = interactionMessage.takeUnless {
    pendingAuthInteraction != null && visiblePendingInteraction == null
}
