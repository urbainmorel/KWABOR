package com.kwabor.shared.data.auth

import androidx.sqlite.SQLiteException
import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.data.local.AccountPrivateDataPurgeDao
import com.kwabor.shared.data.local.AccountPrivateDataPurgeRecord
import com.kwabor.shared.data.notification.NotificationStoreLock
import com.kwabor.shared.domain.auth.AccountPrivateDataPurgeRepository
import com.kwabor.shared.domain.auth.AccountPrivateDataPurgeResult
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

internal class DataAccountPrivateDataPurgeRepository(
    daoFactory: () -> AccountPrivateDataPurgeDao,
    private val isDurable: Boolean,
    private val lock: NotificationStoreLock,
) : AccountPrivateDataPurgeRepository {
    private val dao: AccountPrivateDataPurgeDao by lazy(daoFactory)

    override suspend fun purge(expectedAccountId: String): DomainResult<AccountPrivateDataPurgeResult> =
        runAccountPrivateDataPurgeCall {
            if (!isDurable) throw AccountPrivateDataPurgeStorageUnavailableException
            val accountId = expectedAccountId.toCanonicalPurgeAccountId()
            lock.mutex.withLock { dao.purgeAccount(accountId).toDomain() }
        }
}

private fun AccountPrivateDataPurgeRecord.toDomain(): AccountPrivateDataPurgeResult =
    AccountPrivateDataPurgeResult(
        interactionOperationCount = interactionOperationCount,
        notificationItemCount = notificationItemCount,
        notificationSnapshotCount = notificationSnapshotCount,
        notificationOperationCount = notificationOperationCount,
        notificationPreferenceCount = notificationPreferenceCount,
    )

private fun String.toCanonicalPurgeAccountId(): String = trim().lowercase().also { accountId ->
    if (!accountId.isValidUuid()) throw AccountPrivateDataPurgeValidationException
}

private suspend inline fun <T> runAccountPrivateDataPurgeCall(crossinline action: suspend () -> T): DomainResult<T> =
    try {
        DomainResult.Success(action())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: AccountPrivateDataPurgeValidationException) {
        DomainResult.Failure(DomainError.Validation("error.account_deletion.account_id_invalid"))
    } catch (_: AccountPrivateDataPurgeStorageUnavailableException) {
        DomainResult.Failure(DomainError.LocalStorageUnavailable())
    } catch (_: SQLiteException) {
        DomainResult.Failure(DomainError.LocalStorageUnavailable())
    } catch (_: IllegalStateException) {
        DomainResult.Failure(DomainError.LocalStorageUnavailable())
    }

private data object AccountPrivateDataPurgeValidationException : RuntimeException()

private data object AccountPrivateDataPurgeStorageUnavailableException : RuntimeException()
