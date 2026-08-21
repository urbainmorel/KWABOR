package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationKind
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.presentation.session.ViewerSessionScope

enum class NotificationTemporalGroup {
    Today,
    ThisWeek,
    Earlier,
}

data class NotificationItemTextUiModel(
    val title: String,
    val excerpt: String,
)

data class NotificationItemImageUiModel(
    val url: String,
    val alt: String,
)

data class NotificationTargetUiModel(
    val listingId: String,
    val listingType: ListingType,
    val listingName: String,
    val cityId: String?,
) {
    init {
        require(listingId.isNotBlank() && listingId == listingId.trim()) {
            "Notification target listing id must be normalized."
        }
        require(cityId == cityId.toAnalyticsSafeNotificationCityId()) {
            "Notification target city id must be null or analytics-safe."
        }
    }
}

enum class NotificationSponsoredBadgeTone {
    SponsoredYellow,
}

data class NotificationSponsoredBadgeUiModel(
    val label: String,
    val tone: NotificationSponsoredBadgeTone = NotificationSponsoredBadgeTone.SponsoredYellow,
)

data class NotificationItemMetadataUiModel(
    val relativeTime: String,
    val eventDateLabel: String?,
    val sponsoredBadge: NotificationSponsoredBadgeUiModel?,
    val isUnread: Boolean,
)

data class NotificationItemUiModel(
    val id: String,
    val sequence: Long,
    val kind: NotificationKind,
    val text: NotificationItemTextUiModel,
    val metadata: NotificationItemMetadataUiModel,
    val image: NotificationItemImageUiModel?,
    val target: NotificationTargetUiModel?,
)

data class NotificationSectionUiModel(
    val group: NotificationTemporalGroup,
    val title: String,
    val items: List<NotificationItemUiModel>,
) {
    init {
        require(items.isNotEmpty()) { "A notification section cannot be empty." }
    }
}

sealed interface NotificationPresentationResult {
    data class Content(
        val sections: List<NotificationSectionUiModel>,
    ) : NotificationPresentationResult

    data object InvalidPayload : NotificationPresentationResult
}

sealed interface NotificationPageContentUiState {
    data object Initial : NotificationPageContentUiState

    data object Skeleton : NotificationPageContentUiState

    data object Empty : NotificationPageContentUiState

    data class Content(
        val sections: List<NotificationSectionUiModel>,
    ) : NotificationPageContentUiState {
        init {
            require(sections.isNotEmpty()) { "Notification content requires at least one section." }
        }
    }

    data class Error(
        val message: String,
    ) : NotificationPageContentUiState
}

enum class NotificationPageOperation {
    Idle,
    Refreshing,
    Appending,
}

enum class NotificationMessagePlacement {
    Refresh,
    Append,
    Mutation,
}

data class NotificationUiMessage(
    val text: String,
    val placement: NotificationMessagePlacement,
)

data class NotificationPageWindow(
    val snapshotSequence: Long? = null,
    val nextCursor: String? = null,
) {
    init {
        require(snapshotSequence == null || snapshotSequence >= 0L) {
            "Notification snapshot sequence must not be negative."
        }
    }
}

data class NotificationPageUiState(
    val content: NotificationPageContentUiState = NotificationPageContentUiState.Initial,
    val window: NotificationPageWindow = NotificationPageWindow(),
    val operation: NotificationPageOperation = NotificationPageOperation.Idle,
    val isOffline: Boolean = false,
    val isLocalCacheUnavailable: Boolean = false,
    val message: NotificationUiMessage? = null,
) {
    val canLoadMore: Boolean
        get() =
            content is NotificationPageContentUiState.Content &&
                window.nextCursor != null &&
                operation == NotificationPageOperation.Idle &&
                !isOffline
}

data class NotificationBadgeUiState(
    val unseenCount: Int = 0,
    val unreadCount: Int = 0,
    val seenThroughSequence: Long = 0L,
) {
    init {
        require(unseenCount >= 0) { "Notification unseen count must not be negative." }
        require(unreadCount >= unseenCount) { "Notification unread count cannot be lower than unseen count." }
        require(seenThroughSequence >= 0L) { "Notification seen-through sequence must not be negative." }
    }

    val isVisible: Boolean
        get() = unseenCount > 0
}

data class NotificationPreferenceUiModel(
    val family: NotificationPreferenceFamily,
    val title: String,
    val enabled: Boolean,
)

data class NotificationPreferencesUiState(
    val entries: List<NotificationPreferenceUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val savingFamilies: Set<NotificationPreferenceFamily> = emptySet(),
    val isOffline: Boolean = false,
    val isLocalCacheUnavailable: Boolean = false,
    val message: String? = null,
)

data class NotificationUiState(
    val viewerScope: ViewerSessionScope = ViewerSessionScope.InitialGuest,
    val page: NotificationPageUiState = NotificationPageUiState(),
    val badge: NotificationBadgeUiState = NotificationBadgeUiState(),
    val preferences: NotificationPreferencesUiState = NotificationPreferencesUiState(),
    val presentationGeneration: Long = 0L,
) {
    init {
        require(presentationGeneration >= 0L) { "Notification presentation generation must not be negative." }
    }

    val accountScope: NotificationAccountScope?
        get() = viewerScope.toNotificationAccountScopeOrNull()
}

internal fun String?.toAnalyticsSafeNotificationCityId(): String? =
    this?.takeIf { candidate ->
        candidate.length in 1..MAXIMUM_ANALYTICS_CITY_ID_LENGTH && ANALYTICS_CITY_ID_PATTERN.matches(candidate)
    }

private val ANALYTICS_CITY_ID_PATTERN = Regex("[A-Za-z0-9_-]+")
private const val MAXIMUM_ANALYTICS_CITY_ID_LENGTH = 64
