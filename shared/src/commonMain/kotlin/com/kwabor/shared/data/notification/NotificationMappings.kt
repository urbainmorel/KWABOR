package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.notification.NotificationFamilyPreference
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationItemMutation
import com.kwabor.shared.domain.notification.NotificationKind
import com.kwabor.shared.domain.notification.NotificationMarkAllReadConfirmation
import com.kwabor.shared.domain.notification.NotificationPageRequest
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences

internal fun NotificationInboxRowDto.toDomain(allowHidden: Boolean = false): NotificationInboxItem {
    val mappedKind = family.toNotificationKind()
    val createdAtEpochMilliseconds = createdAt.requireNotificationTimestamp("created_at")
    val eventStartAtEpochMilliseconds = targetEventStartAt?.requireNotificationTimestamp("target_event_start_at")
    val target = requireNotificationTarget(eventStartAtEpochMilliseconds)
    val content = requireNotificationContent(mappedKind)
    val mappedSeenAt = seenAt?.requireNotificationTimestamp("seen_at")
    val mappedReadAt = readAt?.requireNotificationTimestamp("read_at")
    val mappedHiddenAt = hiddenAt?.requireNotificationTimestamp("hidden_at")
    if (!allowHidden && mappedHiddenAt != null) {
        invalidNotificationValue("hidden_at", "hidden rows must not be returned by the inbox RPC")
    }
    listOfNotNull(mappedSeenAt, mappedReadAt, mappedHiddenAt).forEach { stateTimestamp ->
        if (stateTimestamp < createdAtEpochMilliseconds) {
            invalidNotificationValue("notification_state", "state timestamp predates notification creation")
        }
    }
    requireSeenBeforeTerminalState(mappedSeenAt, mappedReadAt, mappedHiddenAt)
    if (sponsored != (mappedKind == NotificationKind.Sponsored)) {
        invalidNotificationValue("sponsored", sponsored.toString())
    }
    if (sequenceNumber <= 0L || snapshotSequence < sequenceNumber) {
        invalidNotificationValue("sequence_number", sequenceNumber.toString())
    }
    rowCursor.requireNotificationCursor("row_cursor")
    return NotificationInboxItem(
        id = notificationId.requireNotificationUuid("notification_id"),
        sequence = sequenceNumber,
        kind = mappedKind,
        content = content,
        target = target,
        seenAtEpochMilliseconds = mappedSeenAt,
        readAtEpochMilliseconds = mappedReadAt,
        hiddenAtEpochMilliseconds = mappedHiddenAt,
        createdAtEpochMilliseconds = createdAtEpochMilliseconds,
    )
}

internal fun NotificationInboxPageDto.toDomain(): NotificationInboxPage {
    val mappedItems = items.map { row -> row.toDomain() }
    if (mappedItems.isNotEmpty() && snapshotSequence != items.first().snapshotSequence) {
        invalidNotificationValue("snapshot_sequence", "page envelope differs from its rows")
    }
    if (mappedItems.isEmpty() && snapshotSequence != null) {
        invalidNotificationValue("snapshot_sequence", "empty page must not invent a row snapshot")
    }
    nextCursor?.requireNotificationCursor("next_cursor")
    return NotificationInboxPage(
        items = mappedItems,
        snapshotSequence = snapshotSequence,
        nextCursor = nextCursor,
    )
}

internal fun List<NotificationInboxRowDto>.toNotificationInboxPageDto(limit: Int): NotificationInboxPageDto {
    requireNotificationPageRows(limit)
    val expectedSnapshot = firstOrNull()?.snapshotSequence
    if (any { row -> row.snapshotSequence != expectedSnapshot }) {
        invalidNotificationValue("snapshot_sequence", "rows do not share one snapshot")
    }
    requireStrictNotificationPageOrder()
    val retained = take(limit)
    return NotificationInboxPageDto(
        items = retained,
        snapshotSequence = retained.firstOrNull()?.snapshotSequence,
        nextCursor = if (size > limit) retained.last().rowCursor else null,
    )
}

private fun List<NotificationInboxRowDto>.requireNotificationPageRows(limit: Int) {
    if (limit !in 1..NotificationPageRequest.MAX_LIMIT) {
        invalidNotificationValue("page_limit", limit.toString())
    }
    if (size > limit + 1) {
        invalidNotificationValue("items", "more than one sentinel row")
    }
    forEach { row -> row.toDomain() }
    if (distinctBy { row -> row.notificationId.lowercase() }.size != size) {
        invalidNotificationValue("items", "duplicate notification IDs")
    }
    if (distinctBy(NotificationInboxRowDto::sequenceNumber).size != size) {
        invalidNotificationValue("items", "duplicate notification sequences")
    }
    if (distinctBy(NotificationInboxRowDto::rowCursor).size != size) {
        invalidNotificationValue("items", "duplicate row cursors")
    }
}

private fun List<NotificationInboxRowDto>.requireStrictNotificationPageOrder() {
    zipWithNext().forEach { (newer, older) ->
        val strictlyNewestFirst = newer.sequenceNumber > older.sequenceNumber ||
            (newer.sequenceNumber == older.sequenceNumber && newer.notificationId > older.notificationId)
        if (!strictlyNewestFirst) {
            invalidNotificationValue("items", "rows are not in strict newest-first order")
        }
    }
}

