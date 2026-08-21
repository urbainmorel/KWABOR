package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.notification.NotificationPageRequest
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal class SupabaseNotificationDataSource(
    private val postgrest: Postgrest,
) : NotificationDataSource {
    override suspend fun getStatus(expectedAccountId: String): NotificationInboxStatusDto =
        runNotificationPostgrest {
            postgrest.rpc(
                function = GET_NOTIFICATION_INBOX_STATUS,
                parameters = NotificationAccountParametersDto(expectedAccountId),
            ).decodeNotificationRows<NotificationInboxStatusDto>()
                .requireSingleNotificationRow(GET_NOTIFICATION_INBOX_STATUS)
                .also { row -> row.toDomain() }
        }

    override suspend fun listInbox(
        expectedAccountId: String,
        page: NotificationPageRequest,
    ): NotificationInboxPageDto = runNotificationPostgrest {
        postgrest.rpc(
            function = LIST_NOTIFICATION_INBOX,
            parameters = ListNotificationInboxParametersDto(
                expectedAccountId = expectedAccountId,
                cursor = page.cursor,
                limit = page.limit,
            ),
        ).decodeNotificationRows<NotificationInboxRowDto>()
            .toNotificationInboxPageDto(page.limit)
    }

    override suspend fun markSeenThrough(
        expectedAccountId: String,
        throughSequence: Long,
    ): NotificationInboxStatusDto = runNotificationPostgrest {
        postgrest.rpc(
            function = MARK_NOTIFICATION_INBOX_SEEN,
            parameters = MarkNotificationSeenParametersDto(
                expectedAccountId = expectedAccountId,
                seenThroughSequence = throughSequence,
            ),
        ).decodeNotificationRows<NotificationInboxStatusDto>()
            .requireSingleNotificationRow(MARK_NOTIFICATION_INBOX_SEEN)
            .also { row -> row.toDomain() }
    }

    override suspend fun markRead(
        expectedAccountId: String,
        notificationId: String,
    ): NotificationItemMutationDto = runNotificationPostgrest {
        postgrest.rpc(
            function = MARK_NOTIFICATION_READ,
            parameters = NotificationItemParametersDto(
                expectedAccountId = expectedAccountId,
                notificationId = notificationId,
            ),
        ).decodeNotificationRows<NotificationItemMutationDto>()
            .requireSingleNotificationRow(MARK_NOTIFICATION_READ)
            .also { row -> row.toReadDomain(notificationId) }
    }

    override suspend fun markAllReadThrough(
        expectedAccountId: String,
        throughSequence: Long,
    ): NotificationMarkAllReadResultDto = runNotificationPostgrest {
        postgrest.rpc(
            function = MARK_ALL_NOTIFICATIONS_READ,
            parameters = NotificationThroughSequenceParametersDto(
                expectedAccountId = expectedAccountId,
                throughSequence = throughSequence,
            ),
        ).decodeNotificationRows<NotificationMarkAllReadResultDto>()
            .requireSingleNotificationRow(MARK_ALL_NOTIFICATIONS_READ)
            .also { result -> result.toDomain(throughSequence) }
    }

    override suspend fun hide(
        expectedAccountId: String,
        notificationId: String,
    ): NotificationItemMutationDto = runNotificationPostgrest {
        postgrest.rpc(
            function = HIDE_NOTIFICATION,
            parameters = NotificationItemParametersDto(
                expectedAccountId = expectedAccountId,
                notificationId = notificationId,
            ),
        ).decodeNotificationRows<NotificationItemMutationDto>()
            .requireSingleNotificationRow(HIDE_NOTIFICATION)
            .also { row -> row.toHiddenDomain(notificationId) }
    }

    override suspend fun listPreferences(expectedAccountId: String): List<NotificationPreferenceRowDto> =
        runNotificationPostgrest {
            postgrest.rpc(
                function = LIST_NOTIFICATION_PREFERENCES,
                parameters = NotificationAccountParametersDto(expectedAccountId),
            ).decodeNotificationRows<NotificationPreferenceRowDto>()
                .also { rows -> rows.toDomainPreferences() }
        }

    override suspend fun setPreference(
        expectedAccountId: String,
        family: NotificationPreferenceFamily,
        enabled: Boolean,
    ): NotificationPreferenceRowDto = runNotificationPostgrest {
        postgrest.rpc(
            function = SET_NOTIFICATION_PREFERENCE,
            parameters = SetNotificationPreferenceParametersDto(
                expectedAccountId = expectedAccountId,
                family = family.toWireValue(),
                enabled = enabled,
            ),
        ).decodeNotificationRows<NotificationPreferenceRowDto>()
            .requireSingleNotificationRow(SET_NOTIFICATION_PREFERENCE)
            .also { row ->
                val preference = row.toDomain()
                if (
                    preference.family != family ||
                    preference.enabled != enabled ||
                    preference.updatedAtEpochMilliseconds == null
                ) {
                    invalidNotificationValue("preference", "mutation did not confirm requested state")
                }
            }
    }
}

