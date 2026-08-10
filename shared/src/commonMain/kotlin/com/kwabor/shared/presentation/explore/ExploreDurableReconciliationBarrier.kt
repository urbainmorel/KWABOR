package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.interaction.InteractionReconciliationConsumer
import com.kwabor.shared.presentation.interaction.InteractionReconciliationSignal
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ExploreDurableReconciliationBarrier(
    private val coordinator: InteractionCoordinator?,
    private val strings: KwaborStrings,
    private val stateStore: ExploreStateStore,
    private val interactionMutex: Mutex,
    private val callbacks: ExploreDurableRuntimeCallbacks,
    private val reconcileCurrentVisible: suspend (
        InteractionReconciliationSignal,
        ViewerSessionScope,
        Boolean,
    ) -> Boolean,
) {
    private var reconciliationScope: InteractionAccountScope? = null
    private var lastSuccessfulStateVersion = 0L
    private var lastSuccessfulDeliveryWatermark = 0L

    suspend fun handle(signal: InteractionReconciliationSignal) {
        val durableCoordinator = coordinator ?: return
        val expectedScope = callbacks.currentViewerScope() ?: return
        if (!expectedScope.isAuthenticated || !signal.scope.matches(expectedScope)) return
        val currentPreparation = prepareCurrentSignal(durableCoordinator, signal, expectedScope) ?: return
        if (currentPreparation.alreadySucceeded) {
            acknowledge(durableCoordinator, signal)
            return
        }
        val reconciledCurrentScope = reconcileCurrentVisible(
            signal,
            expectedScope,
            currentPreparation.requiresAuthoritativeReconciliation,
        )
        if (reconciledCurrentScope && markSuccessful(durableCoordinator, signal)) {
            acknowledge(durableCoordinator, signal)
        }
    }

    suspend fun shouldRevalidatePendingEvent(event: InteractionCoordinatorEvent): Boolean {
        when (event) {
            is InteractionCoordinatorEvent.Queued,
            is InteractionCoordinatorEvent.Retrying,
            -> Unit
            is InteractionCoordinatorEvent.Confirmed,
            is InteractionCoordinatorEvent.Rejected,
            is InteractionCoordinatorEvent.Superseded,
            -> return false
        }
        val expectedScope = callbacks.currentViewerScope() ?: return false
        if (!expectedScope.isAuthenticated || !event.scope.matches(expectedScope)) return false
        return interactionMutex.withLock {
            ensureScopeLocked(event.scope)
            val signal = coordinator?.reconciliationSignals?.value
                ?.takeIf { current -> current.scope == event.scope }
            val watermarksApplied = signal?.let { current ->
                ensureScopeLocked(current.scope)
                stateStore.applyReconciliationWatermarks(current, expectedScope, strings) != null
            } ?: true
            val deliveryWatermark = maxOf(
                lastSuccessfulDeliveryWatermark,
                signal?.deliveryWatermark ?: 0L,
            )
            !watermarksApplied || event.deliverySequence <= deliveryWatermark
        }
    }

    private fun rememberSignalLocked(signal: InteractionReconciliationSignal) {
        ensureScopeLocked(signal.scope)
    }

    private suspend fun prepareCurrentSignal(
        durableCoordinator: InteractionCoordinator,
        signal: InteractionReconciliationSignal,
        expectedScope: ViewerSessionScope,
    ): ExploreReconciliationPreparation? {
        var preparation: ExploreReconciliationPreparation? = null
        val accepted = durableCoordinator.deliveryCommitGate.runIfReconciliationCurrent(signal) {
            preparation = interactionMutex.withLock {
                rememberSignalLocked(signal)
                val watermarkCommit = stateStore.applyReconciliationWatermarks(signal, expectedScope, strings)
                    ?: return@withLock null
                ExploreReconciliationPreparation(
                    alreadySucceeded = signal.stateVersion != Long.MAX_VALUE &&
                        lastSuccessfulStateVersion >= signal.stateVersion,
                    requiresAuthoritativeReconciliation = signal.requiresPendingValidation ||
                        watermarkCommit.requiresAuthoritativeReconciliation,
                )
            }
        }
        return preparation.takeIf { accepted }
    }

    private suspend fun markSuccessful(
        durableCoordinator: InteractionCoordinator,
        signal: InteractionReconciliationSignal,
    ): Boolean {
        var markedSuccessful = false
        durableCoordinator.deliveryCommitGate.runIfReconciliationCurrent(signal) {
            interactionMutex.withLock {
                lastSuccessfulStateVersion = maxOf(lastSuccessfulStateVersion, signal.stateVersion)
                lastSuccessfulDeliveryWatermark = maxOf(lastSuccessfulDeliveryWatermark, signal.deliveryWatermark)
                markedSuccessful = true
            }
        }
        return markedSuccessful
    }

    private suspend fun acknowledge(
        durableCoordinator: InteractionCoordinator,
        signal: InteractionReconciliationSignal,
    ) {
        durableCoordinator.deliveryCommitGate.acknowledgeReconciliation(
            signal,
            InteractionReconciliationConsumer.Explore,
        )
    }

    private fun ensureScopeLocked(scope: InteractionAccountScope) {
        if (reconciliationScope == scope) return
        reconciliationScope = scope
        lastSuccessfulStateVersion = 0L
        lastSuccessfulDeliveryWatermark = 0L
    }
}

private data class ExploreReconciliationPreparation(
    val alreadySucceeded: Boolean,
    val requiresAuthoritativeReconciliation: Boolean,
)
