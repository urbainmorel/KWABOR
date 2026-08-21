package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.notification.NotificationPageRequest
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily

internal interface NotificationDataSource {
    suspend fun getStatus(expectedAccountId: String): NotificationInboxStatusDto

    suspend fun listInbox(
        expectedAccountId: String,
        page: NotificationPageRequest,
    ): NotificationInboxPageDto

    suspend fun markSeenThrough(expectedAccountId: String, throughSequence: Long): NotificationInboxStatusDto

    suspend fun markRead(expectedAccountId: String, notificationId: String): NotificationItemMutationDto

    suspend fun markAllReadThrough(
        expectedAccountId: String,
        throughSequence: Long,
    ): NotificationMarkAllReadResultDto

    suspend fun hide(expectedAccountId: String, notificationId: String): NotificationItemMutationDto

    suspend fun listPreferences(expectedAccountId: String): List<NotificationPreferenceRowDto>

    suspend fun setPreference(
        expectedAccountId: String,
        family: NotificationPreferenceFamily,
        enabled: Boolean,
    ): NotificationPreferenceRowDto
}

internal sealed class NotificationDataException(
    val domainError: DomainError,
    cause: Throwable? = null,
) : RuntimeException(domainError.messageKey, cause) {
    class AuthenticationRequired(cause: Throwable? = null) : NotificationDataException(
        domainError = DomainError.AuthenticationRequired(),
        cause = cause,
    )

    class PermissionDenied(cause: Throwable? = null) : NotificationDataException(
        domainError = DomainError.PermissionDenied("error.notifications.permission_denied"),
        cause = cause,
    )

    class NotFound(cause: Throwable? = null) : NotificationDataException(
        domainError = DomainError.NotFound("error.notifications.not_found"),
        cause = cause,
    )

    class Validation(
        messageKey: String = "error.notifications.invalid_request",
        cause: Throwable? = null,
    ) : NotificationDataException(
        domainError = DomainError.Validation(messageKey),
        cause = cause,
    )

    class NetworkUnavailable(cause: Throwable? = null) : NotificationDataException(
        domainError = DomainError.NetworkUnavailable(),
        cause = cause,
    )

    class Unexpected(cause: Throwable? = null) : NotificationDataException(
        domainError = DomainError.Unexpected(),
        cause = cause,
    )
}
