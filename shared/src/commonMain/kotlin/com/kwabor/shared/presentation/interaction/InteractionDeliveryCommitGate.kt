package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker

internal class InteractionDeliveryCommitGate(
    private val eventPublisher: InteractionEventPublisher,
    private val lifecycleGate: InteractionLifecycleGate,
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
) {
    suspend fun acknowledgeReconciliation(
        signal: InteractionReconciliationSignal,
        consumer: InteractionReconciliationConsumer,
    ): Boolean = eventPublisher.acknowledgeReconciliation(signal, consumer)

    fun isEventDeliveryValid(event: InteractionCoordinatorEvent): Boolean = eventPublisher.isDeliveryValid(event)

    suspend fun runIfEventDeliveryValid(event: InteractionCoordinatorEvent, action: suspend () -> Unit): Boolean =
        eventPublisher.runIfDeliveryValid(event, action)

    suspend fun requestReconciliationFor(event: InteractionCoordinatorEvent): Boolean =
        eventPublisher.requestReconciliationIfCurrent(event)

    suspend fun runIfReconciliationCurrent(
        signal: InteractionReconciliationSignal,
        action: suspend () -> Unit,
    ): Boolean = eventPublisher.runIfReconciliationCurrent(signal, action)

    suspend fun captureLifecycleGeneration(scope: InteractionAccountScope): InteractionAccountLifecycleGeneration? =
        lifecycleGate.availableGeneration(
            expectedScope = scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        )

    suspend fun runIfLifecycleGenerationCurrent(
        scope: InteractionAccountScope,
        generation: InteractionAccountLifecycleGeneration,
        action: suspend () -> Unit,
    ): Boolean {
        val lease = lifecycleGate.beginOperation(
            expectedScope = scope,
            currentScope = viewerSessionScopeTracker.currentInteractionScope(),
        ) ?: return false
        return try {
            val current = lifecycleGate.isAvailableAtGeneration(
                expectedScope = scope,
                currentScope = viewerSessionScopeTracker.currentInteractionScope(),
                generation = generation,
            )
            if (!current) return false
            action()
            true
        } finally {
            lifecycleGate.endOperation(lease)
        }
    }
}
