package com.kwabor.shared.domain.notification

import com.kwabor.shared.domain.catalog.ListingType

enum class NotificationKind {
    Suggestion,
    Sponsored,
    NewListing,
    EventAlert,
}

enum class NotificationPreferenceFamily {
    Suggestion,
    Sponsored,
    NewListing,
    EventAlert,
}

data class NotificationImage(
    val url: String,
    val alt: String,
)

data class NotificationListingTarget(
    val listingId: String,
    val listingType: ListingType,
    val listingName: String,
    val cityId: String?,
    val cityName: String?,
    val coverImage: NotificationImage?,
    val eventStartAtEpochMilliseconds: Long?,
)

sealed interface NotificationContent {
    val titleKey: String
    val bodyKey: String
    val listingName: String

    data class Suggestion(
        override val titleKey: String,
        override val bodyKey: String,
        override val listingName: String,
    ) : NotificationContent

    data class Sponsored(
        override val titleKey: String,
        override val bodyKey: String,
        override val listingName: String,
    ) : NotificationContent

    data class NewListing(
        override val titleKey: String,
        override val bodyKey: String,
        override val listingName: String,
        val cityName: String,
    ) : NotificationContent

    data class EventAlert(
        override val titleKey: String,
        override val bodyKey: String,
        override val listingName: String,
        val eventStartAtEpochMilliseconds: Long,
    ) : NotificationContent
}

data class NotificationInboxItem(
    val id: String,
    val sequence: Long,
    val kind: NotificationKind,
    val content: NotificationContent,
    val target: NotificationListingTarget?,
    val seenAtEpochMilliseconds: Long?,
    val readAtEpochMilliseconds: Long?,
    val hiddenAtEpochMilliseconds: Long?,
    val createdAtEpochMilliseconds: Long,
) {
    init {
        require(sequence > 0L) { "Notification sequence must be positive." }
        require(createdAtEpochMilliseconds >= 0L) { "Notification creation time must not be negative." }
        require(kind.matches(content)) { "Notification kind and content must match." }
        listOfNotNull(seenAtEpochMilliseconds, readAtEpochMilliseconds, hiddenAtEpochMilliseconds).forEach { stateAt ->
            require(stateAt >= createdAtEpochMilliseconds) {
                "Notification state timestamps cannot predate creation."
            }
        }
        if (readAtEpochMilliseconds != null || hiddenAtEpochMilliseconds != null) {
            val seenAt = requireNotNull(seenAtEpochMilliseconds) {
                "A read or hidden notification must also be seen."
            }
            require(readAtEpochMilliseconds == null || seenAt <= readAtEpochMilliseconds) {
                "A notification cannot be read before it is seen."
            }
            require(hiddenAtEpochMilliseconds == null || seenAt <= hiddenAtEpochMilliseconds) {
                "A notification cannot be hidden before it is seen."
            }
        }
        when (content) {
            is NotificationContent.Suggestion,
            is NotificationContent.Sponsored,
            is NotificationContent.NewListing,
            -> Unit
            is NotificationContent.EventAlert -> target?.let { availableTarget ->
                require(availableTarget.listingType == ListingType.Event) {
                    "An event alert must target an event listing."
                }
            }
        }
    }
}

