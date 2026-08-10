package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionConfirmation
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.interaction.InteractionHydration
import com.kwabor.shared.presentation.interaction.InteractionReconciliationSignal
import com.kwabor.shared.presentation.interaction.terminalWatermark
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.sync.withLock

internal suspend fun ExploreStateStore.applyDurablePending(
    pending: PendingInteraction,
    expectedScope: ViewerSessionScope,
    strings: KwaborStrings,
): ExploreUiState? = mutex.withLock {
    val current = mutableState.value
    if (current.viewerScope != expectedScope || pending.accountId != expectedScope.accountId) return@withLock null
    prepareInteractionDataFor(expectedScope)
    val kind = pending.kind.toExploreInteractionKind()
    val key = ExploreInteractionRevisionKey(pending.listingId, kind)
    val queued = current.queuedInteractions.forKey(pending.listingId, kind)
    val highestSeenOperation = lastDurableOperationIdsByKey[key] ?: 0L
    if (!pending.canReplace(queued, highestSeenOperation)) return@withLock null
    val updated = current.applyDurablePending(pending, strings)
    if (updated == current) return@withLock current
    if (!mutableState.compareAndSet(current, updated)) return@withLock null
    lastDurableOperationIdsByKey[key] = maxOf(highestSeenOperation, pending.operationId)
    if (queued?.operationId != pending.operationId) {
        recordDurableInteractionChange(key, pending.listingId, updated)
    }
    updated
}

internal suspend fun ExploreStateStore.captureDurableHydrationExpectations(
    requestedListingIds: List<String>,
    expectedScope: ViewerSessionScope,
): Map<ExploreInteractionRevisionKey, ExploreDurableHydrationExpectation>? = mutex.withLock {
    val current = mutableState.value
    if (current.viewerScope != expectedScope) return@withLock null
    prepareInteractionDataFor(expectedScope)
    buildMap {
        requestedListingIds.forEach { listingId ->
            ExploreInteractionKind.entries.forEach { kind ->
                val key = ExploreInteractionRevisionKey(listingId, kind)
                put(
                    key,
                    ExploreDurableHydrationExpectation(
                        operationId = current.queuedInteractions.forKey(listingId, kind)?.operationId,
                        kindRevision = interactionKindRevisions[key] ?: 0L,
                    ),
                )
            }
        }
    }
}

internal suspend fun ExploreStateStore.applyDurableHydration(
    hydration: InteractionHydration,
    requestedListingIds: List<String>,
    expectations: Map<ExploreInteractionRevisionKey, ExploreDurableHydrationExpectation>,
    expectedScope: ViewerSessionScope,
    strings: KwaborStrings,
): ExploreDurableHydrationCommit? = mutex.withLock {
    val current = mutableState.value
    if (current.viewerScope != expectedScope || !hydration.scope.matches(expectedScope)) return@withLock null
    prepareInteractionDataFor(expectedScope)
    val missing = findMissingDurableInteractions(current, hydration, requestedListingIds, expectations)
    val withoutMissing = removeMissingDurableInteractions(current, missing, strings)
    val reduction = applyHydratedPendingInteractions(withoutMissing, hydration.pending, strings)
    if (reduction.state != current && !mutableState.compareAndSet(current, reduction.state)) return@withLock null
    missing.forEach { queued ->
        val operationId = queued.operationId ?: return@forEach
        val key = ExploreInteractionRevisionKey(queued.listingId, queued.kind)
        lastDurableOperationIdsByKey[key] = maxOf(lastDurableOperationIdsByKey[key] ?: 0L, operationId)
    }
    reduction.changedKeys.forEach { key ->
        recordDurableInteractionChange(key, key.listingId, reduction.state)
    }
    ExploreDurableHydrationCommit(requiresAuthoritativeReconciliation = missing.isNotEmpty())
}

internal suspend fun ExploreStateStore.applyReconciliationWatermarks(
    signal: InteractionReconciliationSignal,
    expectedScope: ViewerSessionScope,
    strings: KwaborStrings,
): ExploreReconciliationWatermarkCommit? = mutex.withLock {
    val current = mutableState.value
    if (current.viewerScope != expectedScope || !signal.scope.matches(expectedScope)) return@withLock null
    prepareInteractionDataFor(expectedScope)
    signal.terminalWatermarks.forEach { (watermark, operationId) ->
        val key = ExploreInteractionRevisionKey(
            listingId = watermark.listingId,
            kind = watermark.kind.toExploreInteractionKind(),
        )
        lastDurableOperationIdsByKey[key] = maxOf(lastDurableOperationIdsByKey[key] ?: 0L, operationId)
    }
    val settled = current.settledBy(signal)
    val requiresAuthoritativeReconciliation = current.hasVisibleTerminalWatermark(signal)
    if (settled.isEmpty()) {
        return@withLock ExploreReconciliationWatermarkCommit(requiresAuthoritativeReconciliation)
    }
    val remaining = current.queuedInteractions - settled.toSet()
    val updated = current.copy(
        queuedInteractions = remaining,
        isOffline = current.contentIsOffline || remaining.hasNetworkRetry(),
        interactionMessage = strings.interactionQueuedOffline.takeIf { remaining.hasNetworkRetry() },
    )
    if (!mutableState.compareAndSet(current, updated)) return@withLock null
    settled.forEach { queued ->
        recordDurableInteractionChange(
            key = ExploreInteractionRevisionKey(queued.listingId, queued.kind),
            listingId = queued.listingId,
            state = updated,
        )
    }
    ExploreReconciliationWatermarkCommit(requiresAuthoritativeReconciliation)
}

