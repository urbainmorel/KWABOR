package com.kwabor.shared.data.auth

import com.kwabor.shared.data.notification.NotificationStoreLock
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

class DataAccountPrivateDataPurgeRepositoryTest {
    @Test
    fun memoryOnlyStorageFailsClosedWithoutOpeningRoom() = runTest {
        val repository = DataAccountPrivateDataPurgeRepository(
            daoFactory = { error("Room must stay unopened in memory-only mode") },
            isDurable = false,
            lock = NotificationStoreLock(),
        )

        val result = repository.purge(ACCOUNT_ID)

        assertIs<DomainError.LocalStorageUnavailable>(assertIs<DomainResult.Failure>(result).error)
    }

    @Test
    fun invalidAccountFailsBeforeOpeningRoom() = runTest {
        val repository = DataAccountPrivateDataPurgeRepository(
            daoFactory = { error("Room must stay unopened for an invalid account") },
            isDurable = true,
            lock = NotificationStoreLock(),
        )

        val result = repository.purge("not-an-account")

        assertIs<DomainError.Validation>(assertIs<DomainResult.Failure>(result).error)
    }
}

private const val ACCOUNT_ID = "10000000-0000-4000-8000-000000000001"
