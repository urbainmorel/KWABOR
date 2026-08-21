package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationCachedInbox
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationPendingSyncStatus
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.PendingNotificationSync
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class NotificationStateStore {
    private val mutableState = MutableStateFlow(NotificationUiState())
    val state: StateFlow<NotificationUiState> = mutableState.asStateFlow()
    val value: NotificationUiState
        get() = mutableState.value

    fun publishViewerScope(scope: ViewerSessionScope): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.viewerScope == scope) return false
            if (scope.epoch < current.viewerScope.epoch) return false
            if (scope.epoch == current.viewerScope.epoch) return false
            val reset =
                NotificationUiState(
                    viewerScope = scope,
                    presentationGeneration = current.presentationGeneration.nextNotificationGeneration(),
                )
            if (mutableState.compareAndSet(current, reset)) return true
        }
    }

    fun update(
        expectedScope: NotificationAccountScope,
        transform: (NotificationUiState) -> NotificationUiState,
    ): NotificationUiState? {
        while (true) {
            val current = mutableState.value
            if (current.accountScope != expectedScope) return null
            val updated = transform(current)
            if (mutableState.compareAndSet(current, updated)) return updated
        }
    }

    fun invalidateAccount(accountId: String): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.viewerScope.toNotificationAccountScopeOrNull()?.accountId != accountId) return false
            val reset =
                NotificationUiState(
                    viewerScope = current.viewerScope,
                    presentationGeneration = current.presentationGeneration.nextNotificationGeneration(),
                )
            if (mutableState.compareAndSet(current, reset)) return true
        }
    }
}

