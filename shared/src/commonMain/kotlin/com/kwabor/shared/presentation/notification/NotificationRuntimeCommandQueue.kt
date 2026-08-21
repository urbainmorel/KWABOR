package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.notification.NotificationAccountScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

internal class NotificationRuntimeCommandQueue {
    val signal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val pending = MutableStateFlow<List<NotificationRuntimeCommand>>(emptyList())

    fun offer(command: NotificationRuntimeCommand): NotificationCommandOfferResult {
        while (true) {
            val current = pending.value
            val merged = merge(current, command)
            if (merged == null) return NotificationCommandOfferResult.Rejected(command.sourceScope)
            if (!pending.compareAndSet(current, merged)) continue
            signal.trySend(Unit)
            return NotificationCommandOfferResult.Accepted
        }
    }

    fun clear() {
        pending.value = emptyList()
    }

    fun clearAccount(accountId: String) {
        val canonicalAccountId = accountId.toCanonicalNotificationAccountId()
        while (true) {
            val current = pending.value
            val retained = current.filterNot { command -> command.sourceScope?.accountId == canonicalAccountId }
            if (pending.compareAndSet(current, retained)) return
        }
    }

    fun take(): NotificationRuntimeCommand? {
        while (true) {
            val current = pending.value
            val command = current.firstOrNull() ?: return null
            if (pending.compareAndSet(current, current.drop(1))) return command
        }
    }

    internal val pendingCount: Int
        get() = pending.value.size

    private fun merge(
        current: List<NotificationRuntimeCommand>,
        next: NotificationRuntimeCommand,
    ): List<NotificationRuntimeCommand>? {
        if (next is NotificationRuntimeCommand.ViewerChanged) {
            val queuedViewer = current.filterIsInstance<NotificationRuntimeCommand.ViewerChanged>().firstOrNull()
            if (queuedViewer != null && !next.isNewerThanOrSameViewer(queuedViewer)) return current
            val sameGeneration = current.filter { command -> command.runtimeGeneration == next.runtimeGeneration }
            return listOf(next) +
                sameGeneration
                    .filterNot { command -> command is NotificationRuntimeCommand.ViewerChanged }
                    .take(MAXIMUM_NOTIFICATION_RUNTIME_COMMANDS - 1)
        }
        val key = next.conflationKey()
        if (key != null) {
            val existingIndex = current.indexOfFirst { command -> command.conflationKey() == key }
            if (existingIndex >= 0) return current.toMutableList().also { commands -> commands[existingIndex] = next }
        }
        if (current.size >= MAXIMUM_NOTIFICATION_RUNTIME_COMMANDS) return null
        return current + next
    }
}

private fun NotificationRuntimeCommand.ViewerChanged.isNewerThanOrSameViewer(
    queued: NotificationRuntimeCommand.ViewerChanged,
): Boolean =
    when {
        scope.epoch > queued.scope.epoch -> true
        scope.epoch < queued.scope.epoch -> false
        scope != queued.scope -> false
        else -> runtimeGeneration >= queued.runtimeGeneration
    }

internal sealed interface NotificationCommandOfferResult {
    data object Accepted : NotificationCommandOfferResult

    data class Rejected(val scope: NotificationAccountScope?) : NotificationCommandOfferResult
}

private fun NotificationRuntimeCommand.conflationKey(): String? =
    when (this) {
        is NotificationRuntimeCommand.SyncChanged -> "sync:${sourceScope.accountId}:${sourceScope.epoch}"
        is NotificationRuntimeCommand.ViewerChanged -> "viewer"
        is NotificationRuntimeCommand.Intent ->
            when (val value = intent) {
                is NotificationIntent.Lifecycle -> value.lifecycleConflationKey()
                is NotificationIntent.Page -> value.pageConflationKey(sourceScope)
                is NotificationIntent.PreferenceAction -> value.preferenceConflationKey(sourceScope)
                is NotificationIntent.DetailPresentation,
                is NotificationIntent.ItemAction,
                -> null
            }
    }

private fun NotificationIntent.Lifecycle.lifecycleConflationKey(): String =
    when (this) {
        NotificationIntent.ScreenAppeared,
        NotificationIntent.ScreenDisappeared,
        -> "screen"
        NotificationIntent.Foregrounded -> "foreground"
        is NotificationIntent.SnapshotPresented -> "snapshot:${scope.accountId}:${scope.epoch}"
        is NotificationIntent.ViewerContextChanged -> "viewer-intent"
    }

private fun NotificationIntent.Page.pageConflationKey(scope: NotificationAccountScope?): String =
    when (this) {
        NotificationIntent.Refresh -> "page-refresh:${scope.key()}"
        NotificationIntent.Retry -> "page-retry:${scope.key()}"
        NotificationIntent.LoadNext -> "page-append:${scope.key()}"
    }

private fun NotificationIntent.PreferenceAction.preferenceConflationKey(scope: NotificationAccountScope?): String? =
    when (this) {
        NotificationIntent.OpenPreferences -> null
        NotificationIntent.PreferencesScreenAppeared,
        NotificationIntent.PreferencesScreenDisappeared,
        -> "preferences-screen:${scope.key()}"
        NotificationIntent.RetryPreferences -> "preferences-retry:${scope.key()}"
        is NotificationIntent.SetPreference -> "preference:$family:${scope.key()}"
    }

private fun NotificationAccountScope?.key(): String = "${this?.accountId}:${this?.epoch}"

internal fun NotificationCommandOfferResult.rejectionError(): DomainError? =
    (this as? NotificationCommandOfferResult.Rejected)?.let { DomainError.Unexpected() }

internal const val MAXIMUM_NOTIFICATION_RUNTIME_COMMANDS = 64
