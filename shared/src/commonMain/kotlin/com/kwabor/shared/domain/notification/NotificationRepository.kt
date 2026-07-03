package com.kwabor.shared.domain.notification

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult

interface NotificationRepository {
    suspend fun listNotifications(page: PageRequest = PageRequest()): DomainResult<PageResult<KwaborNotification>>

    suspend fun markAsRead(notificationId: String): DomainResult<Unit>
}
