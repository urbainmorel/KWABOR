package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

internal class InteractionWakeAccumulator {
    val signal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val pending = MutableStateFlow<InteractionWakeRequest?>(null)

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

    fun offer(request: InteractionWakeRequest, currentScope: InteractionAccountScope?) {
        if (request.scope != currentScope) return
        while (true) {
            val current = pending.value
            val merged = current?.merge(request) ?: request
            if (pending.compareAndSet(current, merged)) break
        }
        signal.trySend(Unit)
    }

    fun take(): InteractionWakeRequest? {
        while (true) {
            val current = pending.value ?: return null
            if (pending.compareAndSet(current, null)) return current
        }
    }
}

internal data class InteractionWakeRequest(
    val scope: InteractionAccountScope,
    val retryMode: InteractionWakeRetryMode,
    val retriesReconciliation: Boolean,
) {
    fun merge(next: InteractionWakeRequest): InteractionWakeRequest {
        if (scope != next.scope) return if (next.scope.epoch >= scope.epoch) next else this
        return copy(
            retryMode = maxOf(retryMode, next.retryMode),
            retriesReconciliation = retriesReconciliation || next.retriesReconciliation,
        )
    }

    companion object {
        fun automatic(scope: InteractionAccountScope): InteractionWakeRequest =
            InteractionWakeRequest(scope, InteractionWakeRetryMode.Automatic, retriesReconciliation = false)

        fun manual(scope: InteractionAccountScope): InteractionWakeRequest =
            InteractionWakeRequest(scope, InteractionWakeRetryMode.Manual, retriesReconciliation = false)

        fun reconciling(scope: InteractionAccountScope): InteractionWakeRequest =
            InteractionWakeRequest(scope, InteractionWakeRetryMode.None, retriesReconciliation = true)

        fun immediate(scope: InteractionAccountScope): InteractionWakeRequest =
            InteractionWakeRequest(scope, InteractionWakeRetryMode.None, retriesReconciliation = false)
    }
}

internal enum class InteractionWakeRetryMode {
    None,
    Automatic,
    Manual,
}