data class NotificationPageRequest(
    val cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "Notification page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}

data class NotificationInboxPage(
    val items: List<NotificationInboxItem>,
    val snapshotSequence: Long?,
    val nextCursor: String?,
) {
    init {
        require(snapshotSequence == null || snapshotSequence >= 0L) {
            "Notification snapshot sequence must not be negative."
        }
        require(items.isNotEmpty() || snapshotSequence == null) {
            "An empty notification page has no row-derived snapshot sequence."
        }
        require(items.isEmpty() || snapshotSequence != null) {
            "A non-empty notification page requires a snapshot sequence."
        }
        require(nextCursor == null || items.isNotEmpty()) {
            "An empty notification page cannot expose a continuation cursor."
        }
        require(items.all { item -> item.sequence <= requireNotNull(snapshotSequence) }) {
            "Notification item sequence cannot exceed its page snapshot."
        }
        require(items.distinctBy(NotificationInboxItem::id).size == items.size) {
            "Notification page item ids must be unique."
        }
        require(items.distinctBy(NotificationInboxItem::sequence).size == items.size) {
            "Notification page item sequences must be unique."
        }
        require(items.zipWithNext().all { (newer, older) -> newer.sequence > older.sequence }) {
            "Notification page items must be in strict newest-first order."
        }
    }
}

data class NotificationInboxStatus(
    val latestSequence: Long,
    val seenThroughSequence: Long,
    val unseenCount: Int,
    val unreadCount: Int,
) {
    init {
        require(latestSequence >= 0L) { "Latest notification sequence must not be negative." }
        require(seenThroughSequence in 0L..latestSequence) {
            "Seen-through sequence must belong to the current notification history."
        }
        require(unseenCount >= 0) { "Unseen notification count must not be negative." }
        require(unreadCount >= 0) { "Unread notification count must not be negative." }
        require(unseenCount <= unreadCount) { "Unseen notification count cannot exceed unread count." }
        require(unseenCount.toLong() <= latestSequence) {
            "Unseen notification count cannot exceed the latest sequence."
        }
        require(unreadCount.toLong() <= latestSequence) {
            "Unread notification count cannot exceed the latest sequence."
        }
    }
}

data class NotificationMarkAllReadConfirmation(
    val status: NotificationInboxStatus,
    val throughSequence: Long,
    val mutationAtEpochMilliseconds: Long,
) {
    init {
        require(throughSequence in 1L..status.latestSequence) {
            "Notification read boundary must belong to the current notification history."
        }
        require(status.seenThroughSequence >= throughSequence) {
            "Notification mark-all confirmation must include the requested seen boundary."
        }
        require(mutationAtEpochMilliseconds >= 0L) {
            "Notification mark-all timestamp must not be negative."
        }
    }
}

data class NotificationItemMutation(
    val notificationId: String,
    val sequence: Long,
    val seenAtEpochMilliseconds: Long?,
    val readAtEpochMilliseconds: Long?,
    val hiddenAtEpochMilliseconds: Long?,
) {
    init {
        require(sequence > 0L) { "Notification mutation sequence must be positive." }
        if (readAtEpochMilliseconds != null || hiddenAtEpochMilliseconds != null) {
            val seenAt = requireNotNull(seenAtEpochMilliseconds) {
                "A read or hidden notification mutation must confirm seen state."
            }
            require(readAtEpochMilliseconds == null || seenAt <= readAtEpochMilliseconds) {
                "A notification mutation cannot read before seen."
            }
            require(hiddenAtEpochMilliseconds == null || seenAt <= hiddenAtEpochMilliseconds) {
                "A notification mutation cannot hide before seen."
            }
        }
    }
}

data class NotificationFamilyPreference(
    val family: NotificationPreferenceFamily,
    val enabled: Boolean,
    val updatedAtEpochMilliseconds: Long?,
)

data class NotificationPreferences(
    val entries: List<NotificationFamilyPreference>,
) {
    init {
        require(
            entries.map(NotificationFamilyPreference::family).toSet() ==
                NotificationPreferenceFamily.entries.toSet(),
        ) {
            "Notification preferences must contain each family exactly once."
        }
        require(entries.size == NotificationPreferenceFamily.entries.size) {
            "Notification preferences cannot contain duplicate families."
        }
    }

    fun preferenceFor(family: NotificationPreferenceFamily): NotificationFamilyPreference =
        entries.first { preference -> preference.family == family }

    companion object {
        fun disabled(): NotificationPreferences = NotificationPreferences(
            entries = NotificationPreferenceFamily.entries.map { family ->
                NotificationFamilyPreference(
                    family = family,
                    enabled = false,
                    updatedAtEpochMilliseconds = null,
                )
            },
        )
    }
}

private fun NotificationKind.matches(content: NotificationContent): Boolean = when (this) {
    NotificationKind.Suggestion -> content is NotificationContent.Suggestion
    NotificationKind.Sponsored -> content is NotificationContent.Sponsored
    NotificationKind.NewListing -> content is NotificationContent.NewListing
    NotificationKind.EventAlert -> content is NotificationContent.EventAlert
}
