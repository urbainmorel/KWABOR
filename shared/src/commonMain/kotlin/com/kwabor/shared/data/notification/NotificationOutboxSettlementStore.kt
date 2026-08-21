package com.kwabor.shared.data.notification

import com.kwabor.shared.data.local.NotificationConfirmationSettlementDao
import com.kwabor.shared.data.local.NotificationMarkAllReadSettlementRecord
import com.kwabor.shared.data.local.NotificationOutboxDao
import com.kwabor.shared.data.local.NotificationPreferenceEntity
import kotlinx.coroutines.sync.withLock

internal class NotificationOutboxSettlementStore(
    private val daoFactory: () -> NotificationOutboxDao,
    private val confirmationDaoFactory: () -> NotificationConfirmationSettlementDao,
    internal val isDurable: Boolean,
    private val lock: NotificationStoreLock,
) {
    private val dao: NotificationOutboxDao by lazy(daoFactory)
    private val confirmationDao: NotificationConfirmationSettlementDao by lazy(confirmationDaoFactory)

    internal constructor(
        dao: NotificationOutboxDao,
        confirmationDao: NotificationConfirmationSettlementDao,
        lock: NotificationStoreLock = NotificationStoreLock(),
    ) : this(
        daoFactory = { dao },
        confirmationDaoFactory = { confirmationDao },
        isDurable = true,
        lock = lock,
    )

    suspend fun settleConfirmedStatus(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Int,
        prepare: suspend () -> PreparedNotificationStatusSettlement,
    ): NotificationConfirmedOperationSettlement = settleConfirmed(accountId, operationId, expectedAttemptCount) {
        val prepared = prepare()
        confirmationDao.settleStatus(
            accountId,
            operationId,
            expectedAttemptCount.toLong(),
            prepared.status,
            prepared.snapshotWhenAbsent,
        )
    }

    suspend fun settleConfirmedItem(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Int,
        prepare: suspend () -> PreparedNotificationItemSettlement,
    ): NotificationConfirmedOperationSettlement = settleConfirmed(accountId, operationId, expectedAttemptCount) {
        val prepared = prepare()
        confirmationDao.settleItemAndStatus(
            accountId,
            operationId,
            expectedAttemptCount.toLong(),
            prepared.item,
            prepared.status,
            prepared.snapshotWhenAbsent,
        )
    }

    suspend fun settleConfirmedMarkAllRead(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Int,
        prepare: suspend () -> PreparedNotificationMarkAllReadSettlement,
    ): NotificationConfirmedOperationSettlement = settleConfirmed(accountId, operationId, expectedAttemptCount) {
        val prepared = prepare()
        confirmationDao.settleMarkAllRead(
            accountId,
            operationId,
            expectedAttemptCount.toLong(),
            NotificationMarkAllReadSettlementRecord(
                throughSequence = prepared.throughSequence,
                mutationAtEpochMilliseconds = prepared.mutationAtEpochMilliseconds,
                snapshot = prepared.snapshot,
                snapshotWasPresent = prepared.snapshotWasPresent,
            ),
        )
    }

    suspend fun settleConfirmedPreferences(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Int,
        prepare: suspend () -> List<NotificationPreferenceEntity>,
    ): NotificationConfirmedOperationSettlement = settleConfirmed(accountId, operationId, expectedAttemptCount) {
        confirmationDao.settlePreferences(
            accountId,
            operationId,
            expectedAttemptCount.toLong(),
            prepare(),
        )
    }

    private suspend fun settleConfirmed(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Int,
        settleTransaction: suspend () -> Boolean,
    ): NotificationConfirmedOperationSettlement = lock.mutex.withLock {
        requireArguments(accountId, operationId, expectedAttemptCount)
        val current = dao.findOperationById(accountId, operationId)
        if (
            current == null ||
            current.attemptCount != expectedAttemptCount.toLong() ||
            current.terminalErrorCode != null
        ) {
            return@withLock NotificationConfirmedOperationSettlement.Superseded
        }
        if (settleTransaction()) {
            NotificationConfirmedOperationSettlement.Settled
        } else {
            NotificationConfirmedOperationSettlement.Superseded
        }
    }

    suspend fun recordOperationRetry(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Int,
        nextAttemptAtEpochMilliseconds: Long,
    ): Boolean = lock.mutex.withLock {
        requireArguments(accountId, operationId, expectedAttemptCount)
        expectedAttemptCount.requireNotificationRetryableAttemptCount()
        nextAttemptAtEpochMilliseconds.requireNotificationStoreTimestamp("nextAttemptAtEpochMilliseconds")
        dao.recordOperationRetry(
            accountId = accountId,
            operationId = operationId,
            expectedAttemptCount = expectedAttemptCount.toLong(),
            nextAttemptAtEpochMilliseconds = nextAttemptAtEpochMilliseconds,
        ) == 1
    }

    suspend fun recordOperationTerminalFailure(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Int,
        terminalErrorCode: String,
    ): Boolean = lock.mutex.withLock {
        requireArguments(accountId, operationId, expectedAttemptCount, terminalErrorCode)
        expectedAttemptCount.requireNotificationRetryableAttemptCount()
        dao.recordOperationTerminalFailure(
            accountId = accountId,
            operationId = operationId,
            expectedAttemptCount = expectedAttemptCount.toLong(),
            terminalErrorCode = terminalErrorCode,
        ) == 1
    }

    suspend fun rearmOperation(
        accountId: String,
        operationId: Long,
        expectedTerminalErrorCode: String,
        rearmedAtEpochMilliseconds: Long,
    ): Boolean = lock.mutex.withLock {
        requireArguments(accountId, operationId, 0, expectedTerminalErrorCode)
        rearmedAtEpochMilliseconds.requireNotificationStoreTimestamp("rearmedAtEpochMilliseconds")
        dao.rearmOperation(accountId, operationId, expectedTerminalErrorCode, rearmedAtEpochMilliseconds) == 1
    }

    private fun requireArguments(
        accountId: String,
        operationId: Long,
        expectedAttemptCount: Int,
        terminalErrorCode: String? = null,
    ) {
        if (!isDurable) throw NotificationStorageUnavailableException()
        accountId.requireCanonicalNotificationAccountId()
        operationId.requireNotificationOperationId()
        expectedAttemptCount.requireNotificationAttemptCount()
        terminalErrorCode?.requireNotificationTerminalErrorCode()
    }
}

internal sealed interface NotificationConfirmedOperationSettlement {
    data object Settled : NotificationConfirmedOperationSettlement

    data object Superseded : NotificationConfirmedOperationSettlement
}
