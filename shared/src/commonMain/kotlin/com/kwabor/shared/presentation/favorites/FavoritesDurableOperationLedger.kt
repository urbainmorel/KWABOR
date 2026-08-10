package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.domain.interaction.PendingInteractionStatus

internal class FavoritesDurableOperationLedger {
    private val pendingByListingId = mutableMapOf<String, PendingInteraction>()
    private val settledOperationIds = mutableMapOf<String, Long>()
    private val lastConfirmedMutationSequences = mutableMapOf<String, Long>()

    fun reset() {
        pendingByListingId.clear()
        settledOperationIds.clear()
        lastConfirmedMutationSequences.clear()
    }

    fun upsert(pending: PendingInteraction): Boolean {
        if (pending.kind != InteractionKind.Favorite) return false
        if ((settledOperationIds[pending.listingId] ?: 0L) >= pending.operationId) return false
        val current = pendingByListingId[pending.listingId]
        if (current == pending) return false
        if (current != null && current.operationId > pending.operationId) return false
        if (current?.operationId == pending.operationId && current.attemptCount > pending.attemptCount) return false
        pendingByListingId[pending.listingId] = pending
        return true
    }

    fun settle(listingId: String, operationId: Long): PendingInteraction? {
        val settled = settledOperationIds[listingId] ?: 0L
        if (operationId > settled) settledOperationIds[listingId] = operationId
        val pending = pendingByListingId[listingId]
        if (pending != null && pending.operationId <= operationId) pendingByListingId.remove(listingId)
        return pendingByListingId[listingId]
    }

    fun acceptConfirmationSequence(listingId: String, sequence: Long): Boolean {
        val lastSequence = lastConfirmedMutationSequences[listingId] ?: 0L
        if (sequence <= lastSequence) return false
        lastConfirmedMutationSequences[listingId] = sequence
        return true
    }

    fun canApplyTerminal(listingId: String, operationId: Long): Boolean =
        operationId > (settledOperationIds[listingId] ?: 0L)

    fun reconcile(expectedOperationIds: Map<String, Long>, hydrated: List<PendingInteraction>) {
        val hydratedFavorites = hydrated
            .filter { pending -> pending.kind == InteractionKind.Favorite }
            .associateBy(PendingInteraction::listingId)
        expectedOperationIds.forEach { (listingId, operationId) ->
            val current = pendingByListingId[listingId]
            val hydratedOperationId = hydratedFavorites[listingId]?.operationId
            if (current?.operationId == operationId && hydratedOperationId != operationId) {
                settle(listingId, operationId)
            }
        }
        hydratedFavorites.values.forEach(::upsert)
    }

    fun pending(listingId: String): PendingInteraction? = pendingByListingId[listingId]

    fun pendingOperations(): List<PendingInteraction> = pendingByListingId.values.toList()

    fun pendingOperationIds(): Map<String, Long> = pendingByListingId.mapValues { (_, pending) ->
        pending.operationId
    }

    fun pendingRemovalIds(): Set<String> = pendingByListingId.values
        .filterNot(PendingInteraction::desiredSelected)
        .mapTo(mutableSetOf(), PendingInteraction::listingId)

    fun retryListingIds(): Set<String> = pendingByListingId.values
        .filter { pending ->
            pending.attemptCount > 0 && pending.status is PendingInteractionStatus.Scheduled
        }
        .mapTo(mutableSetOf(), PendingInteraction::listingId)

    fun listingIds(): Set<String> = pendingByListingId.keys.toSet()
}
