package com.kwabor.shared.data.notification

internal fun String.requireCanonicalNotificationAccountId() {
    require(isCanonicalNotificationUuid()) { "Notification accountId must be a lowercase UUID." }
}

internal fun String.requireCanonicalNotificationId() {
    require(isCanonicalNotificationUuid()) { "Notification notificationId must be a lowercase UUID." }
}

internal fun Long.requirePositiveNotificationSequence(fieldName: String) {
    require(this > 0L) { "Notification $fieldName must be positive." }
}

internal fun Long.requireNotificationStoreTimestamp(fieldName: String) {
    require(this >= 0L) { "Notification $fieldName must not be negative." }
}

internal fun Int.requireNotificationOperationReadLimit() {
    require(this in 1..MAX_NOTIFICATION_SYNC_READ_LIMIT) {
        "Notification operation read limit must be between 1 and $MAX_NOTIFICATION_SYNC_READ_LIMIT."
    }
}

internal fun Long.requireNotificationOperationId() {
    require(this > 0L) { "Notification operationId must be positive." }
}

internal fun Int.requireNotificationAttemptCount() {
    require(this >= 0) { "Notification attempt count must not be negative." }
}

internal fun Int.requireNotificationRetryableAttemptCount() {
    require(this in 0 until Int.MAX_VALUE) { "Notification attempt count cannot be incremented." }
}

internal fun String.requireNotificationTerminalErrorCode() {
    require(isValidNotificationTerminalErrorCode()) { "Notification terminal error code is invalid." }
}
