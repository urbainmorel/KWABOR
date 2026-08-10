package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionConfirmation
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionSubmitOutcome
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.interaction.InteractionHydration
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val EXPLORE_RUNTIME_HYDRATION_WINDOW_SIZE = 1_000

internal class ExploreDurableRuntimeCoordinator(
    private val coordinator: InteractionCoordinator?,
    private val strings: KwaborStrings,
    private val coroutineScope: CoroutineScope,
    private val stateStore: ExploreStateStore,
    private val interactionMutex: Mutex,
    private val callbacks: ExploreDurableRuntimeCallbacks,
) {
    private val signalReconciler = coordinator?.let { durableCoordinator ->
        ExploreDurableSignalReconciler(
            coordinator = durableCoordinator,
            strings = strings,
            stateStore = stateStore,
            interactionMutex = interactionMutex,
            callbacks = callbacks,
            scheduleAuthoritativeReconciliation = ::scheduleAuthoritativeReconciliation,
        )
    }
    private val reconciliationBarrier = ExploreDurableReconciliationBarrier(
        coordinator = coordinator,
        strings = strings,
        stateStore = stateStore,
        interactionMutex = interactionMutex,
        callbacks = callbacks,
        reconcileCurrentVisible = { signal, scope, forceAuthoritativeReconciliation ->
            signalReconciler?.reconcile(signal, scope, forceAuthoritativeReconciliation) == true
        },
    )
    private val eventsJob: Job? = coordinator?.let { durableCoordinator ->
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            durableCoordinator.events.collect(::handleEvent)
        }
    }
    private val reconciliationJob: Job? = coordinator?.let { durableCoordinator ->
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            durableCoordinator.reconciliationSignals.collect { signal ->
                signal?.let { current -> reconciliationBarrier.handle(current) }
            }
        }
    }

    suspend fun hydrateVisible(
        expectedScope: ViewerSessionScope,
        listingIds: List<String>,
        forceAuthoritativeReconciliation: Boolean = false,
    ): Boolean = signalReconciler?.hydrateVisible(
        expectedScope = expectedScope,
        listingIds = listingIds,
        forceAuthoritativeReconciliation = forceAuthoritativeReconciliation,
    ) ?: true

    suspend fun retry(sourceScope: ViewerSessionScope?) {
        val durableCoordinator = coordinator ?: return
        val expectedScope = sourceScope ?: return
        val interactionScope = expectedScope.toInteractionAccountScopeOrNull() ?: return
        if (!callbacks.isCurrentScope(expectedScope)) return
        durableCoordinator.retryManually(interactionScope)
    }

    suspend fun execute(
        baseline: ExploreUiState,
        expectedScope: ViewerSessionScope,
        listingId: String,
        kind: ExploreInteractionKind,
        canSubmit: suspend () -> Boolean,
    ): ExploreInteractionExecution? {
        val durableCoordinator = coordinator ?: return null
        if (!expectedScope.isAuthenticated) {
            return baseline.authenticationRequired(listingId, kind, strings)
        }
        val listing = baseline.listings.firstOrNull { item -> item.id == listingId }
            ?: return ExploreInteractionExecution(baseline)
        if (!canSubmit()) return ExploreInteractionExecution(baseline)
        val request = ExploreDurableSubmitRequest(
            scope = expectedScope,
            listingId = listingId,
            kind = kind,
            desiredSelected = listing.desiredSelection(kind),
        )
        return submit(durableCoordinator, baseline, request)
    }

    fun close() {
        eventsJob?.cancel()
        reconciliationJob?.cancel()
    }

    private suspend fun submit(
        durableCoordinator: InteractionCoordinator,
        baseline: ExploreUiState,
        request: ExploreDurableSubmitRequest,
    ): ExploreInteractionExecution {
        val interactionScope = request.scope.toInteractionAccountScopeOrNull()
            ?: return baseline.failedInteraction(strings)
        val result = durableCoordinator.submit(
            expectedScope = interactionScope,
            listingId = request.listingId,
            kind = request.kind.toDomainInteractionKind(),
            desiredSelected = request.desiredSelected,
        )
        return resolveSubmit(result, baseline, request)
    }

    private fun resolveSubmit(
        result: DomainResult<InteractionSubmitOutcome>,
        baseline: ExploreUiState,
        request: ExploreDurableSubmitRequest,
    ): ExploreInteractionExecution = when (result) {
        is DomainResult.Failure -> baseline.failedInteraction(strings)
        is DomainResult.Success -> when (val outcome = result.value) {
            is InteractionSubmitOutcome.Queued -> if (outcome.isValidFor(request)) {
                ExploreInteractionExecution(baseline.applyDurablePending(outcome.pending, strings))
            } else {
                baseline.failedInteraction(strings)
            }
            is InteractionSubmitOutcome.Superseded -> {
                if (outcome.command.matches(request)) {
                    coroutineScope.launch { hydrateCurrentVisible(request.scope) }
                }
                ExploreInteractionExecution(baseline)
            }
        }
    }

    private suspend fun handleEvent(event: InteractionCoordinatorEvent) {
        val durableCoordinator = coordinator ?: return
        if (!event.hasConsistentPayload()) return
        var shouldRevalidate = false
        val validationPrepared = durableCoordinator.deliveryCommitGate.runIfEventDeliveryValid(event) {
            shouldRevalidate = reconciliationBarrier.shouldRevalidatePendingEvent(event)
        }
        if (!validationPrepared) return
        if (shouldRevalidate) {
            val expectedScope = callbacks.currentViewerScope() ?: return
            val reconciled = signalReconciler?.hydrateVisible(
                expectedScope = expectedScope,
                listingIds = listOf(event.command.listingId),
                forceAuthoritativeReconciliation = false,
                deliveryEvent = event,
            ) == true
            if (!reconciled) durableCoordinator.deliveryCommitGate.requestReconciliationFor(event)
            return
        }
        var followUp: ExploreDurableFollowUp = ExploreDurableFollowUp.None
        val reduced = durableCoordinator.deliveryCommitGate.runIfEventDeliveryValid(event) {
            followUp = interactionMutex.withLock { reduceEvent(event) }
        }
        if (!reduced) return
        when (val currentFollowUp = followUp) {
            ExploreDurableFollowUp.None -> Unit
            is ExploreDurableFollowUp.Hydrate -> hydrateCurrentVisible(currentFollowUp.scope, event)
            is ExploreDurableFollowUp.Reconcile -> scheduleAuthoritativeReconciliation(currentFollowUp.scope)
        }
    }

    private suspend fun reduceEvent(event: InteractionCoordinatorEvent): ExploreDurableFollowUp {
        val expectedScope = callbacks.currentViewerScope() ?: return ExploreDurableFollowUp.None
        if (!expectedScope.isAuthenticated || !event.scope.matches(expectedScope)) {
            return ExploreDurableFollowUp.None
        }
        return when (event) {
            is InteractionCoordinatorEvent.Queued -> applyPending(event.pending, expectedScope)
            is InteractionCoordinatorEvent.Retrying -> applyPending(event.pending, expectedScope)
            is InteractionCoordinatorEvent.Confirmed -> applyConfirmation(event.confirmation, expectedScope)
            is InteractionCoordinatorEvent.Rejected -> reject(event, expectedScope)
            is InteractionCoordinatorEvent.Superseded -> supersede(event, expectedScope)
        }
    }

    private suspend fun applyPending(
        pending: PendingInteraction,
        expectedScope: ViewerSessionScope,
    ): ExploreDurableFollowUp {
        stateStore.applyDurablePending(pending, expectedScope, strings)
        return ExploreDurableFollowUp.None
    }

    private suspend fun applyConfirmation(
        confirmation: InteractionConfirmation,
        expectedScope: ViewerSessionScope,
    ): ExploreDurableFollowUp {
        stateStore.applyDurableConfirmation(confirmation, expectedScope, strings)
        return ExploreDurableFollowUp.None
    }

    private suspend fun reject(
        event: InteractionCoordinatorEvent.Rejected,
        expectedScope: ViewerSessionScope,
    ): ExploreDurableFollowUp {
        val rejected = stateStore.rejectDurableOperation(
            command = event.command,
            operationId = event.operationId,
            expectedScope = expectedScope,
            strings = strings,
        )
        return if (rejected == null) ExploreDurableFollowUp.None else ExploreDurableFollowUp.Reconcile(expectedScope)
    }

    private suspend fun supersede(
        event: InteractionCoordinatorEvent.Superseded,
        expectedScope: ViewerSessionScope,
    ): ExploreDurableFollowUp {
        val pendingIsVisible = stateStore.hasDurableOperation(
            listingId = event.command.listingId,
            kind = event.command.kind.toExploreInteractionKind(),
            operationId = event.operationId,
            expectedScope = expectedScope,
        )
        return if (pendingIsVisible) ExploreDurableFollowUp.Hydrate(expectedScope) else ExploreDurableFollowUp.None
    }

    private suspend fun hydrateCurrentVisible(
        expectedScope: ViewerSessionScope,
        deliveryEvent: InteractionCoordinatorEvent? = null,
        forceAuthoritativeReconciliation: Boolean = false,
    ): Boolean {
        val current = stateStore.snapshot()
        if (current.viewerScope != expectedScope) return false
        return signalReconciler?.hydrateVisible(
            expectedScope = expectedScope,
            listingIds = current.listings.map(ExploreListingItem::id),
            forceAuthoritativeReconciliation = forceAuthoritativeReconciliation,
            deliveryEvent = deliveryEvent,
        ) ?: true
    }

    private fun scheduleAuthoritativeReconciliation(expectedScope: ViewerSessionScope) {
        coroutineScope.launch {
            val current = stateStore.snapshot()
            if (current.viewerScope == expectedScope) callbacks.reloadFeed(current.toLoadRequest())
        }
    }
}

