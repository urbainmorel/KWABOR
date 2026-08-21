package com.kwabor.shared.domain.notification

data class NotificationCachedInbox(
    val accountId: String,
    val snapshotSequence: Long,
    val nextCursor: String?,
    val status: NotificationInboxStatus,
    val cachedAtEpochMilliseconds: Long,
    val items: List<NotificationInboxItem>,
) {
    init {
        require(accountId.isNotEmpty() && accountId == accountId.trim()) {
            "Notification cache account id must be normalized."
        }
        require(snapshotSequence in 0L..status.latestSequence) {
            "Notification cache snapshot must belong to the current history."
        }
        require(cachedAtEpochMilliseconds >= 0L) {
            "Notification cache timestamp must not be negative."
        }
        require(items.all { item -> item.sequence <= snapshotSequence }) {
            "Notification cache item cannot exceed its snapshot."
        }
        require(items.size <= MAX_ITEMS) { "Notification cache cannot exceed $MAX_ITEMS items." }
        require(items.distinctBy(NotificationInboxItem::id).size == items.size) {
            "Notification cache item ids must be unique."
        }
        require(items.distinctBy(NotificationInboxItem::sequence).size == items.size) {
            "Notification cache item sequences must be unique."
        }
        require(items.zipWithNext().all { (newer, older) -> newer.sequence > older.sequence }) {
            "Notification cache items must be in strict newest-first order."
        }
        require(nextCursor == null || items.isNotEmpty()) {
            "An empty notification cache cannot expose a cursor."
        }
        require(items.size < MAX_ITEMS || nextCursor == null) {
            "A full notification cache cannot expose a continuation cursor."
        }
    }

    companion object {
        const val MAX_ITEMS = 200
    }
}

data class NotificationCachedPreferences(
    val accountId: String,
    val preferences: NotificationPreferences,
    val cachedAtEpochMilliseconds: Long?,
) {
    init {
        require(accountId.isNotEmpty() && accountId == accountId.trim()) {
            "Notification preferences account id must be normalized."
        }
        require(cachedAtEpochMilliseconds == null || cachedAtEpochMilliseconds >= 0L) {
            "Notification preferences cache timestamp must not be negative."
        }
    }
}

data class NotificationAccountScope(
    val accountId: String,
    val epoch: Long,
) {
    init {
        require(accountId.isNotEmpty() && accountId == accountId.trim()) {
            "Notification account id must be normalized."
        }
        require(epoch >= 0L) { "Notification account epoch must not be negative." }
    }
}

fun interface ActiveNotificationAccountProvider {
    fun currentScope(): NotificationAccountScope?
}

sealed interface NotificationSyncCommand {
    val scope: NotificationAccountScope

    data class AdvanceSeenThrough(
        override val scope: NotificationAccountScope,
        val throughSequence: Long,
    ) : NotificationSyncCommand {
        init {
            require(throughSequence > 0L) { "Notification seen boundary must be positive." }
        }
    }

    data class MarkRead(
        override val scope: NotificationAccountScope,
        val notificationId: String,
    ) : NotificationSyncCommand {
        init {
            requireNormalizedNotificationId(notificationId)
        }
    }

    data class MarkAllReadThrough(
        override val scope: NotificationAccountScope,
        val throughSequence: Long,
    ) : NotificationSyncCommand {
        init {
            require(throughSequence > 0L) { "Notification read boundary must be positive." }
        }
    }

    data class Hide(
        override val scope: NotificationAccountScope,
        val notificationId: String,
    ) : NotificationSyncCommand {
        init {
            requireNormalizedNotificationId(notificationId)
        }
    }

    data class SetFamilyEnabled(
        override val scope: NotificationAccountScope,
        val family: NotificationPreferenceFamily,
        val enabled: Boolean,
    ) : NotificationSyncCommand
}

sealed interface NotificationPendingSyncStatus {
    data class Scheduled(
        val nextAttemptAtEpochMilliseconds: Long,
    ) : NotificationPendingSyncStatus {
        init {
            require(nextAttemptAtEpochMilliseconds >= 0L) {
                "Notification retry timestamp must not be negative."
            }
        }
    }

    data class Paused(
        val errorCode: String,
    ) : NotificationPendingSyncStatus {
        init {
            require(errorCode.isNotBlank() && errorCode == errorCode.trim()) {
                "Notification terminal error code must be normalized."
            }
        }
    }
}

data class PendingNotificationSync(
    val operationId: Long,
    val command: NotificationSyncCommand,
    val enqueuedAtEpochMilliseconds: Long,
    val attemptCount: Int,
    val status: NotificationPendingSyncStatus,
) {
    init {
        require(operationId > 0L) { "Notification operation id must be positive." }
        require(enqueuedAtEpochMilliseconds >= 0L) {
            "Notification enqueue timestamp must not be negative."
        }
        require(attemptCount >= 0) { "Notification attempt count must not be negative." }
    }
}

sealed interface NotificationSyncConfirmation {
    data class Status(val status: NotificationInboxStatus) : NotificationSyncConfirmation

    data class Item(
        val mutation: NotificationItemMutation,
        val status: NotificationInboxStatus,
    ) : NotificationSyncConfirmation

    data class MarkAllRead(
        val confirmation: NotificationMarkAllReadConfirmation,
    ) : NotificationSyncConfirmation

    data class Preference(val preference: NotificationFamilyPreference) : NotificationSyncConfirmation
}

sealed interface NotificationSubmitOutcome {
    val command: NotificationSyncCommand

    data class Queued(
        override val command: NotificationSyncCommand,
        val pending: PendingNotificationSync,
    ) : NotificationSubmitOutcome

    data class Superseded(
        override val command: NotificationSyncCommand,
        val operationId: Long,
    ) : NotificationSubmitOutcome
}

sealed interface NotificationSyncOperationOutcome {
    val command: NotificationSyncCommand

    data class Confirmed(
        override val command: NotificationSyncCommand,
        val operationId: Long,
        val confirmation: NotificationSyncConfirmation,
    ) : NotificationSyncOperationOutcome

    data class Retrying(
        override val command: NotificationSyncCommand,
        val pending: PendingNotificationSync,
    ) : NotificationSyncOperationOutcome

    data class Paused(
        override val command: NotificationSyncCommand,
        val pending: PendingNotificationSync,
    ) : NotificationSyncOperationOutcome

    data class Superseded(
        override val command: NotificationSyncCommand,
        val operationId: Long,
    ) : NotificationSyncOperationOutcome
}

data class NotificationDrainOutcome(
    val scope: NotificationAccountScope,
    val operations: List<NotificationSyncOperationOutcome>,
)

private fun requireNormalizedNotificationId(notificationId: String) {
    require(notificationId.isNotEmpty() && notificationId == notificationId.trim()) {
        "Notification id must be normalized."
    }
}
