package com.kwabor.shared.presentation.notification

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

internal class NotificationRuntimeEffectQueue(
    private val capacity: Int = NOTIFICATION_EFFECT_CAPACITY,
) {
    private val signal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val pending = MutableStateFlow<List<NotificationEffect>>(emptyList())

    init {
        require(capacity > 0) { "Notification effect capacity must be positive." }
    }

    fun offer(effect: NotificationEffect): Boolean {
        while (true) {
            val current = pending.value
            if (current.size >= capacity) return false
            if (!pending.compareAndSet(current, current + effect)) continue
            signal.trySend(Unit)
            return true
        }
    }

    fun clearAccount(accountId: String) {
        val canonicalAccountId = accountId.toCanonicalNotificationAccountId()
        while (true) {
            val current = pending.value
            val retained = current.filterNot { effect -> effect.scope.accountId == canonicalAccountId }
            if (pending.compareAndSet(current, retained)) return
        }
    }

    fun asFlow(): Flow<NotificationEffect> =
        flow {
            for (ignored in signal) {
                var effect = take()
                while (effect != null) {
                    emit(effect)
                    effect = take()
                }
            }
        }

    fun close() {
        signal.close()
    }

    internal val pendingCount: Int
        get() = pending.value.size

    private fun take(): NotificationEffect? {
        while (true) {
            val current = pending.value
            val effect = current.firstOrNull() ?: return null
            if (pending.compareAndSet(current, current.drop(1))) return effect
        }
    }
}

internal const val NOTIFICATION_EFFECT_CAPACITY = 64