internal sealed interface ExploreHydrationResult {
    data object Failed : ExploreHydrationResult

    data class Succeeded(
        val requiresAuthoritativeReconciliation: Boolean,
    ) : ExploreHydrationResult
}

internal data class ExploreDurableRuntimeCallbacks(
    val currentViewerScope: suspend () -> ViewerSessionScope?,
    val isCurrentScope: suspend (ViewerSessionScope) -> Boolean,
    val reloadFeed: suspend (ExploreLoadRequest) -> Unit,
)

private data class ExploreDurableSubmitRequest(
    val scope: ViewerSessionScope,
    val listingId: String,
    val kind: ExploreInteractionKind,
    val desiredSelected: Boolean,
)

private fun ExploreUiState.authenticationRequired(
    listingId: String,
    kind: ExploreInteractionKind,
    strings: KwaborStrings,
): ExploreInteractionExecution = ExploreInteractionExecution(
    requireAuthenticationForDurableInteraction(listingId, kind, strings),
)

private fun ExploreUiState.failedInteraction(strings: KwaborStrings): ExploreInteractionExecution =
    ExploreInteractionExecution(failDurableInteraction(strings))

private sealed interface ExploreDurableFollowUp {
    data object None : ExploreDurableFollowUp

    data class Hydrate(val scope: ViewerSessionScope) : ExploreDurableFollowUp