private inline fun <reified T> PostgrestResult.decodeNotificationRows(): List<T> =
    decodeList<JsonObject>().map { row -> strictNotificationJson.decodeFromJsonElement<T>(row) }

private fun <T> List<T>.requireSingleNotificationRow(function: String): T {
    if (size != 1) {
        throw NotificationDataException.Unexpected(
            IllegalStateException("Notification RPC $function must return exactly one row."),
        )
    }
    return single()
}

private suspend fun <T> runNotificationPostgrest(block: suspend () -> T): T = try {
    block()
} catch (exception: PostgrestRestException) {
    throw exception.toNotificationDataException()
} catch (exception: RestException) {
    throw exception.toNotificationDataException()
} catch (exception: HttpRequestTimeoutException) {
    throw NotificationDataException.NetworkUnavailable(exception)
} catch (exception: HttpRequestException) {
    throw NotificationDataException.NetworkUnavailable(exception)
} catch (exception: SerializationException) {
    throw NotificationDataException.Unexpected(exception)
}

private fun RestException.toNotificationDataException(): NotificationDataException {
    val codeMapped = (this as? PostgrestRestException)?.toCodeMappedNotificationDataException()
    return codeMapped ?: when (statusCode) {
        HTTP_UNAUTHORIZED -> NotificationDataException.AuthenticationRequired(this)
        HTTP_FORBIDDEN -> NotificationDataException.PermissionDenied(this)
        HTTP_NOT_FOUND -> NotificationDataException.NotFound(this)
        HTTP_BAD_REQUEST,
        HTTP_CONFLICT,
        HTTP_UNPROCESSABLE_CONTENT,
        -> NotificationDataException.Validation(cause = this)
        HTTP_BAD_GATEWAY,
        HTTP_SERVICE_UNAVAILABLE,
        HTTP_GATEWAY_TIMEOUT,
        -> NotificationDataException.NetworkUnavailable(this)
        else -> NotificationDataException.Unexpected(this)
    }
}

private fun PostgrestRestException.toCodeMappedNotificationDataException(): NotificationDataException? = when {
    code?.startsWith(POSTGREST_SCHEMA_CACHE_ERROR_PREFIX) == true -> NotificationDataException.Unexpected(this)
    else -> when (code) {
        "P0002", "PGRST116" -> NotificationDataException.NotFound(this)
        "42501" -> NotificationDataException.AuthenticationRequired(this)
        "22023", "23503", "23505", "23514" -> NotificationDataException.Validation(cause = this)
        else -> null
    }
}

private val strictNotificationJson = Json

private const val GET_NOTIFICATION_INBOX_STATUS = "get_notification_inbox_status_v1"
private const val LIST_NOTIFICATION_INBOX = "list_notification_inbox_v1"
private const val MARK_NOTIFICATION_INBOX_SEEN = "mark_notification_inbox_seen_v1"
private const val MARK_NOTIFICATION_READ = "mark_notification_read_v1"
private const val MARK_ALL_NOTIFICATIONS_READ = "mark_all_notifications_read_v1"
private const val HIDE_NOTIFICATION = "hide_notification_v1"
private const val LIST_NOTIFICATION_PREFERENCES = "list_notification_preferences_v1"
private const val SET_NOTIFICATION_PREFERENCE = "set_notification_preference_v1"
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE_CONTENT = 422
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTP_GATEWAY_TIMEOUT = 504
private const val POSTGREST_SCHEMA_CACHE_ERROR_PREFIX = "PGRST2"