internal data class NotificationRuntimeInbox(
    val accountId: String,
    val snapshotSequence: Long,
    val nextCursor: String?,
    val status: NotificationInboxStatus,
    val items: List<NotificationInboxItem>,
) {
    init {
        require(snapshotSequence in 0L..status.latestSequence) {
            "Notification runtime snapshot must belong to the current history."
        }
        require(items.size <= NotificationCachedInbox.MAX_ITEMS) {
            "Notification runtime inbox exceeds its cache-sized window."
        }
        require(items.all { item -> item.sequence <= snapshotSequence }) {
            "Notification runtime item exceeds its snapshot."
        }
        require(items.distinctBy(NotificationInboxItem::id).size == items.size) {
            "Notification runtime item ids must be unique."
        }
        require(items.distinctBy(NotificationInboxItem::sequence).size == items.size) {
            "Notification runtime item sequences must be unique."
        }
        require(items.zipWithNext().all { (newer, older) -> newer.sequence > older.sequence }) {
            "Notification runtime items must remain newest-first."
        }
        require(nextCursor == null || items.isNotEmpty()) {
            "An empty notification runtime window cannot expose a cursor."
        }
        require(items.size < NotificationCachedInbox.MAX_ITEMS || nextCursor == null) {
            "A full notification runtime window cannot expose a cursor."
        }
    }

    fun overlayPending(
        expectedScope: NotificationAccountScope,
        pending: List<PendingNotificationSync>,
        nowEpochMilliseconds: Long,
    ): NotificationRuntimeInbox {
        require(expectedScope.accountId == accountId) {
            "Notification pending projection must target its runtime inbox account."
        }
        return pending
            .filter { operation -> operation.command.scope == expectedScope }
            .fold(this) { current, operation -> current.overlay(operation.command, nowEpochMilliseconds) }
    }

    private fun overlay(
        command: NotificationSyncCommand,
        nowEpochMilliseconds: Long,
    ): NotificationRuntimeInbox =
        when (command) {
            is NotificationSyncCommand.AdvanceSeenThrough ->
                copy(
                    status = status.advanceSeenThrough(command.throughSequence),
                )
            is NotificationSyncCommand.MarkRead ->
                overlayItem(
                    command.notificationId,
                    nowEpochMilliseconds,
                ) { item, at ->
                    item.copy(
                        seenAtEpochMilliseconds = item.seenAtEpochMilliseconds ?: at,
                        readAtEpochMilliseconds = item.readAtEpochMilliseconds ?: at,
                    )
                }
            is NotificationSyncCommand.MarkAllReadThrough ->
                overlayMarkAllRead(
                    command.throughSequence,
                    nowEpochMilliseconds,
                )
            is NotificationSyncCommand.Hide ->
                overlayItem(command.notificationId, nowEpochMilliseconds) { item, at ->
                    item.copy(
                        seenAtEpochMilliseconds = item.seenAtEpochMilliseconds ?: at,
                        hiddenAtEpochMilliseconds = item.hiddenAtEpochMilliseconds ?: at,
                    )
                }
            is NotificationSyncCommand.SetFamilyEnabled -> this
        }

    private fun overlayItem(
        notificationId: String,
        nowEpochMilliseconds: Long,
        transform: (NotificationInboxItem, Long) -> NotificationInboxItem,
    ): NotificationRuntimeInbox {
        val index = items.indexOfFirst { item -> item.id == notificationId }
        if (index < 0) return this
        val current = items[index]
        val mutationAt = maxOf(nowEpochMilliseconds, current.createdAtEpochMilliseconds)
        val updated = transform(current, mutationAt)
        val updatedItems = items.toMutableList().also { mutable -> mutable[index] = updated }.toList()
        return copy(
            items = updatedItems,
            status = status.projectItemStateChange(current, updated),
        )
    }

    private fun overlayMarkAllRead(
        throughSequence: Long,
        nowEpochMilliseconds: Long,
    ): NotificationRuntimeInbox {
        var updatedStatus = status.advanceSeenThrough(throughSequence)
        val updatedItems =
            items.map { item ->
                if (
                    item.sequence > throughSequence ||
                    item.hiddenAtEpochMilliseconds != null ||
                    item.readAtEpochMilliseconds != null
                ) {
                    item
                } else {
                    val mutationAt = maxOf(nowEpochMilliseconds, item.createdAtEpochMilliseconds)
                    item.copy(
                        seenAtEpochMilliseconds = item.seenAtEpochMilliseconds ?: mutationAt,
                        readAtEpochMilliseconds = mutationAt,
                    ).also { updated -> updatedStatus = updatedStatus.projectItemStateChange(item, updated) }
                }
            }
        return copy(items = updatedItems, status = updatedStatus)
    }
}

internal data class NotificationPendingProjection(
    val operations: List<PendingNotificationSync>,
) {
    val savingFamilies: Set<NotificationPreferenceFamily> =
        operations.mapNotNullTo(linkedSetOf()) { pending ->
            (pending.command as? NotificationSyncCommand.SetFamilyEnabled)?.family
        }

    val hasScheduledRetry: Boolean =
        operations.any { pending ->
            pending.status is NotificationPendingSyncStatus.Scheduled && pending.attemptCount > 0
        }

    fun overlayPreferences(preferences: NotificationPreferences): NotificationPreferences =
        operations.fold(preferences) { current, pending ->
            val command = pending.command as? NotificationSyncCommand.SetFamilyEnabled ?: return@fold current
            current.copy(
                entries =
                    current.entries.map { preference ->
                        if (preference.family == command.family) {
                            preference.copy(enabled = command.enabled)
                        } else {
                            preference
                        }
                    },
            )
        }
}

internal fun NotificationCachedInbox.toRuntimeInbox(): NotificationRuntimeInbox =
    NotificationRuntimeInbox(
        accountId = accountId,
        snapshotSequence = snapshotSequence,
        nextCursor = nextCursor,
        status = status,
        items = items,
    )

