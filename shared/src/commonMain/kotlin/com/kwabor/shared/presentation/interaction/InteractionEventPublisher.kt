package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionOperationOutcome
import com.kwabor.shared.domain.interaction.InteractionSubmitOutcome
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

private const val INTERACTION_EVENT_BUFFER_CAPACITY = 100
private const val INTERACTION_RECONCILIATION_WATERMARK_CAPACITY = 1_000
private const val INTERACTION_DELIVERY_INVALIDATION_ACCOUNT_CAPACITY = 1_000

internal class InteractionEventPublisher(
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
    private val lifecycleGate: InteractionLifecycleGate,
) {
    private val mutableEvents = MutableSharedFlow<InteractionCoordinatorEvent>(
        replay = 0,
        extraBufferCapacity = INTERACTION_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<InteractionCoordinatorEvent> = mutableEvents.asSharedFlow()
    private val mutableReconciliationSignals = MutableStateFlow<InteractionReconciliationSignal?>(null)
    private val invalidatedDeliveryWatermarks = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val globalInvalidatedDeliveryWatermark = MutableStateFlow(0L)
    private val publicationMutex = Mutex()
    private var lastDeliverySequence = 0L
    private var lastReconciliationStateVersion = 0L
    val reconciliationSignals: StateFlow<InteractionReconciliationSignal?> =
        mutableReconciliationSignals.asStateFlow()

    suspend fun publishSubmitOutcome(outcome: InteractionSubmitOutcome) {
        val event = when (outcome) {
            is InteractionSubmitOutcome.Queued -> InteractionCoordinatorEvent.Queued(
                scope = outcome.command.scope,
                deliverySequence = 0L,
                command = outcome.command,
                pending = outcome.pending,
            )
            is InteractionSubmitOutcome.Superseded -> InteractionCoordinatorEvent.Superseded(
                scope = outcome.command.scope,
                deliverySequence = 0L,
                command = outcome.command,
                operationId = outcome.operationId,
            )
        }
        publishIfCurrent(event)
    }

    suspend fun publishDrainOutcome(outcome: InteractionOperationOutcome) {
        val event = when (outcome) {
            is InteractionOperationOutcome.Confirmed -> InteractionCoordinatorEvent.Confirmed(
                scope = outcome.command.scope,
                deliverySequence = 0L,
                command = outcome.command,
                confirmation = outcome.confirmation,
            )
            is InteractionOperationOutcome.Retrying -> InteractionCoordinatorEvent.Retrying(
                scope = outcome.command.scope,
                deliverySequence = 0L,
                command = outcome.command,
                pending = outcome.pending,
            )
            is InteractionOperationOutcome.Rejected -> InteractionCoordinatorEvent.Rejected(
                scope = outcome.command.scope,
                deliverySequence = 0L,
                command = outcome.command,
                operationId = outcome.operationId,
                reason = outcome.reason,
            )
            is InteractionOperationOutcome.Superseded -> InteractionCoordinatorEvent.Superseded(
                scope = outcome.command.scope,
                deliverySequence = 0L,
                command = outcome.command,
                operationId = outcome.operationId,
            )
        }
        publishIfCurrent(event)
    }

    suspend fun retryReconciliationIfCurrent(scope: InteractionAccountScope) {
        val lease = lifecycleGate.beginOperation(
            expectedScope = scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        ) ?: return
        try {
            mutableReconciliationSignals.update { previous ->
                previous
                    ?.takeIf { signal -> signal.scope == scope }
                    ?.copyWithNextRevision()
            }
        } finally {
            lifecycleGate.endOperation(lease)
        }
    }

    suspend fun acknowledgeReconciliation(
        signal: InteractionReconciliationSignal,
        consumer: InteractionReconciliationConsumer,
    ): Boolean {
        val lease = lifecycleGate.beginOperation(
            expectedScope = signal.scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        ) ?: return false
        return try {
            mutableReconciliationSignals.applyAcknowledgement(signal, consumer)
        } finally {
            lifecycleGate.endOperation(lease)
        }
    }

    suspend fun requestReconciliationIfCurrent(event: InteractionCoordinatorEvent): Boolean {
        var requested = false
        publicationMutex.lock()
        try {
            lifecycleGate.runIfAvailable(
                expectedScope = event.scope,
                currentScope = viewerSessionScopeTracker.currentInteractionScope(),
            ) {
                if (isDeliveryValid(event)) {
                    val stateVersion = nextReconciliationStateVersion()
                    while (true) {
                        val previous = mutableReconciliationSignals.value
                        val updated = previous.nextSignal(
                            event = event,
                            stateVersion = stateVersion,
                            forcePendingValidation = true,
                        )
                        if (mutableReconciliationSignals.compareAndSet(previous, updated)) {
                            requested = true
                            break
                        }
                    }
                }
            }
        } finally {
            publicationMutex.unlock()
        }
        return requested
    }

    suspend fun invalidateDeliveryForPurgedAccount(accountId: String, currentScope: InteractionAccountScope?) {
        publicationMutex.lock()
        try {
            val invalidationWatermark = lastDeliverySequence
            val currentInvalidations = invalidatedDeliveryWatermarks.value
            val usesGlobalInvalidation = accountId !in currentInvalidations &&
                currentInvalidations.size >= INTERACTION_DELIVERY_INVALIDATION_ACCOUNT_CAPACITY
            if (usesGlobalInvalidation) {
                globalInvalidatedDeliveryWatermark.value = maxOf(
                    globalInvalidatedDeliveryWatermark.value,
                    invalidationWatermark,
                )
            } else {
                invalidatedDeliveryWatermarks.value = currentInvalidations +
                    (accountId to maxOf(currentInvalidations[accountId] ?: 0L, invalidationWatermark))
            }
            mutableReconciliationSignals.update { previous ->
                val reconciliationScope = currentScope?.takeIf { scope ->
                    usesGlobalInvalidation || scope.accountId == accountId
                }
                if (reconciliationScope != null) {
                    previous.nextPurgeSignal(
                        scope = reconciliationScope,
                        deliveryWatermark = invalidationWatermark,
                        stateVersion = nextReconciliationStateVersion(),
                    )
                } else {
                    previous?.takeUnless { signal -> signal.scope.accountId == accountId }
                }
            }
        } finally {
            publicationMutex.unlock()
        }
    }

    fun isDeliveryValid(event: InteractionCoordinatorEvent): Boolean {
        val accountWatermark = invalidatedDeliveryWatermarks.value[event.scope.accountId] ?: 0L
        val invalidationWatermark = maxOf(accountWatermark, globalInvalidatedDeliveryWatermark.value)
        return event.deliverySequence > invalidationWatermark
    }

    suspend fun runIfDeliveryValid(event: InteractionCoordinatorEvent, action: suspend () -> Unit): Boolean {
        val lease = lifecycleGate.beginOperation(
            expectedScope = event.scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        ) ?: return false
        return try {
            if (!isDeliveryValid(event)) return false
            action()
            true
        } finally {
            lifecycleGate.endOperation(lease)
        }
    }

    suspend fun runIfReconciliationCurrent(
        signal: InteractionReconciliationSignal,
        action: suspend () -> Unit,
    ): Boolean {
        val lease = lifecycleGate.beginOperation(
            expectedScope = signal.scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        ) ?: return false
        return try {
            if (!mutableReconciliationSignals.value.representsSameStateAs(signal)) return false
            action()
            true
        } finally {
            lifecycleGate.endOperation(lease)
        }
    }

    private suspend fun publishIfCurrent(event: InteractionCoordinatorEvent) {
        publicationMutex.lock()
        try {
            lifecycleGate.runIfAvailable(
                expectedScope = event.scope,
                currentScope = viewerSessionScopeTracker.currentInteractionScope(),
            ) {
                publishOrRequestReconciliation(event)
            }
        } finally {
            publicationMutex.unlock()
        }
    }

    private fun publishOrRequestReconciliation(event: InteractionCoordinatorEvent) {
        val deliverySequence = nextDeliverySequenceOrNull()
        if (deliverySequence == null) {
            mutableReconciliationSignals.update { previous ->
                previous.nextSignal(
                    event = event.withDeliverySequence(Long.MAX_VALUE),
                    stateVersion = nextReconciliationStateVersion(),
                    forcePendingValidation = true,
                )
            }
            return
        }
        val sequencedEvent = event.withDeliverySequence(deliverySequence)
        if (mutableEvents.tryEmit(sequencedEvent)) return
        mutableReconciliationSignals.update { previous ->
            previous.nextSignal(
                event = sequencedEvent,
                stateVersion = nextReconciliationStateVersion(),
            )
        }
    }

    private fun nextDeliverySequenceOrNull(): Long? {
        if (lastDeliverySequence == Long.MAX_VALUE) return null
        lastDeliverySequence += 1L
        return lastDeliverySequence
    }

    private fun nextReconciliationStateVersion(): Long {
        if (lastReconciliationStateVersion < Long.MAX_VALUE) {
            lastReconciliationStateVersion += 1L
        }
        return lastReconciliationStateVersion
    }
}

private data class InteractionReconciliationAcknowledgementUpdate(
    val current: InteractionReconciliationSignal,
    val updated: InteractionReconciliationSignal?,
)

private fun MutableStateFlow<InteractionReconciliationSignal?>.applyAcknowledgement(
    signal: InteractionReconciliationSignal,
    consumer: InteractionReconciliationConsumer,
): Boolean {
    var applied = false
    var retry = true
    while (retry) {
        val update = prepareAcknowledgement(signal, consumer)
        retry = update != null && !compareAndSet(update.current, update.updated)
        applied = update != null && !retry
    }
    return applied
}

private fun MutableStateFlow<InteractionReconciliationSignal?>.prepareAcknowledgement(
    signal: InteractionReconciliationSignal,
    consumer: InteractionReconciliationConsumer,
): InteractionReconciliationAcknowledgementUpdate? {
    val current = value ?: return null
    val acknowledged = current.acknowledgedConsumers + consumer
    return if (!current.matchesRevision(signal) || acknowledged == current.acknowledgedConsumers) {
        null
    } else {
        val updated = if (acknowledged.containsAll(InteractionReconciliationConsumer.entries)) {
            null
        } else {
            current.copyWithAcknowledgedConsumers(acknowledged)
        }
        InteractionReconciliationAcknowledgementUpdate(current, updated)
    }
}

private fun InteractionReconciliationSignal?.nextSignal(
    event: InteractionCoordinatorEvent,
    stateVersion: Long,
    forcePendingValidation: Boolean = false,
): InteractionReconciliationSignal {
    val retained = this?.takeIf { signal -> signal.scope == event.scope }
    val watermarks = retained?.terminalWatermarks.orEmpty()
    val terminal = event.terminalWatermarkOrNull()
    val canStoreTerminal = terminal == null ||
        terminal.first in watermarks ||
        watermarks.size < INTERACTION_RECONCILIATION_WATERMARK_CAPACITY
    val updatedWatermarks = if (terminal != null && canStoreTerminal) {
        watermarks + (terminal.first to maxOf(watermarks[terminal.first] ?: 0L, terminal.second))
    } else {
        watermarks
    }
    return InteractionReconciliationSignal(
        scope = event.scope,
        revision = nextRevision(),
        stateVersion = stateVersion,
        deliveryWatermark = maxOf(retained?.deliveryWatermark ?: 0L, event.deliverySequence),
        terminalWatermarks = updatedWatermarks,
        status = InteractionReconciliationStatus(
            requiresPendingValidation = retained?.requiresPendingValidation == true ||
                forcePendingValidation ||
                terminal == null ||
                !canStoreTerminal,
            acknowledgedConsumers = emptySet(),
        ),
    )
}

private fun InteractionReconciliationSignal?.nextPurgeSignal(
    scope: InteractionAccountScope,
    deliveryWatermark: Long,
    stateVersion: Long,
): InteractionReconciliationSignal {
    val retained = this?.takeIf { signal -> signal.scope == scope }
    return InteractionReconciliationSignal(
        scope = scope,
        revision = nextRevision(),
        stateVersion = stateVersion,
        deliveryWatermark = maxOf(retained?.deliveryWatermark ?: 0L, deliveryWatermark),
        terminalWatermarks = emptyMap(),
        status = InteractionReconciliationStatus(
            requiresPendingValidation = true,
            acknowledgedConsumers = emptySet(),
        ),
    )
}

private fun InteractionReconciliationSignal.copyWithNextRevision(): InteractionReconciliationSignal =
    InteractionReconciliationSignal(
        scope = scope,
        revision = nextRevision(),
        stateVersion = stateVersion,
        deliveryWatermark = deliveryWatermark,
        terminalWatermarks = terminalWatermarks,
        status = status,
    )

private fun InteractionReconciliationSignal.copyWithAcknowledgedConsumers(
    acknowledged: Set<InteractionReconciliationConsumer>,
): InteractionReconciliationSignal = InteractionReconciliationSignal(
    scope = scope,
    revision = revision,
    stateVersion = stateVersion,
    deliveryWatermark = deliveryWatermark,
    terminalWatermarks = terminalWatermarks,
    status = status.copy(acknowledgedConsumers = acknowledged),
)

private fun InteractionReconciliationSignal.matchesRevision(expected: InteractionReconciliationSignal): Boolean =
    this === expected

private fun InteractionReconciliationSignal?.representsSameStateAs(
    expected: InteractionReconciliationSignal,
): Boolean {
    if (this == null || scope != expected.scope) return false
    if (expected.stateVersion != Long.MAX_VALUE) return stateVersion == expected.stateVersion
    if (expected.revision != Long.MAX_VALUE) return revision == expected.revision
    return this === expected
}

private fun InteractionCoordinatorEvent.withDeliverySequence(sequence: Long): InteractionCoordinatorEvent =
    when (this) {
        is InteractionCoordinatorEvent.Queued -> copy(deliverySequence = sequence)
        is InteractionCoordinatorEvent.Confirmed -> copy(deliverySequence = sequence)
        is InteractionCoordinatorEvent.Retrying -> copy(deliverySequence = sequence)
        is InteractionCoordinatorEvent.Rejected -> copy(deliverySequence = sequence)
        is InteractionCoordinatorEvent.Superseded -> copy(deliverySequence = sequence)
    }

private fun InteractionCoordinatorEvent.terminalWatermarkOrNull(): Pair<InteractionReconciliationKey, Long>? {
    val operationId = when (this) {
        is InteractionCoordinatorEvent.Confirmed -> confirmation.operationId
        is InteractionCoordinatorEvent.Rejected -> operationId
        is InteractionCoordinatorEvent.Superseded -> operationId
        is InteractionCoordinatorEvent.Queued,
        is InteractionCoordinatorEvent.Retrying,
        -> return null
    }
    return InteractionReconciliationKey(command.listingId, command.kind) to operationId
}

private fun InteractionReconciliationSignal?.nextRevision(): Long = when (val current = this?.revision) {
    null -> 1L
    Long.MAX_VALUE -> Long.MAX_VALUE
    else -> current + 1L
}
