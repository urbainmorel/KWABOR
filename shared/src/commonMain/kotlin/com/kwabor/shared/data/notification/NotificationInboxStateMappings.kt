package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationItemMutation
import com.kwabor.shared.domain.notification.NotificationMarkAllReadConfirmation

internal fun NotificationInboxItem.mergeMonotoneState(current: NotificationInboxItem?): NotificationInboxItem =
    if (current == null || current.sequence != sequence) {
        this
    } else {
        copy(
            seenAtEpochMilliseconds = earliestTimestamp(seenAtEpochMilliseconds, current.seenAtEpochMilliseconds),
            readAtEpochMilliseconds = earliestTimestamp(readAtEpochMilliseconds, current.readAtEpochMilliseconds),
            hiddenAtEpochMilliseconds = earliestTimestamp(hiddenAtEpochMilliseconds, current.hiddenAtEpochMilliseconds),
        )
    }

internal fun List<NotificationInboxItem>.mergeMonotoneState(
    current: List<NotificationInboxItem>,
): List<NotificationInboxItem> {
    val currentById = current.associateBy(NotificationInboxItem::id)
    return map { incoming -> incoming.mergeMonotoneState(currentById[incoming.id]) }
}

internal fun NotificationInboxItem.mergeMutation(mutation: NotificationItemMutation): NotificationInboxItem = copy(
    seenAtEpochMilliseconds = earliestTimestamp(seenAtEpochMilliseconds, mutation.seenAtEpochMilliseconds),
    readAtEpochMilliseconds = earliestTimestamp(readAtEpochMilliseconds, mutation.readAtEpochMilliseconds),
    hiddenAtEpochMilliseconds = earliestTimestamp(hiddenAtEpochMilliseconds, mutation.hiddenAtEpochMilliseconds),
)

internal fun NotificationInboxItem.markReadThrough(
    confirmation: NotificationMarkAllReadConfirmation,
): NotificationInboxItem = if (sequence > confirmation.throughSequence || hiddenAtEpochMilliseconds != null) {
    this
} else {
    val effectiveTimestamp = maxOf(createdAtEpochMilliseconds, confirmation.mutationAtEpochMilliseconds)
    copy(
        seenAtEpochMilliseconds = seenAtEpochMilliseconds ?: effectiveTimestamp,
        readAtEpochMilliseconds = readAtEpochMilliseconds ?: effectiveTimestamp,
    )
}

private fun earliestTimestamp(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> minOf(first, second)
}

internal fun projectNotificationCount(
    serverCount: Int,
    remoteItems: List<NotificationInboxItem>,
    cachedItems: List<NotificationInboxItem>,
    predicate: (NotificationInboxItem) -> Boolean,
): Int {
    val projected = serverCount.toLong() + cachedItems.count(predicate) - remoteItems.count(predicate)
    return maxOf(projected.coerceAtLeast(0L), cachedItems.count(predicate).toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

internal fun NotificationInboxItem.isUnseen(seenThroughSequence: Long): Boolean =
    hiddenAtEpochMilliseconds == null && seenAtEpochMilliseconds == null && sequence > seenThroughSequence

internal fun NotificationInboxItem.isUnread(): Boolean =
    hiddenAtEpochMilliseconds == null && readAtEpochMilliseconds == null

internal fun Boolean.toCountDelta(): Int = if (this) 1 else 0

internal fun List<NotificationInboxItem>.isStrictNotificationOrder(): Boolean = zipWithNext().all { (newer, older) ->
    newer.sequence > older.sequence || (newer.sequence == older.sequence && newer.id > older.id)
}