internal fun NotificationInboxPage.toRuntimeInbox(
    scope: NotificationAccountScope,
    status: NotificationInboxStatus,
): NotificationRuntimeInbox {
    val snapshot = snapshotSequence ?: status.latestSequence
    val normalizedStatus =
        if (status.latestSequence < snapshot) {
            status.copy(latestSequence = snapshot)
        } else {
            status
        }
    return NotificationRuntimeInbox(
        accountId = scope.accountId,
        snapshotSequence = snapshot,
        nextCursor = nextCursor.takeUnless { items.size == NotificationCachedInbox.MAX_ITEMS },
        status = normalizedStatus,
        items = items,
    )
}

internal fun NotificationRuntimeInbox.appendPage(page: NotificationInboxPage): NotificationRuntimeInbox {
    require(page.snapshotSequence == null || page.snapshotSequence == snapshotSequence) {
        "Notification append snapshot changed."
    }
    require(items.none { cached -> page.items.any { incoming -> incoming.id == cached.id } }) {
        "Notification append repeated an existing item id."
    }
    require(items.none { cached -> page.items.any { incoming -> incoming.sequence == cached.sequence } }) {
        "Notification append repeated an existing sequence."
    }
    val appended = items + page.items
    require(appended.size <= NotificationCachedInbox.MAX_ITEMS) {
        "Notification append exceeds the runtime cap."
    }
    return copy(
        items = appended,
        nextCursor = page.nextCursor.takeUnless { appended.size == NotificationCachedInbox.MAX_ITEMS },
    )
}

internal fun NotificationInboxStatus.toBadgeUiState(): NotificationBadgeUiState =
    NotificationBadgeUiState(
        unseenCount = unseenCount,
        unreadCount = unreadCount,
        seenThroughSequence = seenThroughSequence,
    )

internal fun NotificationInboxStatus.mergeMonotone(incoming: NotificationInboxStatus): NotificationInboxStatus =
    when {
        incoming.latestSequence < latestSequence -> this
        incoming.latestSequence == latestSequence ->
            NotificationInboxStatus(
                latestSequence = latestSequence,
                seenThroughSequence = maxOf(seenThroughSequence, incoming.seenThroughSequence),
                unseenCount = minOf(unseenCount, incoming.unseenCount),
                unreadCount = minOf(unreadCount, incoming.unreadCount),
            )
        else -> incoming.copy(seenThroughSequence = maxOf(seenThroughSequence, incoming.seenThroughSequence))
    }

private fun NotificationInboxStatus.advanceSeenThrough(boundary: Long): NotificationInboxStatus {
    val through = boundary.coerceIn(seenThroughSequence, latestSequence)
    val maximumRemainingUnseen = (latestSequence - through).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return copy(
        seenThroughSequence = through,
        unseenCount = minOf(unseenCount, maximumRemainingUnseen),
    )
}

private fun NotificationInboxStatus.projectItemStateChange(
    before: NotificationInboxItem,
    after: NotificationInboxItem,
): NotificationInboxStatus {
    val unseenDelta =
        after.isRuntimeUnseen(seenThroughSequence).toIntCount() -
            before.isRuntimeUnseen(seenThroughSequence).toIntCount()
    val unreadDelta = after.isRuntimeUnread().toIntCount() - before.isRuntimeUnread().toIntCount()
    return copy(
        unseenCount = (unseenCount + unseenDelta).coerceAtLeast(0),
        unreadCount = (unreadCount + unreadDelta).coerceAtLeast(0),
    )
}

private fun NotificationInboxItem.isRuntimeUnseen(seenThroughSequence: Long): Boolean =
    hiddenAtEpochMilliseconds == null && sequence > seenThroughSequence && seenAtEpochMilliseconds == null

private fun NotificationInboxItem.isRuntimeUnread(): Boolean =
    hiddenAtEpochMilliseconds == null && readAtEpochMilliseconds == null

private fun Boolean.toIntCount(): Int = if (this) 1 else 0

private fun Long.nextNotificationGeneration(): Long = if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L