    data class Reconcile(val scope: ViewerSessionScope) : ExploreDurableFollowUp
}

internal fun List<String>.normalizeVisibleListingIds(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct()

private fun ExploreListingItem.desiredSelection(kind: ExploreInteractionKind): Boolean = when (kind) {
    ExploreInteractionKind.Like -> !liked
    ExploreInteractionKind.Favorite -> !favorited
}

private fun InteractionCoordinatorEvent.hasConsistentPayload(): Boolean = deliverySequence > 0L && when (this) {
    is InteractionCoordinatorEvent.Queued -> hasConsistentPending()
    is InteractionCoordinatorEvent.Retrying -> hasConsistentPending()
    is InteractionCoordinatorEvent.Confirmed -> command.scope == scope && confirmation.matches(command)
    is InteractionCoordinatorEvent.Rejected -> hasConsistentTerminalPayload()
    is InteractionCoordinatorEvent.Superseded -> hasConsistentTerminalPayload()
}

private fun InteractionCoordinatorEvent.Queued.hasConsistentPending(): Boolean =
    command.scope == scope && pending.matches(command) && pending.isHydratable

private fun InteractionCoordinatorEvent.Retrying.hasConsistentPending(): Boolean =
    command.scope == scope && pending.matches(command) && pending.isHydratable

private fun InteractionCoordinatorEvent.Rejected.hasConsistentTerminalPayload(): Boolean =
    command.scope == scope && operationId > 0L

private fun InteractionCoordinatorEvent.Superseded.hasConsistentTerminalPayload(): Boolean =
    command.scope == scope && operationId > 0L

private fun InteractionSubmitOutcome.Queued.isValidFor(request: ExploreDurableSubmitRequest): Boolean =
    command.matches(request) && pending.matches(command) && pending.isHydratable

private fun InteractionCommand.matches(request: ExploreDurableSubmitRequest): Boolean = scope.matches(request.scope) &&
    listingId == request.listingId &&
    kind == request.kind.toDomainInteractionKind() &&
    desiredSelected == request.desiredSelected

internal fun InteractionHydration.isValidFor(expectedScope: ViewerSessionScope, listingIds: List<String>): Boolean {
    if (!scope.matches(expectedScope)) return false
    val requested = listingIds.toSet()
    val keys = mutableSetOf<Pair<String, InteractionKind>>()
    return pending.all { interaction -> interaction.isValidHydrationEntry(expectedScope, requested, keys) }
}

private fun PendingInteraction.isValidHydrationEntry(
    expectedScope: ViewerSessionScope,
    requestedListingIds: Set<String>,
    keys: MutableSet<Pair<String, InteractionKind>>,
): Boolean = accountId == expectedScope.accountId &&
    listingId in requestedListingIds &&
    isHydratable &&
    keys.add(listingId to kind)

private fun PendingInteraction.matches(command: InteractionCommand): Boolean = accountId == command.scope.accountId &&
    listingId == command.listingId &&
    kind == command.kind &&
    desiredSelected == command.desiredSelected

private fun InteractionConfirmation.matches(command: InteractionCommand): Boolean = when (this) {
    is InteractionConfirmation.Like -> matchesLike(command)
    is InteractionConfirmation.Favorite -> matchesFavorite(command)
}

private fun InteractionConfirmation.Like.matchesLike(command: InteractionCommand): Boolean =
    hasValidIdentity(command, InteractionKind.Like) &&
        liked == command.desiredSelected &&
        (likesCount == null || likesCount >= 0) &&
        mutatedAtEpochMilliseconds >= 0L

private fun InteractionConfirmation.Favorite.matchesFavorite(command: InteractionCommand): Boolean =
    hasValidIdentity(command, InteractionKind.Favorite) &&
        favorited == command.desiredSelected &&
        clientMutationSequence > 0L

private fun InteractionConfirmation.hasValidIdentity(
    command: InteractionCommand,
    expectedKind: InteractionKind,
): Boolean = operationId > 0L &&
    scope == command.scope &&
    listingId == command.listingId &&
    command.kind == expectedKind
