package com.kwabor.shared.domain.notification

import com.kwabor.shared.domain.core.DomainResult

interface NotificationSyncRepository {
    suspend fun submit(command: NotificationSyncCommand): DomainResult<NotificationSubmitOutcome>

    suspend fun loadPending(
        expectedScope: NotificationAccountScope,
    ): DomainResult<List<PendingNotificationSync>>

    suspend fun drainDue(expectedScope: NotificationAccountScope): DomainResult<NotificationDrainOutcome>

    suspend fun nextAttemptAt(expectedScope: NotificationAccountScope): DomainResult<Long?>

    suspend fun retryAccount(
        expectedScope: NotificationAccountScope,
        includeManualFailures: Boolean = false,
    ): DomainResult<Int>
}
