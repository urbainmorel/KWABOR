package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.interaction.InteractionReconciliationConsumer
import com.kwabor.shared.presentation.interaction.InteractionReconciliationSignal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FavoritesDurableReconciliationBarrier(
    private val coordinator: InteractionCoordinator,
    private val lifecycleMutex: Mutex,
    private val durableState: FavoritesDurableState,
    private val hydrate: suspend (FavoritesDurableReconciliationRequest) -> FavoritesDurableHydration?,
    private val refreshActionLocked: (FavoritesPageCoordinator) -> FavoritesPageAction,
) {
    private var reconciliationScope: InteractionAccountScope? = null
    private var lastSuccessfulStateVersion = 0L
    private var lastSuccessfulDeliveryWatermark = 0L

    suspend fun handle(signal: InteractionReconciliationSignal, pageCoordinator: FavoritesPageCoordinator): Boolean {
        val currentPreparation = prepareCurrentSignal(signal) ?: return false
        if (currentPreparation.alreadySucceeded) {
            return acknowledge(signal)
        }
        val hydration = hydrate(currentPreparation.request) ?: return false
        val committedAction = commitCurrentSignal(
            signal,
            currentPreparation.request,
            hydration,
            pageCoordinator,
        ) ?: return false
        val acknowledged = acknowledge(signal)
        pageCoordinator.runAction(committedAction)
        return acknowledged
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
        return lifecycleMutex.withLock {
            val scope = durableState.currentScopeForLocked(event.scope) ?: return@withLock false
            ensureScopeLocked(event.scope)
            val signal = coordinator.reconciliationSignals.value
                ?.takeIf { current -> current.scope == event.scope }
            val watermarksApplied = signal?.let { current ->
                ensureScopeLocked(current.scope)
                durableState.reconciliation.applyWatermarksLocked(current, scope) != null
            } ?: true
            val deliveryWatermark = maxOf(
                lastSuccessfulDeliveryWatermark,
                signal?.deliveryWatermark ?: 0L,
            )
            !watermarksApplied || event.deliverySequence <= deliveryWatermark
        }
    }

    suspend fun revalidatePendingEvent(
        event: InteractionCoordinatorEvent,
        pageCoordinator: FavoritesPageCoordinator,
    ): Boolean {
        val currentRequest = preparePendingEvent(event) ?: return false
        val hydration = hydrate(currentRequest) ?: return false
        val committedAction = commitPendingEvent(event, currentRequest, hydration, pageCoordinator) ?: return false
        pageCoordinator.runAction(committedAction)
        return true
    }

    private suspend fun prepareCurrentSignal(
        signal: InteractionReconciliationSignal,
    ): FavoritesReconciliationPreparation? {
        var preparation: FavoritesReconciliationPreparation? = null
        val accepted = coordinator.deliveryCommitGate.runIfReconciliationCurrent(signal) {
            preparation = lifecycleMutex.withLock {
                val scope = durableState.currentScopeForLocked(signal.scope) ?: return@withLock null
                rememberSignalLocked(signal)
                durableState.reconciliation.applyWatermarksLocked(signal, scope) ?: return@withLock null
                val request = durableState.reconciliation.prepareLocked(scope) ?: return@withLock null
                FavoritesReconciliationPreparation(
                    request = request,
                    alreadySucceeded = signal.stateVersion != Long.MAX_VALUE &&
                        lastSuccessfulStateVersion >= signal.stateVersion,
                )
            }
        }
        return preparation.takeIf { accepted }
    }

    private suspend fun commitCurrentSignal(
        signal: InteractionReconciliationSignal,
        request: FavoritesDurableReconciliationRequest,
        hydration: FavoritesDurableHydration,
        pageCoordinator: FavoritesPageCoordinator,
    ): FavoritesPageAction? {
        var action: FavoritesPageAction? = null
        val accepted = coordinator.deliveryCommitGate.runIfReconciliationCurrent(signal) {
            action = lifecycleMutex.withLock {
                if (!durableState.hasExactScopeLocked(request.scope)) return@withLock null
                durableState.reconciliation.applyLocked(request, hydration) ?: return@withLock null
                lastSuccessfulStateVersion = maxOf(lastSuccessfulStateVersion, signal.stateVersion)
                lastSuccessfulDeliveryWatermark = maxOf(lastSuccessfulDeliveryWatermark, signal.deliveryWatermark)
                refreshActionLocked(pageCoordinator)
            }
        }
        return action.takeIf { accepted }
    }

    private suspend fun preparePendingEvent(
        event: InteractionCoordinatorEvent,
    ): FavoritesDurableReconciliationRequest? {
        var request: FavoritesDurableReconciliationRequest? = null
        val accepted = coordinator.deliveryCommitGate.runIfEventDeliveryValid(event) {
            request = lifecycleMutex.withLock {
                val scope = durableState.currentScopeForLocked(event.scope) ?: return@withLock null
                durableState.reconciliation.prepareLocked(scope, listOf(event.command.listingId))
            }
        }
        return request.takeIf { accepted }
    }

    private suspend fun commitPendingEvent(
        event: InteractionCoordinatorEvent,
        request: FavoritesDurableReconciliationRequest,
        hydration: FavoritesDurableHydration,
        pageCoordinator: FavoritesPageCoordinator,
    ): FavoritesPageAction? {
        var action: FavoritesPageAction? = null
        val accepted = coordinator.deliveryCommitGate.runIfEventDeliveryValid(event) {
            action = lifecycleMutex.withLock {
                durableState.reconciliation.applyLocked(request, hydration) ?: return@withLock null
                refreshActionLocked(pageCoordinator)
            }
        }
        return action.takeIf { accepted }
    }

    private suspend fun acknowledge(signal: InteractionReconciliationSignal): Boolean =
        coordinator.deliveryCommitGate.acknowledgeReconciliation(
            signal,
            InteractionReconciliationConsumer.Favorites,
        )

    private fun rememberSignalLocked(signal: InteractionReconciliationSignal) {
        ensureScopeLocked(signal.scope)
    }

    private fun ensureScopeLocked(scope: InteractionAccountScope) {
        if (reconciliationScope == scope) return
        reconciliationScope = scope
        lastSuccessfulStateVersion = 0L
        lastSuccessfulDeliveryWatermark = 0L
    }
}

private data class FavoritesReconciliationPreparation(
    val request: FavoritesDurableReconciliationRequest,
    val alreadySucceeded: Boolean,
)
