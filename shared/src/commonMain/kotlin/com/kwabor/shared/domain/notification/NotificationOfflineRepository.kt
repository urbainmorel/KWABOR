package com.kwabor.shared.domain.notification

import com.kwabor.shared.domain.core.DomainResult

interface NotificationOfflineRepository {
    suspend fun readInbox(expectedScope: NotificationAccountScope): DomainResult<NotificationCachedInbox?>

    suspend fun replaceInbox(
        expectedScope: NotificationAccountScope,
        page: NotificationInboxPage,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox>

    suspend fun appendInbox(
        expectedScope: NotificationAccountScope,
        expectedSnapshotSequence: Long,
        expectedNextCursor: String,
        page: NotificationInboxPage,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox>

    suspend fun storeStatus(
        expectedScope: NotificationAccountScope,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox>

    suspend fun applyItemMutation(
        expectedScope: NotificationAccountScope,
        mutation: NotificationItemMutation,
    ): DomainResult<Boolean>

    suspend fun applyMarkAllRead(
        expectedScope: NotificationAccountScope,
        confirmation: NotificationMarkAllReadConfirmation,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox>

    suspend fun readPreferences(expectedScope: NotificationAccountScope): DomainResult<NotificationCachedPreferences>

    suspend fun replacePreferences(
        expectedScope: NotificationAccountScope,
        preferences: NotificationPreferences,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedPreferences>
}
