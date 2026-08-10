package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionConfirmation
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionSubmitOutcome
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.session.ViewerSessionScope

internal fun ViewerSessionScope.toInteractionScope(): InteractionAccountScope = InteractionAccountScope(
    accountId = requireNotNull(accountId),
    epoch = epoch,
)

internal data class FavoritesDurableHydration(
    val scope: InteractionAccountScope,
    val pending: List<PendingInteraction>,
    val requestedListingIds: Set<String>,
    val expectedOperationIds: Map<String, Long>,
)

internal data class FavoritesDurableReconciliationRequest(
    val scope: ViewerSessionScope,
    val listingIds: List<String>,
    val expectedOperationIds: Map<String, Long>,
)

internal fun InteractionSubmitOutcome.Queued.isValidFor(request: DurableFavoriteRemovalRequest): Boolean =
    command.matches(request) && pending.matches(command)

internal fun InteractionCommand.matches(request: DurableFavoriteRemovalRequest): Boolean =
    scope.matches(request.scope) &&
        listingId == request.listingId &&
        kind == InteractionKind.Favorite &&
        !desiredSelected

private fun PendingInteraction.matches(command: InteractionCommand): Boolean = accountId == command.scope.accountId &&
    listingId == command.listingId &&
    kind == command.kind &&
    desiredSelected == command.desiredSelected

private fun InteractionConfirmation.matches(command: InteractionCommand): Boolean = when (this) {
    is InteractionConfirmation.Like -> false
    is InteractionConfirmation.Favorite ->
        scope == command.scope &&
            listingId == command.listingId &&
            favorited == command.desiredSelected &&
            clientMutationSequence > 0L
}

internal fun InteractionCoordinatorEvent.hasConsistentFavoritePayload(): Boolean {
    if (deliverySequence <= 0L || !command.belongsToFavoriteScope(scope)) return false
    return when (this) {
        is InteractionCoordinatorEvent.Queued -> pending.matches(command)
        is InteractionCoordinatorEvent.Retrying -> pending.matches(command)
        is InteractionCoordinatorEvent.Confirmed -> confirmation.matches(command)
        is InteractionCoordinatorEvent.Rejected -> operationId > 0L
        is InteractionCoordinatorEvent.Superseded -> operationId > 0L
    }
}

private fun InteractionCommand.belongsToFavoriteScope(scope: InteractionAccountScope): Boolean =
    kind == InteractionKind.Favorite && this.scope == scope
