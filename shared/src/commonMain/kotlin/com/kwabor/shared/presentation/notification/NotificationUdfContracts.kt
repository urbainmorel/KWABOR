package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationKind
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.presentation.detail.CatalogDetailOpenRequestId
import com.kwabor.shared.presentation.session.ViewerSessionScope

data class NotificationDetailTicket(
    val value: Long,
) {
    init {
        require(value in 1L..MAXIMUM_NOTIFICATION_DETAIL_TICKET_VALUE) {
            "Notification detail ticket is outside the correlated detail range."
        }
    }
}

sealed interface NotificationIntent {
    sealed interface Lifecycle : NotificationIntent

    sealed interface Page : NotificationIntent

    sealed interface ItemAction : NotificationIntent

    sealed interface PreferenceAction : NotificationIntent

    sealed interface DetailPresentation : NotificationIntent

    data object ScreenAppeared : Lifecycle

    data object ScreenDisappeared : Lifecycle

    data object Foregrounded : Lifecycle

    data class ViewerContextChanged(
        val scope: ViewerSessionScope,
    ) : Lifecycle {
        val notificationScope: NotificationAccountScope?
            get() = scope.toNotificationAccountScopeOrNull()
    }

    data class SnapshotPresented(
        val scope: NotificationAccountScope,
        val snapshotSequence: Long,
        val presentationGeneration: Long,
    ) : Lifecycle {
        init {
            require(snapshotSequence >= 0L) { "Presented notification snapshot must not be negative." }
            require(presentationGeneration >= 0L) { "Presented notification generation must not be negative." }
        }
    }

    data object Retry : Page

    data object Refresh : Page

    data object LoadNext : Page

    data object MarkAllRead : ItemAction

    data class OpenNotification(
        val notificationId: String,
    ) : ItemAction

    data class HideNotification(
        val notificationId: String,
    ) : ItemAction

    data object OpenPreferences : PreferenceAction

    data object PreferencesScreenAppeared : PreferenceAction

    data object PreferencesScreenDisappeared : PreferenceAction

    data object RetryPreferences : PreferenceAction

    data class SetPreference(
        val family: NotificationPreferenceFamily,
        val enabled: Boolean,
    ) : PreferenceAction

    data class DetailSheetPresentationConfirmed(
        val ticket: NotificationDetailTicket,
        val listingId: String,
        val scope: NotificationAccountScope,
        val presentationGeneration: Long,
    ) : DetailPresentation {
        init {
            require(listingId.isNotBlank() && listingId == listingId.trim()) {
                "Confirmed notification listing id must be normalized."
            }
            require(presentationGeneration >= 0L) {
                "Confirmed notification lifecycle generation must not be negative."
            }
        }
    }

    data class DetailSheetPresentationFailed(
        val ticket: NotificationDetailTicket,
        val scope: NotificationAccountScope,
        val presentationGeneration: Long,
    ) : DetailPresentation {
        init {
            require(presentationGeneration >= 0L) { "Failed notification lifecycle generation must not be negative." }
        }
    }
}

internal const val MAXIMUM_NOTIFICATION_DETAIL_TICKET_VALUE: Long = Long.MAX_VALUE / 2L

sealed interface NotificationEffect {
    val scope: NotificationAccountScope
    val presentationGeneration: Long

    data class OpenCatalogDetail(
        val notificationId: String,
        val target: NotificationTargetUiModel,
        val ticket: NotificationDetailTicket,
        override val scope: NotificationAccountScope,
        override val presentationGeneration: Long,
    ) : NotificationEffect {
        val openRequestId: CatalogDetailOpenRequestId
            get() = CatalogDetailOpenRequestId.correlated(ticket.value)
    }

    data class TargetUnavailable(
        val notificationId: String,
        override val scope: NotificationAccountScope,
        override val presentationGeneration: Long,
    ) : NotificationEffect

    data class OpenNotificationPreferences(
        override val scope: NotificationAccountScope,
        override val presentationGeneration: Long,
    ) : NotificationEffect

    /** Emitted only after a matching [NotificationIntent.DetailSheetPresentationConfirmed]. */
    data class RecordOpenedAnalytics(
        val notificationId: String,
        val kind: NotificationKind,
        val cityId: String?,
        val ticket: NotificationDetailTicket,
        override val scope: NotificationAccountScope,
        override val presentationGeneration: Long,
    ) : NotificationEffect {
        init {
            require(cityId == cityId.toAnalyticsSafeNotificationCityId()) {
                "Notification analytics city id must be null or analytics-safe."
            }
        }
    }
}