private fun ExploreUiState.settledBy(signal: InteractionReconciliationSignal): List<QueuedExploreInteraction> =
    queuedInteractions.filter { queued ->
        val operationId = queued.operationId ?: return@filter false
        signal.terminalWatermark(
            listingId = queued.listingId,
            kind = queued.kind.toDomainInteractionKind(),
        )?.let { watermark -> operationId <= watermark } == true
    }

private fun ExploreUiState.hasVisibleTerminalWatermark(signal: InteractionReconciliationSignal): Boolean =
    signal.terminalWatermarks.keys.any { watermark ->
        listings.any { listing -> listing.id == watermark.listingId }
    }

internal data class ExploreReconciliationWatermarkCommit(
    val requiresAuthoritativeReconciliation: Boolean,
)

internal suspend fun ExploreStateStore.applyDurableConfirmation(
    confirmation: InteractionConfirmation,
    expectedScope: ViewerSessionScope,
    strings: KwaborStrings,
): ExploreUiState? = mutex.withLock {
    val current = mutableState.value
    if (current.viewerScope != expectedScope || !confirmation.scope.matches(expectedScope)) return@withLock null
    prepareInteractionDataFor(expectedScope)
    val kind = confirmation.toExploreInteractionKind()
    val key = ExploreInteractionRevisionKey(confirmation.listingId, kind)
    val highestSeenOperation = lastDurableOperationIdsByKey[key] ?: 0L
    val queued = current.queuedInteractions.forKey(confirmation.listingId, kind)
    if (!canApplyConfirmation(confirmation, queued, highestSeenOperation)) return@withLock null
    val updated = current.applyDurableConfirmation(confirmation, strings)
    if (!mutableState.compareAndSet(current, updated)) return@withLock null
    lastDurableOperationIdsByKey[key] = maxOf(highestSeenOperation, confirmation.operationId)
    recordDurableInteractionChange(key, confirmation.listingId, updated)
    recordConfirmedState(confirmation)
    updated
}

internal suspend fun ExploreStateStore.rejectDurableOperation(
    command: InteractionCommand,
    operationId: Long,
    expectedScope: ViewerSessionScope,
    strings: KwaborStrings,
): ExploreUiState? = mutex.withLock {
    val current = mutableState.value
    if (current.viewerScope != expectedScope || !command.scope.matches(expectedScope)) return@withLock null
    val kind = command.kind.toExploreInteractionKind()
    val queued = current.queuedInteractions.forKey(command.listingId, kind)
    if (queued?.operationId != operationId || queued.selected != command.desiredSelected) return@withLock null
    prepareInteractionDataFor(expectedScope)
    val key = ExploreInteractionRevisionKey(command.listingId, kind)
    val updated = current.rejectDurableOperation(
        listingId = command.listingId,
        kind = kind,
        desiredSelected = command.desiredSelected,
        operationId = operationId,
        strings = strings,
    )
    if (!mutableState.compareAndSet(current, updated)) return@withLock null
    lastDurableOperationIdsByKey[key] = maxOf(lastDurableOperationIdsByKey[key] ?: 0L, operationId)
    recordDurableInteractionChange(key, command.listingId, updated)
    updated
}

internal suspend fun ExploreStateStore.hasDurableOperation(
    listingId: String,
    kind: ExploreInteractionKind,
    operationId: Long,
    expectedScope: ViewerSessionScope,
): Boolean = mutex.withLock {
    val current = mutableState.value
    current.viewerScope == expectedScope && current.hasDurableOperation(listingId, kind, operationId)
}

private fun PendingInteraction.canReplace(queued: QueuedExploreInteraction?, highestSeenOperation: Long): Boolean {
    if (operationId < highestSeenOperation) return false
    if (queued == null && operationId == highestSeenOperation) return false
    val queuedOperation = queued?.operationId
    if (queuedOperation != null && operationId < queuedOperation) return false
    if (queuedOperation != operationId) return true
    return queued.selected == desiredSelected && attemptCount >= queued.attemptCount
}

private fun ExploreStateStore.findMissingDurableInteractions(
    current: ExploreUiState,
    hydration: InteractionHydration,
    requestedListingIds: List<String>,
    expectations: Map<ExploreInteractionRevisionKey, ExploreDurableHydrationExpectation>,
): List<QueuedExploreInteraction> {
    val requested = requestedListingIds.toSet()
    val incomingKeys = hydration.pending.mapTo(mutableSetOf()) { pending ->
        ExploreInteractionRevisionKey(pending.listingId, pending.kind.toExploreInteractionKind())
    }
    return current.queuedInteractions.filter { queued ->
        val key = ExploreInteractionRevisionKey(queued.listingId, queued.kind)
        queued.operationId != null && queued.listingId in requested && key !in incomingKeys &&
            expectations[key]?.matchesCurrent(queued, interactionKindRevisions[key] ?: 0L) == true
    }
}

