package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.ActiveInteractionScopeProvider
import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionDrainOutcome
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionOperationOutcome
import com.kwabor.shared.domain.interaction.InteractionRepository
import com.kwabor.shared.domain.interaction.InteractionSubmitOutcome
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.domain.interaction.PendingInteractionStatus
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.min

private const val INTERACTION_HYDRATION_CHUNK_SIZE = 50
private const val MAX_INTERACTION_HYDRATION_LISTING_IDS = 1_000
private const val MAX_INTERACTION_SCHEDULE_DELAY_MILLISECONDS = 5L * 60L * 1_000L
private const val MIN_INTERACTION_RETRY_SCHEDULE_DELAY_MILLISECONDS = 1L
private const val TOO_MANY_INTERACTION_LISTING_IDS_ERROR_KEY = "error.interaction.too_many_listing_ids"

sealed interface InteractionAccountDeletionPurgeOutcome {
    data class Acquired(val purgedCount: Int) : InteractionAccountDeletionPurgeOutcome

    data object AlreadyBlocked : InteractionAccountDeletionPurgeOutcome
}

class InteractionCoordinator internal constructor(
    private val repository: InteractionRepository,
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
    private val clockProvider: ClockProvider,
    private val coroutineScope: CoroutineScope,
) {
    private val wakeAccumulator = InteractionWakeAccumulator()
    private val lifecycleGate = InteractionLifecycleGate()
    private val eventPublisher = InteractionEventPublisher(
        viewerSessionScopeTracker = viewerSessionScopeTracker,
        lifecycleGate = lifecycleGate,
    )
    val events: SharedFlow<InteractionCoordinatorEvent> = eventPublisher.events
    internal val reconciliationSignals: StateFlow<InteractionReconciliationSignal?> =
        eventPublisher.reconciliationSignals
    internal val deliveryCommitGate = InteractionDeliveryCommitGate(
        eventPublisher = eventPublisher,
        lifecycleGate = lifecycleGate,
        viewerSessionScopeTracker = viewerSessionScopeTracker,
    )
    private var scheduledWake: Job? = null

    init {
        coroutineScope.launch {
            viewerSessionScopeTracker.scope.collectLatest { viewerScope ->
                val scope = viewerScope.toInteractionScopeOrNull()
                if (scope == null) {
                    wakeAccumulator.clear()
                } else {
                    wakeAccumulator.offer(InteractionWakeRequest.automatic(scope), scope)
                }
            }
        }
        coroutineScope.launch {
            for (ignored in wakeAccumulator.signal) {
                var request = wakeAccumulator.take()
                while (request != null) {
                    processWake(request)
                    request = wakeAccumulator.take()
                }
            }
        }
    }

    suspend fun submit(
        expectedScope: InteractionAccountScope,
        listingId: String,
        kind: InteractionKind,
        desiredSelected: Boolean,
    ): DomainResult<InteractionSubmitOutcome> {
        val canonicalScope = expectedScope.toInteractionLifecycleScope()
        val lease = lifecycleGate.beginOperation(
            expectedScope = canonicalScope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        ) ?: return DomainResult.Failure(DomainError.AuthenticationRequired())
        return try {
            val visibleResult = repository.submitWithActiveLease(
                lifecycleGate = lifecycleGate,
                viewerSessionScopeTracker = viewerSessionScopeTracker,
                request = InteractionActiveSubmitRequest(canonicalScope, listingId, kind, desiredSelected),
            )
            if (visibleResult is DomainResult.Success) {
                eventPublisher.publishSubmitOutcome(visibleResult.value)
                wakeAccumulator.offerCurrent(
                    InteractionWakeRequest.immediate(canonicalScope),
                    viewerSessionScopeTracker,
                )
            }
            visibleResult
        } finally {
            lifecycleGate.endOperation(lease)
        }
    }

    suspend fun hydrate(
        expectedScope: InteractionAccountScope,
        listingIds: List<String>,
    ): DomainResult<InteractionHydration> {
        val canonicalScope = expectedScope.toInteractionLifecycleScope()
        val lifecycleGeneration = lifecycleGate.availableGeneration(
            expectedScope = canonicalScope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        ) ?: return DomainResult.Failure(DomainError.AuthenticationRequired())
        return when (val normalized = listingIds.normalizeForInteractionHydration(scopeAvailable = true)) {
            is DomainResult.Failure -> normalized
            is DomainResult.Success -> {
                val normalizedListingIds = normalized.value
                val loaded = if (normalizedListingIds.isEmpty()) {
                    DomainResult.Success(emptyList())
                } else {
                    loadHydrationChunks(canonicalScope, normalizedListingIds, lifecycleGeneration)
                }
                when (loaded) {
                    is DomainResult.Failure -> loaded
                    is DomainResult.Success -> {
                        val generationCurrent = lifecycleGate.isAvailableAtGeneration(
                            expectedScope = canonicalScope,
                            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
                            generation = lifecycleGeneration,
                        )
                        if (generationCurrent) {
                            DomainResult.Success(
                                loaded.value.toInteractionHydration(canonicalScope, normalizedListingIds),
                            )
                        } else {
                            DomainResult.Failure(DomainError.AuthenticationRequired())
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadHydrationChunks(
        expectedScope: InteractionAccountScope,
        listingIds: List<String>,
        lifecycleGeneration: InteractionAccountLifecycleGeneration,
    ): DomainResult<List<PendingInteraction>> {
        val loaded = mutableListOf<PendingInteraction>()
        for (chunk in listingIds.chunked(INTERACTION_HYDRATION_CHUNK_SIZE)) {
            val canRead = lifecycleGate.isAvailableAtGeneration(
                expectedScope = expectedScope,
                currentScope = viewerSessionScopeTracker.currentInteractionScope(),
                generation = lifecycleGeneration,
            )
            if (!canRead) {
                return DomainResult.Failure(DomainError.AuthenticationRequired())
            }
            val result = repository.loadPending(expectedScope.accountId, chunk)
            val canExpose = lifecycleGate.isAvailableAtGeneration(
                expectedScope = expectedScope,
                currentScope = viewerSessionScopeTracker.currentInteractionScope(),
                generation = lifecycleGeneration,
            )
            if (!canExpose) {
                return DomainResult.Failure(DomainError.AuthenticationRequired())
            }
            when (result) {
                is DomainResult.Failure -> return result
                is DomainResult.Success -> loaded += result.value
            }
        }
        return DomainResult.Success(loaded)
    }

    fun onForeground() {
        viewerSessionScopeTracker.currentInteractionScope()?.let { scope ->
            wakeAccumulator.offerCurrent(InteractionWakeRequest.reconciling(scope), viewerSessionScopeTracker)
        }
    }

    fun onScreenAppeared() {
        viewerSessionScopeTracker.currentInteractionScope()?.let { scope ->
            wakeAccumulator.offerCurrent(InteractionWakeRequest.reconciling(scope), viewerSessionScopeTracker)
        }
    }

    fun retryManually(expectedScope: InteractionAccountScope) {
        wakeAccumulator.offerCurrent(
            InteractionWakeRequest.manual(expectedScope.toInteractionLifecycleScope()),
            viewerSessionScopeTracker,
        )
    }

    suspend fun purgeForAccountDeletion(accountId: String): DomainResult<InteractionAccountDeletionPurgeOutcome> =
        purgeForAccountDeletion(accountId, onAcquired = {})

    internal suspend fun purgeForAccountDeletion(
        accountId: String,
        onAcquired: () -> Unit,
    ): DomainResult<InteractionAccountDeletionPurgeOutcome> {
        val lifecycleAccountId = accountId.toInteractionLifecycleAccountId()
        if (lifecycleAccountId.isEmpty()) {
            return DomainResult.Failure(
                DomainError.Validation("error.interaction.account_id_invalid"),
            )
        }
        return when (val registration = lifecycleGate.registerPurge(lifecycleAccountId)) {
            InteractionAccountDeletionPurgeRegistration.AlreadyPurged ->
                DomainResult.Success(InteractionAccountDeletionPurgeOutcome.AlreadyBlocked)

            is InteractionAccountDeletionPurgeRegistration.Existing -> registration.awaitAlreadyBlockedOutcome()

            is InteractionAccountDeletionPurgeRegistration.Owner -> awaitOwnedPurge(
                accountId = lifecycleAccountId,
                registration = registration,
                onAcquired = onAcquired,
            )
        }
    }

    private suspend fun awaitOwnedPurge(
        accountId: String,
        registration: InteractionAccountDeletionPurgeRegistration.Owner,
        onAcquired: () -> Unit,
    ): DomainResult<InteractionAccountDeletionPurgeOutcome> {
        launchOwnedPurge(accountId, registration)
        val result = try {
            registration.completion.await()
        } catch (cancellation: CancellationException) {
            coroutineScope.launch {
                if (registration.settlement.await() is DomainResult.Success) {
                    resumeAfterAccountDeletionFailure(accountId)
                }
            }
            throw cancellation
        }
        return when (result) {
            is DomainResult.Failure -> result
            is DomainResult.Success -> {
                onAcquired()
                DomainResult.Success(InteractionAccountDeletionPurgeOutcome.Acquired(result.value))
            }
        }
    }

    private fun launchOwnedPurge(accountId: String, registration: InteractionAccountDeletionPurgeRegistration.Owner) {
        coroutineScope.launch {
            var result: DomainResult<Int>? = null
            var failure: Throwable? = null
            try {
                val execution = runCatching {
                    registration.idle?.await()
                    repository.purge(accountId).also { purgeResult ->
                        if (purgeResult is DomainResult.Success) {
                            eventPublisher.invalidateDeliveryForPurgedAccount(
                                accountId = accountId,
                                currentScope = viewerSessionScopeTracker.currentInteractionScope(),
                            )
                        }
                    }
                }
                result = execution.getOrNull()
                failure = execution.exceptionOrNull()
                if (failure != null && failure !is Exception) throw failure
            } finally {
                lifecycleGate.finishPurge(accountId, registration, result, failure)
            }
        }
    }

    suspend fun resumeAfterAccountDeletionFailure(accountId: String) {
        val lifecycleAccountId = accountId.toInteractionLifecycleAccountId()
        if (lifecycleAccountId.isEmpty()) return
        val removed = lifecycleGate.resume(lifecycleAccountId)
        val scopeToWake = viewerSessionScopeTracker.currentInteractionScope().takeIf { scope ->
            removed && scope?.accountId?.toInteractionLifecycleAccountId() == lifecycleAccountId
        }
        scopeToWake?.let { scope ->
            wakeAccumulator.offerCurrent(InteractionWakeRequest.reconciling(scope), viewerSessionScopeTracker)
        }
    }

    private suspend fun processWake(request: InteractionWakeRequest) {
        val scope = viewerSessionScopeTracker.currentInteractionScope()
            ?.takeIf { currentScope -> request.scope == currentScope }
            ?: return
        scheduledWake?.cancel()
        scheduledWake = null
        if (request.retriesReconciliation) {
            eventPublisher.retryReconciliationIfCurrent(scope)
        }
        val lease = lifecycleGate.beginOperation(scope, scope) ?: return
        val operations = try {
            request.drainWithActiveLease(
                repository = repository,
                lifecycleGate = lifecycleGate,
                viewerSessionScopeTracker = viewerSessionScopeTracker,
                scope = scope,
            )?.successfulOperationsOrNull()?.also { outcomes ->
                outcomes.forEach { outcome -> eventPublisher.publishDrainOutcome(outcome) }
            } ?: return
        } finally {
            lifecycleGate.endOperation(lease)
        }
        when (val retryStatus = operations.automaticDrainStopStatusOrNull()) {
            is PendingInteractionStatus.Scheduled -> scheduleWakeAt(
                scope = scope,
                nextAttemptAt = retryStatus.nextAttemptAtEpochMilliseconds,
                minimumDelayMilliseconds = MIN_INTERACTION_RETRY_SCHEDULE_DELAY_MILLISECONDS,
            )
            PendingInteractionStatus.SuspendedForSession -> Unit
            null -> scheduleNextWake(scope)
            PendingInteractionStatus.SuspendedForManualRetry,
            is PendingInteractionStatus.Rejected,
            -> Unit
        }
    }

    private suspend fun scheduleNextWake(scope: InteractionAccountScope) {
        val initiallyAllowed = lifecycleGate.isAvailable(
            expectedScope = scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        )
        if (!initiallyAllowed) return
        val nextAttemptAt = when (val result = repository.nextAttemptAt(scope.accountId)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> null
        } ?: return
        scheduleWakeAt(scope, nextAttemptAt, minimumDelayMilliseconds = 0L)
    }

    private suspend fun scheduleWakeAt(
        scope: InteractionAccountScope,
        nextAttemptAt: Long,
        minimumDelayMilliseconds: Long,
    ) {
        val allowed = lifecycleGate.isAvailable(
            expectedScope = scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        )
        if (!allowed) return
        val now = clockProvider.nowEpochMilliseconds().coerceAtLeast(0L)
        val delayMilliseconds = min(
            (nextAttemptAt - now).coerceAtLeast(minimumDelayMilliseconds),
            MAX_INTERACTION_SCHEDULE_DELAY_MILLISECONDS,
        )
        lifecycleGate.runIfAvailable(
            expectedScope = scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        ) {
            scheduledWake = coroutineScope.launch {
                delay(delayMilliseconds)
                wakeAccumulator.offerCurrent(InteractionWakeRequest.immediate(scope), viewerSessionScopeTracker)
            }
        }
    }
}

private fun InteractionWakeAccumulator.offerCurrent(
    request: InteractionWakeRequest,
    viewerSessionScopeTracker: ViewerSessionScopeTracker,
) {
    offer(request, viewerSessionScopeTracker.currentInteractionScope())
}

private suspend fun InteractionRepository.submitWithActiveLease(
    lifecycleGate: InteractionLifecycleGate,
    viewerSessionScopeTracker: ViewerSessionScopeTracker,
    request: InteractionActiveSubmitRequest,
): DomainResult<InteractionSubmitOutcome> {
    val normalizedListingId = request.listingId.trim()
    if (normalizedListingId.isEmpty()) {
        return DomainResult.Failure(DomainError.Validation("error.interaction.listing_id_invalid"))
    }
    val command = InteractionCommand(request.scope, normalizedListingId, request.kind, request.desiredSelected)
    val result = submit(command)
    val accountBlocked = !lifecycleGate.isAvailable(
        expectedScope = request.scope,
        currentScope = viewerSessionScopeTracker.currentInteractionScope(),
    )
    return result.hideAfterLifecycleTransition(
        currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        accountBlocked = accountBlocked,
    )
}

private data class InteractionActiveSubmitRequest(
    val scope: InteractionAccountScope,
    val listingId: String,
    val kind: InteractionKind,
    val desiredSelected: Boolean,
)

internal class ViewerSessionActiveInteractionScopeProvider(
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
) : ActiveInteractionScopeProvider {
    override fun currentScope(): InteractionAccountScope? =
        viewerSessionScopeTracker.currentScope.toInteractionScopeOrNull()
}

private fun ViewerSessionScope.toInteractionScopeOrNull(): InteractionAccountScope? = accountId
    ?.toInteractionLifecycleAccountId()
    ?.takeIf(String::isNotEmpty)
    ?.let { accountId -> InteractionAccountScope(accountId = accountId, epoch = epoch) }

internal fun ViewerSessionScopeTracker.currentInteractionScope(): InteractionAccountScope? =
    currentScope.toInteractionScopeOrNull()

private fun String.toInteractionLifecycleAccountId(): String = trim().lowercase()

private fun InteractionAccountScope.toInteractionLifecycleScope(): InteractionAccountScope = copy(
    accountId = accountId.toInteractionLifecycleAccountId(),
)

private fun List<String>.normalizeForInteractionHydration(scopeAvailable: Boolean): DomainResult<List<String>> {
    if (!scopeAvailable) return DomainResult.Failure(DomainError.AuthenticationRequired())
    val normalized = linkedSetOf<String>()
    for (listingId in this) {
        val canonicalListingId = listingId.trim().lowercase()
        if (canonicalListingId.isEmpty()) continue
        normalized += canonicalListingId
        if (normalized.size > MAX_INTERACTION_HYDRATION_LISTING_IDS) {
            return DomainResult.Failure(
                DomainError.Validation(TOO_MANY_INTERACTION_LISTING_IDS_ERROR_KEY),
            )
        }
    }
    return DomainResult.Success(normalized.toList())
}

private fun DomainResult<InteractionSubmitOutcome>.hideAfterLifecycleTransition(
    currentScope: InteractionAccountScope?,
    accountBlocked: Boolean,
): DomainResult<InteractionSubmitOutcome> = when (this) {
    is DomainResult.Failure -> this
    is DomainResult.Success -> when (val outcome = value) {
        is InteractionSubmitOutcome.Superseded -> this
        is InteractionSubmitOutcome.Queued -> if (
            outcome.command.scope == currentScope && !accountBlocked
        ) {
            this
        } else {
            DomainResult.Success(
                InteractionSubmitOutcome.Superseded(
                    command = outcome.command,
                    operationId = outcome.pending.operationId,
                ),
            )
        }
    }
}

private fun List<PendingInteraction>.toOverlays(): List<InteractionOverlay> =
    groupBy(PendingInteraction::listingId).map { (listingId, interactions) ->
        InteractionOverlay(
            listingId = listingId,
            liked = interactions
                .lastOrNull { interaction -> interaction.kind == InteractionKind.Like }
                ?.desiredSelected,
            favorited = interactions
                .lastOrNull { interaction -> interaction.kind == InteractionKind.Favorite }
                ?.desiredSelected,
            pending = interactions,
        )
    }

private fun List<PendingInteraction>.toInteractionHydration(
    scope: InteractionAccountScope,
    listingIds: List<String>,
): InteractionHydration {
    val accountId = scope.accountId.toInteractionLifecycleAccountId()
    val requestedListingIds = listingIds.toSet()
    val hydratable = filter { pending ->
        pending.accountId.toInteractionLifecycleAccountId() == accountId &&
            pending.listingId in requestedListingIds &&
            pending.isHydratable
    }
    return InteractionHydration(
        scope = scope,
        overlays = hydratable.toOverlays(),
        pending = hydratable,
    )
}

private suspend fun InteractionWakeRequest.retryAccountIfNeeded(
    repository: InteractionRepository,
    scope: InteractionAccountScope,
) {
    when (retryMode) {
        InteractionWakeRetryMode.Automatic -> repository.retryAccount(
            scope = scope,
            includeManualFailures = false,
        )
        InteractionWakeRetryMode.Manual -> repository.retryAccount(
            scope = scope,
            includeManualFailures = true,
        )
        InteractionWakeRetryMode.None -> Unit
    }
}

private suspend fun InteractionWakeRequest.drainWithActiveLease(
    repository: InteractionRepository,
    lifecycleGate: InteractionLifecycleGate,
    viewerSessionScopeTracker: ViewerSessionScopeTracker,
    scope: InteractionAccountScope,
): DomainResult<InteractionDrainOutcome>? {
    retryAccountIfNeeded(repository, scope)
    return if (
        lifecycleGate.isAvailable(
            expectedScope = scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        )
    ) {
        repository.drainDue(scope)
    } else {
        null
    }
}

private fun DomainResult<InteractionDrainOutcome>.successfulOperationsOrNull(): List<InteractionOperationOutcome>? =
    when (this) {
        is DomainResult.Success -> value.operations
        is DomainResult.Failure -> null
    }

private fun List<InteractionOperationOutcome>.automaticDrainStopStatusOrNull(): PendingInteractionStatus? {
    for (outcome in this) {
        if (outcome !is InteractionOperationOutcome.Retrying) continue
        when (val status = outcome.pending.status) {
            is PendingInteractionStatus.Scheduled,
            PendingInteractionStatus.SuspendedForSession,
            -> return status
            PendingInteractionStatus.SuspendedForManualRetry,
            is PendingInteractionStatus.Rejected,
            -> Unit
        }
    }
    return null
}
