package com.kwabor.shared.data.notification

import com.kwabor.shared.data.core.isValidUuid
import kotlin.time.Instant

internal fun String.requireNotificationCursor(fieldName: String): String {
    val isInvalid = listOf(
        isEmpty(),
        encodeToByteArray().size > MAXIMUM_CURSOR_UTF8_BYTES,
        trim() != this,
        any(Char::isWhitespace),
        any(Char::isISOControl),
    ).any { condition -> condition }
    if (isInvalid) invalidNotificationValue(fieldName, "invalid cursor")
    return this
}

internal fun String.requireNotificationUuid(fieldName: String): String {
    if (!isValidUuid() || this != lowercase()) {
        invalidNotificationValue(fieldName, "invalid UUID")
    }
    return this
}

internal fun String.requireNotificationAccountId(fieldName: String = "expected_account_id"): String =
    trim().lowercase().also { canonical ->
        if (!canonical.isValidUuid()) {
            throw NotificationDataException.Validation("error.notifications.$fieldName.invalid")
        }
    }

internal fun String.requireNotificationTimestamp(fieldName: String): Long {
    val instant = try {
        Instant.parse(this)
    } catch (exception: IllegalArgumentException) {
        invalidNotificationValue(fieldName, this, exception)
    }
    if (instant < MINIMUM_NOTIFICATION_INSTANT || instant >= EXCLUSIVE_MAXIMUM_NOTIFICATION_INSTANT) {
        invalidNotificationValue(fieldName, this)
    }
    return instant.toEpochMilliseconds()
}

internal fun invalidNotificationValue(fieldName: String, value: String, cause: Throwable? = null): Nothing =
    throw NotificationDataException.Unexpected(
        IllegalStateException("Invalid notification value for $fieldName: $value", cause),
    )

private const val MAXIMUM_CURSOR_UTF8_BYTES = 4_096
private val MINIMUM_NOTIFICATION_INSTANT = Instant.parse("0001-01-01T00:00:00Z")
private val EXCLUSIVE_MAXIMUM_NOTIFICATION_INSTANT = Instant.parse("+10000-01-01T00:00:00Z")
