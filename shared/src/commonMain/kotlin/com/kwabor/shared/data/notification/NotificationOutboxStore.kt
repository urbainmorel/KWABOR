package com.kwabor.shared.data.notification

import com.kwabor.shared.data.local.NotificationOutboxDao
import com.kwabor.shared.data.local.NotificationSyncOperationEntity
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import kotlinx.coroutines.sync.withLock

internal class NotificationOutboxStore(
    private val daoFactory: () -> NotificationOutboxDao,
    internal val isDurable: Boolean,
    private val lock: NotificationStoreLock,
    private val maxOperationCount: Int = DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS,
) {
    private val dao: NotificationOutboxDao by lazy(daoFactory)

    internal constructor(
        dao: NotificationOutboxDao,
        lock: NotificationStoreLock = NotificationStoreLock(),
        maxOperationCount: Int = DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS,
    ) : this(
        daoFactory = { dao },
        isDurable = true,
        lock = lock,
        maxOperationCount = maxOperationCount,
    )

    init {
        require(maxOperationCount in 1..DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS) {
            "Notification outbox operation bound is invalid."
        }
    }

    suspend fun enqueueAdvanceSeenThrough(
        accountId: String,
        throughSequence: Long,
        enqueuedAtEpochMilliseconds: Long,
    ): NotificationSyncOperation = enqueueOperation(
        accountId,
        NotificationOperationPayload(
            kind = NotificationSyncOperationKind.AdvanceSeenThrough,
            throughSequence = throughSequence,
        ),
        enqueuedAtEpochMilliseconds,
    )

    suspend fun enqueueMarkRead(
        accountId: String,
        notificationId: String,
        enqueuedAtEpochMilliseconds: Long,
    ): NotificationSyncOperation = enqueueOperation(
        accountId,
        NotificationOperationPayload(
            kind = NotificationSyncOperationKind.MarkRead,
            notificationId = notificationId,
        ),
        enqueuedAtEpochMilliseconds,
    )

    suspend fun enqueueMarkAllReadThrough(
        accountId: String,
        throughSequence: Long,
        enqueuedAtEpochMilliseconds: Long,
    ): NotificationSyncOperation = enqueueOperation(
        accountId,
        NotificationOperationPayload(
            kind = NotificationSyncOperationKind.MarkAllReadThrough,
            throughSequence = throughSequence,
        ),
        enqueuedAtEpochMilliseconds,
    )

    suspend fun enqueueHide(
        accountId: String,
        notificationId: String,
        enqueuedAtEpochMilliseconds: Long,
    ): NotificationSyncOperation = enqueueOperation(
        accountId,
        NotificationOperationPayload(
            kind = NotificationSyncOperationKind.Hide,
            notificationId = notificationId,
        ),
        enqueuedAtEpochMilliseconds,
    )

    suspend fun enqueueSetFamilyEnabled(
        accountId: String,
        family: NotificationPreferenceFamily,
        enabled: Boolean,
        enqueuedAtEpochMilliseconds: Long,
    ): NotificationSyncOperation = enqueueOperation(
        accountId,
        NotificationOperationPayload(
            kind = NotificationSyncOperationKind.SetFamilyEnabled,
            family = family,
            desiredEnabled = enabled,
        ),
        enqueuedAtEpochMilliseconds,
    )

    suspend fun listOperations(
        accountId: String,
        limit: Int = DEFAULT_NOTIFICATION_SYNC_READ_LIMIT,
    ): List<NotificationSyncOperation> = lock.mutex.withLock {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        limit.requireNotificationOperationReadLimit()
        validateOrEvictOperations(accountId, dao.findOperations(accountId, limit))
    }

    suspend fun listReadyOperations(
        accountId: String,
        readyAtEpochMilliseconds: Long,
        limit: Int = DEFAULT_NOTIFICATION_SYNC_READ_LIMIT,
    ): List<NotificationSyncOperation> = lock.mutex.withLock {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        readyAtEpochMilliseconds.requireNotificationStoreTimestamp("readyAtEpochMilliseconds")
        limit.requireNotificationOperationReadLimit()
        validateOrEvictOperations(accountId, dao.findReadyOperations(accountId, readyAtEpochMilliseconds, limit))
    }

    suspend fun listPausedOperations(
        accountId: String,
        terminalErrorCode: String? = null,
        limit: Int = DEFAULT_NOTIFICATION_SYNC_READ_LIMIT,
    ): List<NotificationSyncOperation> = lock.mutex.withLock {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        terminalErrorCode?.requireNotificationTerminalErrorCode()
        limit.requireNotificationOperationReadLimit()
        validateOrEvictOperations(accountId, dao.findPausedOperations(accountId, terminalErrorCode, limit))
    }

    suspend fun nextAttemptAt(accountId: String): Long? = lock.mutex.withLock {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        var entity = dao.findNextScheduledOperation(accountId)
        while (entity != null) {
            val operation = entity.toNotificationOperationOrNull()
            if (operation != null) return@withLock operation.nextAttemptAtEpochMilliseconds
            dao.deleteOperation(accountId, entity.operationId)
            entity = dao.findNextScheduledOperation(accountId)
        }
        null
    }

    private suspend fun enqueueOperation(
        accountId: String,
        payload: NotificationOperationPayload,
        enqueuedAtEpochMilliseconds: Long,
    ): NotificationSyncOperation = lock.mutex.withLock {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        payload.notificationId?.requireCanonicalNotificationId()
        payload.throughSequence?.requirePositiveNotificationSequence("throughSequence")
        enqueuedAtEpochMilliseconds.requireNotificationStoreTimestamp("enqueuedAtEpochMilliseconds")
        val entity = payload.toEntity(accountId, enqueuedAtEpochMilliseconds)
        require(entity.copy(operationId = 1).toNotificationOperationOrNull() != null) {
            "Notification sync operation payload does not match its kind."
        }
        val stored = dao.enqueueCoalesced(entity, maxOperationCount)
        stored.toNotificationOperationOrNull() ?: run {
            dao.deleteOperation(accountId, stored.operationId)
            error("Notification outbox returned a corrupt operation.")
        }
    }

    private suspend fun validateOrEvictOperations(
        accountId: String,
        entities: List<NotificationSyncOperationEntity>,
    ): List<NotificationSyncOperation> = entities.mapNotNull { entity ->
        entity.toNotificationOperationOrNull() ?: run {
            dao.deleteOperation(accountId, entity.operationId)
            null
        }
    }

    private fun requireDurableStorage() {
        if (!isDurable) throw NotificationStorageUnavailableException()
    }
}

private data class NotificationOperationPayload(
    val kind: NotificationSyncOperationKind,
    val notificationId: String? = null,
    val throughSequence: Long? = null,
    val family: NotificationPreferenceFamily? = null,
    val desiredEnabled: Boolean? = null,
)

private fun NotificationOperationPayload.toEntity(
    accountId: String,
    enqueuedAtEpochMilliseconds: Long,
): NotificationSyncOperationEntity = NotificationSyncOperationEntity(
    accountId = accountId,
    logicalKey = kind.logicalKey(notificationId, family),
    kind = kind.storedValue,
    notificationId = notificationId,
    throughSequence = throughSequence,
    family = family?.toWireValue(),
    desiredEnabledRaw = desiredEnabled?.toStoredBoolean(),
    enqueuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
    attemptCount = 0,
    nextAttemptAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
    terminalErrorCode = null,
)
