package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationDrainOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class NotificationDrainSingleFlight(
    private val workerScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val requests = mutableMapOf<String, ActiveNotificationDrain>()

    suspend fun execute(
        scope: NotificationAccountScope,
        block: suspend () -> NotificationDrainOutcome,
    ): NotificationDrainOutcome {
        val request = mutex.withLock {
            val current = requests[scope.accountId]
            if (current?.scope == scope) {
                current.request
            } else {
                current?.request?.cancel(NotificationDrainScopeChangedCancellation())
                createRequest(scope, current?.request, block).also { created ->
                    requests[scope.accountId] = created
                    created.request.start()
                }.request
            }
        }
        return try {
            request.await().getOrThrow()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                request.join()
            }
            throw cancellation
        }
    }

    internal suspend fun activeRequestCount(): Int = mutex.withLock { requests.size }

    private fun createRequest(
        scope: NotificationAccountScope,
        predecessor: Deferred<Result<NotificationDrainOutcome>>?,
        block: suspend () -> NotificationDrainOutcome,
    ): ActiveNotificationDrain {
        val token = Any()
        val request = workerScope.async(start = CoroutineStart.LAZY) {
            try {
                predecessor?.join()
                captureDrainResult(block)
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (requests[scope.accountId]?.token === token) requests.remove(scope.accountId)
                    }
                }
            }
        }
        return ActiveNotificationDrain(scope, token, request)
    }
}

private data class ActiveNotificationDrain(
    val scope: NotificationAccountScope,
    val token: Any,
    val request: Deferred<Result<NotificationDrainOutcome>>,
)

private suspend fun captureDrainResult(
    block: suspend () -> NotificationDrainOutcome,
): Result<NotificationDrainOutcome> = runCatching { block() }.also { result ->
    val failure = result.exceptionOrNull()
    if (failure is CancellationException) throw failure
}

private class NotificationDrainScopeChangedCancellation :
    CancellationException("Notification drain scope changed.")
