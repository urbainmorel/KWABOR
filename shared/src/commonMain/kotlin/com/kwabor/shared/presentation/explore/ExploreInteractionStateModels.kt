package com.kwabor.shared.presentation.explore

internal data class ExploreDurableHydrationCommit(
    val requiresAuthoritativeReconciliation: Boolean,
)

internal data class ExploreDurableHydrationExpectation(
    val operationId: Long?,
    val kindRevision: Long,
)

internal data class ExploreConfirmedLikeState(
    val liked: Boolean,
    val likesCount: Int?,
)

internal data class ExploreInteractionRevisionKey(
    val listingId: String,
    val kind: ExploreInteractionKind,
)