internal fun NotificationInboxStatusDto.toDomain(): NotificationInboxStatus = try {
    NotificationInboxStatus(
        latestSequence = latestSequence,
        seenThroughSequence = seenThroughSequence,
        unseenCount = unseenCount,
        unreadCount = unreadCount,
    )
} catch (exception: IllegalArgumentException) {
    invalidNotificationValue("status", "invalid counters or sequence", exception)
}

internal fun NotificationMarkAllReadResultDto.toDomain(
    throughSequence: Long,
): NotificationMarkAllReadConfirmation = try {
    NotificationMarkAllReadConfirmation(
        status = NotificationInboxStatus(
            latestSequence = latestSequence,
            seenThroughSequence = seenThroughSequence,
            unseenCount = unseenCount,
            unreadCount = unreadCount,
        ),
        throughSequence = throughSequence,
        mutationAtEpochMilliseconds = mutationAt.requireNotificationTimestamp("mutation_at"),
    )
} catch (exception: IllegalArgumentException) {
    invalidNotificationValue("mark_all_read", "invalid confirmation", exception)
}

internal fun NotificationItemMutationDto.toReadDomain(expectedNotificationId: String): NotificationItemMutation {
    val mutation = toDomain(expectedNotificationId)
    if (mutation.readAtEpochMilliseconds == null) {
        invalidNotificationValue("read_at", "read mutation did not confirm a timestamp")
    }
    return mutation
}

internal fun NotificationItemMutationDto.toHiddenDomain(expectedNotificationId: String): NotificationItemMutation {
    val mutation = toDomain(expectedNotificationId)
    if (mutation.hiddenAtEpochMilliseconds == null) {
        invalidNotificationValue("hidden_at", "hide mutation did not confirm a timestamp")
    }
    return mutation
}

internal fun List<NotificationPreferenceRowDto>.toDomainPreferences(): NotificationPreferences {
    val mapped = map(NotificationPreferenceRowDto::toDomain)
    if (mapped.distinctBy(NotificationFamilyPreference::family).size != mapped.size) {
        invalidNotificationValue("preferences", "duplicate family")
    }
    val byFamily = mapped.associateBy(NotificationFamilyPreference::family)
    return NotificationPreferences(
        entries = NotificationPreferenceFamily.entries.map { family ->
            byFamily[family] ?: NotificationFamilyPreference(
                family = family,
                enabled = false,
                updatedAtEpochMilliseconds = null,
            )
        },
    )
}

internal fun NotificationPreferenceRowDto.toDomain(): NotificationFamilyPreference = NotificationFamilyPreference(
    family = family.toNotificationPreferenceFamily(),
    enabled = enabled,
    updatedAtEpochMilliseconds = updatedAt?.requireNotificationTimestamp("updated_at"),
)

internal fun String.toNotificationPreferenceFamily(): NotificationPreferenceFamily = when (this) {
    "suggestion" -> NotificationPreferenceFamily.Suggestion
    "sponsored" -> NotificationPreferenceFamily.Sponsored
    "new_listing" -> NotificationPreferenceFamily.NewListing
    "event_alert" -> NotificationPreferenceFamily.EventAlert
    else -> invalidNotificationValue("family", this)
}

internal fun NotificationPreferenceFamily.toWireValue(): String = when (this) {
    NotificationPreferenceFamily.Suggestion -> "suggestion"
    NotificationPreferenceFamily.Sponsored -> "sponsored"
    NotificationPreferenceFamily.NewListing -> "new_listing"
    NotificationPreferenceFamily.EventAlert -> "event_alert"
}

internal fun NotificationKind.toWireValue(): String = when (this) {
    NotificationKind.Suggestion -> "suggestion"
    NotificationKind.Sponsored -> "sponsored"
    NotificationKind.NewListing -> "new_listing"
    NotificationKind.EventAlert -> "event_alert"
}

private fun NotificationItemMutationDto.toDomain(expectedNotificationId: String): NotificationItemMutation {
    val mappedId = notificationId.requireNotificationUuid("notification_id")
    if (mappedId != expectedNotificationId) {
        invalidNotificationValue("notification_id", "does not match request")
    }
    if (sequenceNumber <= 0L) {
        invalidNotificationValue("sequence_number", sequenceNumber.toString())
    }
    val mappedSeenAt = seenAt?.requireNotificationTimestamp("seen_at")
    val mappedReadAt = readAt?.requireNotificationTimestamp("read_at")
    val mappedHiddenAt = hiddenAt?.requireNotificationTimestamp("hidden_at")
    requireSeenBeforeTerminalState(mappedSeenAt, mappedReadAt, mappedHiddenAt)
    return try {
        NotificationItemMutation(
            notificationId = mappedId,
            sequence = sequenceNumber,
            seenAtEpochMilliseconds = mappedSeenAt,
            readAtEpochMilliseconds = mappedReadAt,
            hiddenAtEpochMilliseconds = mappedHiddenAt,
        )
    } catch (exception: IllegalArgumentException) {
        invalidNotificationValue("mutation", "invalid state timestamps", exception)
    }
}

private fun requireSeenBeforeTerminalState(seenAt: Long?, readAt: Long?, hiddenAt: Long?) {
    if ((readAt != null || hiddenAt != null) && seenAt == null) {
        invalidNotificationValue("seen_at", "required by read or hidden state")
    }
    if (seenAt != null && readAt != null && seenAt > readAt) {
        invalidNotificationValue("read_at", "predates seen_at")
    }
    if (seenAt != null && hiddenAt != null && seenAt > hiddenAt) {
        invalidNotificationValue("hidden_at", "predates seen_at")
    }
}
