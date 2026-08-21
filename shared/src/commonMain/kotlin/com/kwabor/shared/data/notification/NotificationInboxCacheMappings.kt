package com.kwabor.shared.data.notification

import com.kwabor.shared.data.local.NotificationInboxDao
import com.kwabor.shared.data.local.NotificationInboxStatusUpdate
import com.kwabor.shared.data.local.NotificationInboxSnapshotEntity
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxStatus

internal fun NotificationInboxPage.projectStatus(
    status: NotificationInboxStatus,
    snapshotSequence: Long,
    cachedItems: List<NotificationInboxItem>,
): NotificationInboxStatus = NotificationInboxStatus(
    latestSequence = maxOf(status.latestSequence, snapshotSequence),
    seenThroughSequence = status.seenThroughSequence,
    unseenCount = projectNotificationCount(status.unseenCount, items, cachedItems) { item ->
        item.isUnseen(status.seenThroughSequence)
    },
    unreadCount = projectNotificationCount(status.unreadCount, items, cachedItems, NotificationInboxItem::isUnread),
)

internal fun CachedNotificationInbox.requireMatchingAppend(
    expectedSnapshotSequence: Long,
    expectedNextCursor: String,
    page: NotificationInboxPage,
) {
    val hasMismatch = listOf(
        snapshotSequence != expectedSnapshotSequence,
        nextCursor != expectedNextCursor,
        page.snapshotSequence != null && page.snapshotSequence != expectedSnapshotSequence,
        page.items.any { incoming -> items.any { cached -> cached.id == incoming.id } },
    ).any { condition -> condition }
    if (hasMismatch) {
        throw NotificationCacheSnapshotMismatchException()
    }
}

internal fun CachedNotificationInbox.toAppendedSnapshot(
    accountId: String,
    page: NotificationInboxPage,
    itemCount: Int,
    maximumItemCount: Int,
    cachedAtEpochMilliseconds: Long,
): NotificationInboxSnapshotEntity = NotificationInboxSnapshotEntity(
    accountId = accountId,
    snapshotSequence = snapshotSequence,
    nextCursor = page.nextCursor.takeUnless { itemCount == maximumItemCount },
    latestSequence = status.latestSequence,
    confirmedSeenThroughSequence = status.seenThroughSequence,
    unseenCount = status.unseenCount.toLong(),
    unreadCount = maxOf(status.unreadCount, (items + page.items).count(NotificationInboxItem::isUnread)).toLong(),
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    itemCount = itemCount.toLong(),
)

internal fun NotificationInboxSnapshotEntity.isValid(accountId: String, maximumItemCount: Int): Boolean {
    val cursorIsValid = nextCursor?.let { cursor ->
        runCatching { cursor.requireNotificationCursor("next_cursor") }.isSuccess
    } ?: true
    return listOf(
        this.accountId == accountId,
        accountId.isCanonicalNotificationUuid(),
        snapshotSequence >= 0L,
        latestSequence >= snapshotSequence,
        confirmedSeenThroughSequence in 0L..latestSequence,
        unseenCount in 0L..Int.MAX_VALUE.toLong(),
        unreadCount in 0L..Int.MAX_VALUE.toLong(),
        unseenCount <= unreadCount,
        unseenCount <= latestSequence,
        unreadCount <= latestSequence,
        cachedAtEpochMilliseconds >= 0L,
        itemCount in 0L..maximumItemCount.toLong(),
        itemCount > 0L || nextCursor == null,
        itemCount < maximumItemCount.toLong() || nextCursor == null,
        cursorIsValid,
    ).all { condition -> condition }
}

internal fun NotificationInboxSnapshotEntity.toCachedInbox(
    items: List<NotificationInboxItem>,
): CachedNotificationInbox = CachedNotificationInbox(
    accountId = accountId,
    snapshotSequence = snapshotSequence,
    nextCursor = nextCursor,
    status = NotificationInboxStatus(
        latestSequence = latestSequence,
        seenThroughSequence = confirmedSeenThroughSequence,
        unseenCount = unseenCount.toInt(),
        unreadCount = unreadCount.toInt(),
    ),
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    items = items,
)

internal fun NotificationInboxStatus.toSnapshotEntity(
    accountId: String,
    snapshotSequence: Long,
    nextCursor: String?,
    cachedAtEpochMilliseconds: Long,
    itemCount: Int,
): NotificationInboxSnapshotEntity = NotificationInboxSnapshotEntity(
    accountId = accountId,
    snapshotSequence = snapshotSequence,
    nextCursor = nextCursor,
    latestSequence = maxOf(latestSequence, snapshotSequence),
    confirmedSeenThroughSequence = seenThroughSequence,
    unseenCount = unseenCount.toLong(),
    unreadCount = unreadCount.toLong(),
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    itemCount = itemCount.toLong(),
)

internal fun NotificationInboxStatus.toStatusUpdate(
    accountId: String,
    cachedAtEpochMilliseconds: Long,
): NotificationInboxStatusUpdate = NotificationInboxStatusUpdate(
    accountId = accountId,
    latestSequence = latestSequence,
    confirmedSeenThroughSequence = seenThroughSequence,
    unseenCount = unseenCount.toLong(),
    unreadCount = unreadCount.toLong(),
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
)

internal fun NotificationInboxStatus.isOlderThan(other: NotificationInboxStatus): Boolean =
    latestSequence < other.latestSequence

internal fun NotificationInboxStatus.projectAfter(current: NotificationInboxStatus): NotificationInboxStatus {
    val latestSequenceDelta = latestSequence - current.latestSequence
    val projectedUnread = minOf(unreadCount.toLong(), current.unreadCount + latestSequenceDelta).toInt()
    return NotificationInboxStatus(
        latestSequence = latestSequence,
        seenThroughSequence = maxOf(current.seenThroughSequence, seenThroughSequence),
        unseenCount = minOf(
            unseenCount.toLong(),
            current.unseenCount + latestSequenceDelta,
            projectedUnread.toLong(),
        ).toInt(),
        unreadCount = projectedUnread,
    )
}

internal fun CachedNotificationInbox.isNewerThan(snapshotSequence: Long, latestSequence: Long): Boolean =
    this.snapshotSequence > snapshotSequence || status.latestSequence > latestSequence
