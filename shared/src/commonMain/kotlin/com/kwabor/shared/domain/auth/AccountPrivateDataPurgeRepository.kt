package com.kwabor.shared.domain.auth

import com.kwabor.shared.domain.core.DomainResult

interface AccountPrivateDataPurgeRepository {
    suspend fun purge(expectedAccountId: String): DomainResult<AccountPrivateDataPurgeResult>
}

data class AccountPrivateDataPurgeResult(
    val interactionOperationCount: Int,
    val notificationItemCount: Int,
    val notificationSnapshotCount: Int,
    val notificationOperationCount: Int,
    val notificationPreferenceCount: Int,
) {
    init {
        require(
            listOf(
                interactionOperationCount,
                notificationItemCount,
                notificationSnapshotCount,
                notificationOperationCount,
                notificationPreferenceCount,
            ).all { count -> count >= 0 },
        ) {
            "Account private-data purge counts must not be negative."
        }
    }

    val totalCount: Int
        get() = interactionOperationCount +
            notificationItemCount +
            notificationSnapshotCount +
            notificationOperationCount +
            notificationPreferenceCount
}
