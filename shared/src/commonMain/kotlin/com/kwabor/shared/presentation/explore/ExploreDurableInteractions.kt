package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionConfirmation
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.domain.interaction.PendingInteractionStatus
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlin.math.max

internal fun ExploreInteractionKind.toDomainInteractionKind(): InteractionKind = when (this) {
    ExploreInteractionKind.Like -> InteractionKind.Like
    ExploreInteractionKind.Favorite -> InteractionKind.Favorite
}

internal fun InteractionKind.toExploreInteractionKind(): ExploreInteractionKind = when (this) {
    InteractionKind.Like -> ExploreInteractionKind.Like
    InteractionKind.Favorite -> ExploreInteractionKind.Favorite
}

internal fun ViewerSessionScope.matches(scope: InteractionAccountScope): Boolean =
    accountId == scope.accountId && epoch == scope.epoch

internal fun InteractionAccountScope.matches(scope: ViewerSessionScope): Boolean = scope.matches(this)

internal fun ViewerSessionScope.toInteractionAccountScopeOrNull(): InteractionAccountScope? =
    accountId?.let { normalizedAccountId ->
        InteractionAccountScope(accountId = normalizedAccountId, epoch = epoch)
    }

internal fun PendingInteraction.toQueuedExploreInteraction(): QueuedExploreInteraction = QueuedExploreInteraction(
    listingId = listingId,
    kind = kind.toExploreInteractionKind(),
    selected = desiredSelected,
    queuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
    operationId = operationId,
    attemptCount = attemptCount,
    isNetworkRetry = attemptCount > 0 && status is PendingInteractionStatus.Scheduled,
)

internal fun ExploreUiState.applyDurablePending(pending: PendingInteraction, strings: KwaborStrings): ExploreUiState {
    val queued = queuedInteractions.upsertDurable(pending.toQueuedExploreInteraction())
    val kind = pending.kind.toExploreInteractionKind()
    return copy(
        listings = listings.map { listing ->
            if (listing.id == pending.listingId) {
                listing.applyDurableSelection(kind, pending.desiredSelected)
            } else {
                listing
            }
        },
        isOffline = contentIsOffline || queued.hasNetworkRetry(),
        interactionMessage = if (queued.hasNetworkRetry()) strings.interactionQueuedOffline else null,
        pendingAuthInteraction = null,
        queuedInteractions = queued,
    )
}

internal fun ExploreUiState.applyDurableConfirmation(
    confirmation: InteractionConfirmation,
    strings: KwaborStrings,
): ExploreUiState {
    val kind = when (confirmation) {
        is InteractionConfirmation.Like -> ExploreInteractionKind.Like
        is InteractionConfirmation.Favorite -> ExploreInteractionKind.Favorite
    }
    val remaining = queuedInteractions.removeDurableOperation(
        listingId = confirmation.listingId,
        kind = kind,
        operationId = confirmation.operationId,
    )
    return copy(
        listings = listings.map { listing -> listing.applyDurableConfirmation(confirmation) },
        isOffline = contentIsOffline || remaining.hasNetworkRetry(),
        interactionMessage = if (remaining.hasNetworkRetry()) strings.interactionQueuedOffline else null,
        pendingAuthInteraction = null,
        queuedInteractions = remaining,
    )
}

internal fun ExploreUiState.rejectDurableOperation(
    listingId: String,
    kind: ExploreInteractionKind,
    desiredSelected: Boolean,
    operationId: Long,
    strings: KwaborStrings,
): ExploreUiState {
    val remaining = queuedInteractions.removeDurableOperation(listingId, kind, operationId)
    return copy(
        listings = listings.map { listing ->
            if (listing.id == listingId) {
                listing.applyDurableSelection(kind, !desiredSelected)
            } else {
                listing
            }
        },
        isOffline = contentIsOffline || remaining.hasNetworkRetry(),
        interactionMessage = strings.interactionFailed,
        pendingAuthInteraction = null,
        queuedInteractions = remaining,
    )
}

internal fun ExploreUiState.requireAuthenticationForDurableInteraction(
    listingId: String,
    kind: ExploreInteractionKind,
    strings: KwaborStrings,
): ExploreUiState = copy(
    interactionMessage = strings.signInRequiredForInteraction,
    pendingAuthInteraction = PendingExploreAuthInteraction(
        listingId = listingId,
        kind = kind,
        suggestedCityId = listings.firstOrNull { listing -> listing.id == listingId }?.cityId,
    ),
)

internal fun ExploreUiState.failDurableInteraction(strings: KwaborStrings): ExploreUiState = copy(
    interactionMessage = strings.interactionFailed,
    pendingAuthInteraction = null,
)

internal fun ExploreUiState.hasDurableOperation(
    listingId: String,
    kind: ExploreInteractionKind,
    operationId: Long,
): Boolean = queuedInteractions.any { queued ->
    queued.listingId == listingId && queued.kind == kind && queued.operationId == operationId
}

internal fun List<QueuedExploreInteraction>.hasNetworkRetry(): Boolean = any(QueuedExploreInteraction::isNetworkRetry)

internal fun List<QueuedExploreInteraction>.forKey(
    listingId: String,
    kind: ExploreInteractionKind,
): QueuedExploreInteraction? = firstOrNull { queued ->
    queued.listingId == listingId && queued.kind == kind
}

private fun List<QueuedExploreInteraction>.upsertDurable(
    interaction: QueuedExploreInteraction,
): List<QueuedExploreInteraction> = filterNot { queued ->
    queued.listingId == interaction.listingId && queued.kind == interaction.kind
} + interaction

private fun List<QueuedExploreInteraction>.removeDurableOperation(
    listingId: String,
    kind: ExploreInteractionKind,
    operationId: Long,
): List<QueuedExploreInteraction> = filterNot { queued ->
    queued.listingId == listingId &&
        queued.kind == kind &&
        queued.operationId?.let { queuedOperationId -> queuedOperationId <= operationId } == true
}

private fun ExploreListingItem.applyDurableConfirmation(confirmation: InteractionConfirmation): ExploreListingItem =
    when (confirmation) {
        is InteractionConfirmation.Like -> if (id == confirmation.listingId) {
            copy(
                liked = confirmation.liked,
                likesCount = confirmation.likesCount ?: likesCount,
            )
        } else {
            this
        }
        is InteractionConfirmation.Favorite -> if (id == confirmation.listingId) {
            copy(favorited = confirmation.favorited)
        } else {
            this
        }
    }

internal fun ExploreListingItem.applyDurableSelection(
    kind: ExploreInteractionKind,
    selected: Boolean,
): ExploreListingItem = when (kind) {
    ExploreInteractionKind.Like -> if (liked == selected) {
        this
    } else {
        copy(
            liked = selected,
            likesCount = if (selected) likesCount + 1 else max(likesCount - 1, 0),
        )
    }
    ExploreInteractionKind.Favorite -> copy(favorited = selected)
}
