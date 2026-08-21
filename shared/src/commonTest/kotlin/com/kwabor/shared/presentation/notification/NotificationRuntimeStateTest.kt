package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationContent
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationKind
import com.kwabor.shared.domain.notification.NotificationPendingSyncStatus
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.PendingNotificationSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NotificationRuntimeStateTest {
    private val scope = NotificationAccountScope(accountId = "account-a", epoch = 4L)

    @Test
    fun renderedSeenBoundaryClearsUnseenWithoutMarkingItemsRead() {
        val item = item(sequence = 2L)
        val inbox =
            NotificationRuntimeInbox(
                accountId = scope.accountId,
                snapshotSequence = 2L,
                nextCursor = null,
                status = status(latest = 2L, unseen = 1, unread = 1),
                items = listOf(item),
            )
        val pending = pending(NotificationSyncCommand.AdvanceSeenThrough(scope, throughSequence = 2L))

        val projected = inbox.overlayPending(scope, listOf(pending), nowEpochMilliseconds = 2_000L)

        assertEquals(0, projected.status.unseenCount)
        assertEquals(1, projected.status.unreadCount)
        assertEquals(2L, projected.status.seenThroughSequence)
        assertEquals(null, projected.items.single().readAtEpochMilliseconds)
        assertFalse(projected.status.toBadgeUiState().isVisible)
    }

    @Test
    fun markReadChangesUnreadIndependentlyFromSeenBoundary() {
        val inbox =
            NotificationRuntimeInbox(
                accountId = scope.accountId,
                snapshotSequence = 2L,
                nextCursor = null,
                status = status(latest = 2L, unseen = 1, unread = 1),
                items = listOf(item(sequence = 2L)),
            )

        val projected =
            inbox.overlayPending(
                expectedScope = scope,
                pending = listOf(pending(NotificationSyncCommand.MarkRead(scope, "notification-2"))),
                nowEpochMilliseconds = 2_000L,
            )

        assertEquals(0, projected.status.unseenCount)
        assertEquals(0, projected.status.unreadCount)
        assertEquals(0L, projected.status.seenThroughSequence)
        assertEquals(2_000L, projected.items.single().readAtEpochMilliseconds)
    }

    @Test
    fun pendingProjectionRejectsAnotherEpochOfTheSameAccount() {
        val staleScope = scope.copy(epoch = scope.epoch - 1L)
        val inbox =
            NotificationRuntimeInbox(
                accountId = scope.accountId,
                snapshotSequence = 1L,
                nextCursor = null,
                status = status(latest = 1L, unseen = 1, unread = 1),
                items = listOf(item(sequence = 1L)),
            )

        val projected =
            inbox.overlayPending(
                expectedScope = scope,
                pending = listOf(pending(NotificationSyncCommand.MarkRead(staleScope, "notification-1"))),
                nowEpochMilliseconds = 2_000L,
            )

        assertEquals(1, projected.status.unreadCount)
        assertEquals(null, projected.items.single().readAtEpochMilliseconds)
    }

    @Test
    fun appendHonoursSnapshotAndHardCacheCap() {
        val firstItems = (151L..200L).reversed().map(::item)
        val inbox =
            NotificationRuntimeInbox(
                accountId = scope.accountId,
                snapshotSequence = 200L,
                nextCursor = "cursor-150",
                status = status(latest = 200L, unseen = 200, unread = 200),
                items = firstItems,
            )
        val lastPage =
            NotificationInboxPage(
                items = (1L..150L).reversed().map(::item),
                snapshotSequence = 200L,
                nextCursor = "must-be-cleared",
            )

        val appended = inbox.appendPage(lastPage)

        assertEquals(200, appended.items.size)
        assertEquals(null, appended.nextCursor)
        assertFailsWith<IllegalArgumentException> {
            inbox.appendPage(
                NotificationInboxPage(
                    items = (1L..151L).reversed().map(::item),
                    snapshotSequence = 200L,
                    nextCursor = null,
                ),
            )
        }
    }

    private fun pending(command: NotificationSyncCommand): PendingNotificationSync =
        PendingNotificationSync(
            operationId = 1L,
            command = command,
            enqueuedAtEpochMilliseconds = 1_500L,
            attemptCount = 0,
            status = NotificationPendingSyncStatus.Scheduled(1_500L),
        )

    private fun item(sequence: Long): NotificationInboxItem =
        NotificationInboxItem(
            id = "notification-$sequence",
            sequence = sequence,
            kind = NotificationKind.Suggestion,
            content =
                NotificationContent.Suggestion(
                    titleKey = "notification.suggestion.title",
                    bodyKey = "notification.suggestion.body",
                    listingName = "Lieu $sequence",
                ),
            target = null,
            seenAtEpochMilliseconds = null,
            readAtEpochMilliseconds = null,
            hiddenAtEpochMilliseconds = null,
            createdAtEpochMilliseconds = 1_000L,
        )

    private fun status(
        latest: Long,
        unseen: Int,
        unread: Int,
    ): NotificationInboxStatus =
        NotificationInboxStatus(
            latestSequence = latest,
            seenThroughSequence = 0L,
            unseenCount = unseen,
            unreadCount = unread,
        )
}
