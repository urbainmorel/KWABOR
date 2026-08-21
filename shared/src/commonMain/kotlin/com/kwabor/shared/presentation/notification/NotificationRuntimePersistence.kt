package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationCachedInbox
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationOfflineRepository
import com.kwabor.shared.domain.notification.NotificationPreferences

internal class NotificationRuntimePersistence(
    private val repository: NotificationOfflineRepository?,
    private val clockProvider: ClockProvider,
) {
    suspend fun replaceInbox(
        scope: NotificationAccountScope,
        page: NotificationInboxPage,
        status: NotificationInboxStatus,
    ): DomainResult<NotificationCachedInbox>? {
        val availableRepository = repository ?: return null
        val now = validNow() ?: return DomainResult.Failure(DomainError.Unexpected())
        return availableRepository.replaceInbox(scope, page, status, now)
    }

    suspend fun appendInbox(
        request: NotificationAppendRequest,
        page: NotificationInboxPage,
    ): DomainResult<NotificationCachedInbox>? {
        val availableRepository = repository ?: return null
        val now = validNow() ?: return DomainResult.Failure(DomainError.Unexpected())
        return availableRepository.appendInbox(
            expectedScope = request.scope,
            expectedSnapshotSequence = request.snapshotSequence,
            expectedNextCursor = request.cursor,
            page = page,
            cachedAtEpochMilliseconds = now,
        )
    }

    suspend fun storeStatus(
        scope: NotificationAccountScope,
        status: NotificationInboxStatus,
    ): DomainResult<NotificationCachedInbox>? {
        val availableRepository = repository ?: return null
        val now = validNow() ?: return DomainResult.Failure(DomainError.Unexpected())
        return availableRepository.storeStatus(scope, status, now)
    }

    suspend fun replacePreferences(
        scope: NotificationAccountScope,
        preferences: NotificationPreferences,
    ) = repository?.let { availableRepository ->
        val now = validNow()
        if (now == null) {
            DomainResult.Failure(DomainError.Unexpected())
        } else {
            availableRepository.replacePreferences(scope, preferences, now)
        }
    }

    private fun validNow(): Long? = clockProvider.nowEpochMilliseconds().takeIf { now -> now >= 0L }
}