private fun ExploreDurableHydrationExpectation.matchesCurrent(
    queued: QueuedExploreInteraction,
    currentKindRevision: Long,
): Boolean = operationId == queued.operationId && kindRevision == currentKindRevision

private fun removeMissingDurableInteractions(
    current: ExploreUiState,
    missing: List<QueuedExploreInteraction>,
    strings: KwaborStrings,
): ExploreDurableHydrationReduction = missing.fold(
    ExploreDurableHydrationReduction(current, emptySet()),
) { reduction, queued ->
    val operationId = queued.operationId ?: return@fold reduction
    val rejected = reduction.state.rejectDurableOperation(
        listingId = queued.listingId,
        kind = queued.kind,
        desiredSelected = queued.selected,
        operationId = operationId,
        strings = strings,
    )
    val updated = rejected.copy(
        interactionMessage = strings.interactionQueuedOffline.takeIf {
            rejected.queuedInteractions.hasNetworkRetry()
        },
    )
    reduction.copy(
        state = updated,
        changedKeys = reduction.changedKeys + ExploreInteractionRevisionKey(queued.listingId, queued.kind),
    )
}

private fun ExploreStateStore.applyHydratedPendingInteractions(
    initial: ExploreDurableHydrationReduction,
    pendingInteractions: List<PendingInteraction>,
    strings: KwaborStrings,
): ExploreDurableHydrationReduction = pendingInteractions.fold(initial) { reduction, pending ->
    val kind = pending.kind.toExploreInteractionKind()
    val key = ExploreInteractionRevisionKey(pending.listingId, kind)
    val queued = reduction.state.queuedInteractions.forKey(pending.listingId, kind)
    val highestSeenOperation = lastDurableOperationIdsByKey[key] ?: 0L
    if (!pending.canReplace(queued, highestSeenOperation)) return@fold reduction
    lastDurableOperationIdsByKey[key] = maxOf(highestSeenOperation, pending.operationId)
    ExploreDurableHydrationReduction(
        state = reduction.state.applyDurablePending(pending, strings),
        changedKeys = if (queued?.operationId == pending.operationId) {
            reduction.changedKeys
        } else {
            reduction.changedKeys + key
        },
    )
}

private fun ExploreStateStore.canApplyConfirmation(
    confirmation: InteractionConfirmation,
    queued: QueuedExploreInteraction?,
    highestSeenOperation: Long,
): Boolean {
    if (confirmation.operationId < highestSeenOperation) return false
    if (queued?.operationId?.let { operationId -> operationId > confirmation.operationId } == true) return false
    if (queued?.operationId == confirmation.operationId && queued.selected != confirmation.selected) return false
    return when (confirmation) {
        is InteractionConfirmation.Like -> true
        is InteractionConfirmation.Favorite ->
            confirmation.clientMutationSequence >
                (confirmedFavoriteSequencesByListingId[confirmation.listingId] ?: 0L)
    }
}

private fun ExploreStateStore.recordConfirmedState(confirmation: InteractionConfirmation) {
    when (confirmation) {
        is InteractionConfirmation.Like -> confirmedLikeStatesByListingId[confirmation.listingId] =
            ExploreConfirmedLikeState(
                liked = confirmation.liked,
                likesCount = confirmation.likesCount,
            )
        is InteractionConfirmation.Favorite -> {
            confirmedFavoriteSequencesByListingId[confirmation.listingId] = confirmation.clientMutationSequence
            confirmedFavoriteStatesByListingId[confirmation.listingId] = confirmation.favorited
        }
    }
}

private fun ExploreStateStore.recordDurableInteractionChange(
    key: ExploreInteractionRevisionKey,
    listingId: String,
    state: ExploreUiState,
) {
    interactionMergeRevisions[key] = ++interactionRevision
    interactionKindRevisions[key] = (interactionKindRevisions[key] ?: 0L) + 1L
    state.listings.firstOrNull { listing -> listing.id == listingId }?.let { listing ->
        interactionOverridesByListingId[listingId] = listing
    }
}

private fun InteractionConfirmation.toExploreInteractionKind(): ExploreInteractionKind = when (this) {
    is InteractionConfirmation.Like -> ExploreInteractionKind.Like
    is InteractionConfirmation.Favorite -> ExploreInteractionKind.Favorite
}

private val InteractionConfirmation.selected: Boolean
    get() = when (this) {
        is InteractionConfirmation.Like -> liked
        is InteractionConfirmation.Favorite -> favorited
    }

private data class ExploreDurableHydrationReduction(
    val state: ExploreUiState,
    val changedKeys: Set<ExploreInteractionRevisionKey>,
)
