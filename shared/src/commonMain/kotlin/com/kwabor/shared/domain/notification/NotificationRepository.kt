package com.kwabor.shared.domain.notification

import com.kwabor.shared.domain.core.DomainResult

interface NotificationInboxRepository {
    suspend fun getStatus(expectedScope: NotificationAccountScope): DomainResult<NotificationInboxStatus>

    suspend fun listInbox(
        expectedScope: NotificationAccountScope,
        page: NotificationPageRequest = NotificationPageRequest(),
    ): DomainResult<NotificationInboxPage>

    suspend fun markSeenThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ): DomainResult<NotificationInboxStatus>

    suspend fun markRead(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ): DomainResult<NotificationItemMutation>

    suspend fun markAllReadThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ): DomainResult<NotificationMarkAllReadConfirmation>

    suspend fun hide(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ): DomainResult<NotificationItemMutation>
}

interface NotificationPreferencesRepository {
    suspend fun getPreferences(expectedScope: NotificationAccountScope): DomainResult<NotificationPreferences>

    suspend fun setPreference(
        expectedScope: NotificationAccountScope,
        family: NotificationPreferenceFamily,
        enabled: Boolean,
    ): DomainResult<NotificationFamilyPreference>
}
