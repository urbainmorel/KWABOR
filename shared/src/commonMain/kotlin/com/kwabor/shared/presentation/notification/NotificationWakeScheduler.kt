package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.notification.NotificationAccountScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

internal class NotificationWakeScheduler(
    private val coordinatorScope: CoroutineScope,
    private val clockProvider: ClockProvider,
    private val currentScope: () -> NotificationAccountScope?,
    private val wakeAccumulator: NotificationWakeAccumulator,
) {
    private val scheduledWake = MutableStateFlow<NotificationScheduledWake?>(null)

    fun install(
        scope: NotificationAccountScope,
        nextAttemptAt: Long,
    ) {
        val now = clockProvider.nowEpochMilliseconds().coerceAtLeast(0L)
        val delayMilliseconds =
            min(
                (nextAttemptAt - now).coerceAtLeast(MINIMUM_NOTIFICATION_SCHEDULE_DELAY_MILLISECONDS),
                MAXIMUM_NOTIFICATION_SCHEDULE_DELAY_MILLISECONDS,
            )
        val job =
            coordinatorScope.launch(start = CoroutineStart.LAZY) {
                delay(delayMilliseconds)
                wakeAccumulator.offer(NotificationWakeRequest.immediate(scope), currentScope())
            }
        replace(NotificationScheduledWake(scope, job))
    }

    fun cancel(predicate: (NotificationScheduledWake) -> Boolean = { true }) {
        while (true) {
            val current = scheduledWake.value ?: return
            if (!predicate(current)) return
            if (!scheduledWake.compareAndSet(current, null)) continue
            current.job.cancel()
            return
        }
    }

    private fun replace(next: NotificationScheduledWake) {
        while (true) {
            val current = scheduledWake.value
            if (!scheduledWake.compareAndSet(current, next)) continue
            current?.job?.cancel()
            next.job.start()
            return
        }
    }
}

internal class NotificationWakeAccumulator {
    val signal =
        kotlinx.coroutines.channels.Channel<Unit>(
            capacity = kotlinx.coroutines.channels.Channel.CONFLATED,
        )
    private val pending = MutableStateFlow<NotificationWakeRequest?>(null)

    fun clear() {
        pending.value = null
    }

    fun clearAccount(accountId: String) {
        while (true) {
            val current = pending.value ?: return
            if (current.scope.accountId != accountId) return
            if (pending.compareAndSet(current, null)) return
        }
    }

    fun offer(
        request: NotificationWakeRequest,
        currentScope: NotificationAccountScope?,
    ) {
        if (request.scope != currentScope) return
        while (true) {
            val current = pending.value
            val merged = current?.merge(request) ?: request
            if (pending.compareAndSet(current, merged)) break
        }
        signal.trySend(Unit)
    }

    fun take(): NotificationWakeRequest? {
        while (true) {
            val current = pending.value ?: return null
            if (pending.compareAndSet(current, null)) return current
        }
    }
}

internal data class NotificationWakeRequest(
    val scope: NotificationAccountScope,
    val retryMode: NotificationWakeRetryMode,
) {
    fun merge(next: NotificationWakeRequest): NotificationWakeRequest {
        if (scope != next.scope) return if (next.scope.epoch >= scope.epoch) next else this
        return copy(retryMode = maxOf(retryMode, next.retryMode))
    }

    companion object {
        fun immediate(scope: NotificationAccountScope): NotificationWakeRequest =
            NotificationWakeRequest(scope, NotificationWakeRetryMode.None)

        fun automatic(scope: NotificationAccountScope): NotificationWakeRequest =
            NotificationWakeRequest(scope, NotificationWakeRetryMode.Automatic)

        fun manual(scope: NotificationAccountScope): NotificationWakeRequest =
            NotificationWakeRequest(scope, NotificationWakeRetryMode.Manual)
    }
}

internal enum class NotificationWakeRetryMode {
    None,
    Automatic,
    Manual,
}

internal data class NotificationScheduledWake(
    val scope: NotificationAccountScope,
    val job: Job,
)

private const val MAXIMUM_NOTIFICATION_SCHEDULE_DELAY_MILLISECONDS = 5L * 60L * 1_000L
private const val MINIMUM_NOTIFICATION_SCHEDULE_DELAY_MILLISECONDS = 1L
