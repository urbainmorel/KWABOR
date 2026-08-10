package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.interaction.InteractionReconciliationSignal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FavoritesDurableOverflowAccumulator {
    private val mutex = Mutex()
    private var activeScope: InteractionAccountScope? = null
    private var queuedOrHandlingCount = 0
    private var latestDroppedEvent: InteractionCoordinatorEvent? = null
    private var publishedRequest: FavoritesDurableOverflowRequest? = null

    suspend fun offer(
        event: InteractionCoordinatorEvent,
        currentScope: InteractionAccountScope?,
        tryEnqueue: () -> Boolean,
    ): FavoritesDurableOverflowRequest? = mutex.withLock {
        resetScopeLocked(currentScope)
        if (tryEnqueue()) {
            check(queuedOrHandlingCount < Int.MAX_VALUE) { "Favorites durable event count overflow." }
            queuedOrHandlingCount += 1
            return@withLock null
        }
        if (event.scope == activeScope && event.isNewerThan(latestDroppedEvent)) {
            latestDroppedEvent = event
        }
        promoteIfIdleLocked()
    }

    suspend fun eventHandled(): FavoritesDurableOverflowRequest? = mutex.withLock {
        check(queuedOrHandlingCount > 0) { "Favorites durable event count underflow." }
        queuedOrHandlingCount -= 1
        promoteIfIdleLocked()
    }

    suspend fun resetScope(scope: InteractionAccountScope?) {
        mutex.withLock { resetScopeLocked(scope) }
    }

    suspend fun requestRejected(request: FavoritesDurableOverflowRequest): FavoritesDurableOverflowRequest? =
        mutex.withLock {
            if (publishedRequest !== request) return@withLock null
            publishedRequest = null
            promoteIfIdleLocked()
        }

    suspend fun acknowledge(signal: InteractionReconciliationSignal): FavoritesDurableOverflowRequest? =
        mutex.withLock {
            if (signal.scope != activeScope) return@withLock null
            val currentRequest = publishedRequest
            if (
                currentRequest != null &&
                currentRequest.event.scope == signal.scope &&
                currentRequest.event.deliverySequence <= signal.deliveryWatermark
            ) {
                publishedRequest = null
            }
            latestDroppedEvent = latestDroppedEvent?.takeUnless { event ->
                event.scope == signal.scope && event.deliverySequence <= signal.deliveryWatermark
            }
            promoteIfIdleLocked()
        }

    private fun resetScopeLocked(scope: InteractionAccountScope?) {
        if (activeScope == scope) return
        activeScope = scope
        latestDroppedEvent = null
        publishedRequest = null
    }

    private fun promoteIfIdleLocked(): FavoritesDurableOverflowRequest? {
        if (queuedOrHandlingCount != 0 || publishedRequest != null) return null
        val event = latestDroppedEvent?.takeIf { current -> current.scope == activeScope } ?: return null
        latestDroppedEvent = null
        return FavoritesDurableOverflowRequest(event).also { request -> publishedRequest = request }
    }
}

internal class FavoritesDurableOverflowRequest internal constructor(
    val event: InteractionCoordinatorEvent,
)

private fun InteractionCoordinatorEvent.isNewerThan(other: InteractionCoordinatorEvent?): Boolean =
    other == null || deliverySequence > other.deliverySequence
