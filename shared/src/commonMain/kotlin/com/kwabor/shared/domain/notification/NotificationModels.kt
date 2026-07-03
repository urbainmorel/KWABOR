package com.kwabor.shared.domain.notification

enum class NotificationType {
    Social,
    Listing,
    Promotion,
    System,
}

data class KwaborNotification(
    val id: String,
    val type: NotificationType,
    val titleKey: String,
    val bodyKey: String,
    val relatedListingId: String?,
    val read: Boolean,
    val createdAtEpochMilliseconds: Long,
)
