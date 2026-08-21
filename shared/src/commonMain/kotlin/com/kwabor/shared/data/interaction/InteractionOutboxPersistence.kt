package com.kwabor.shared.data.interaction

import com.kwabor.shared.data.local.InteractionOutboxKind
import com.kwabor.shared.data.local.InteractionOutboxOperation
import com.kwabor.shared.data.local.InteractionOutboxStore

internal abstract class InteractionOutboxPersistence {
    abstract val isDurable: Boolean

    abstract suspend fun enqueue(
        accountId: String,
        listingId: String,
        kind: InteractionOutboxKind,
        desiredSelected: Boolean,
        enqueuedAtEpochMilliseconds: Long,
    ): InteractionOutboxOperation

    abstract suspend fun listForAccount(accountId: String, limit: Int): List<InteractionOutboxOperation>

    abstract suspend fun listForAccountAndListingIds(
        accountId: String,
        listingIds: List<String>,
    ): List<InteractionOutboxOperation>

    abstract suspend fun listReadyForAccount(
        accountId: String,
        readyAtEpochMilliseconds: Long,
        limit: Int,
    ): List<InteractionOutboxOperation>

    abstract suspend fun listPausedForAccount(
        accountId: String,
        terminalErrorCode: String,
        limit: Int,
    ): List<InteractionOutboxOperation>

    abstract suspend fun deleteIfOperationMatches(
        operationId: Long,
        expectedAttemptCount: Int? = null,
        expectedTerminalErrorCode: String? = null,
    ): Boolean

    abstract suspend fun recordRetry(
        operationId: Long,
        expectedAttemptCount: Int,
        nextAttemptAtEpochMilliseconds: Long,
    ): Boolean

    abstract suspend fun recordTerminalFailure(
        operationId: Long,
        expectedAttemptCount: Int,
        terminalErrorCode: String,
    ): Boolean

    abstract suspend fun rearm(
        operationId: Long,
        expectedDesiredSelected: Boolean,
        rearmedAtEpochMilliseconds: Long,
    ): Boolean

    abstract suspend fun nextAttemptAtForAccount(accountId: String): Long?
}

internal class RoomInteractionOutboxPersistence(
    storeFactory: () -> InteractionOutboxStore,
) : InteractionOutboxPersistence() {
    private val store: InteractionOutboxStore by lazy(storeFactory)
    override val isDurable: Boolean = true

    override suspend fun enqueue(
        accountId: String,
        listingId: String,
        kind: InteractionOutboxKind,
        desiredSelected: Boolean,
        enqueuedAtEpochMilliseconds: Long,
    ): InteractionOutboxOperation = store.enqueue(
        accountId = accountId,
        listingId = listingId,
        kind = kind,
        desiredSelected = desiredSelected,
        enqueuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
    )

    override suspend fun listForAccount(accountId: String, limit: Int): List<InteractionOutboxOperation> =
        store.listForAccount(accountId = accountId, limit = limit)

    override suspend fun listForAccountAndListingIds(
        accountId: String,
        listingIds: List<String>,
    ): List<InteractionOutboxOperation> = store.listForAccountAndListingIds(
        accountId = accountId,
        listingIds = listingIds,
    )

    override suspend fun listReadyForAccount(
        accountId: String,
        readyAtEpochMilliseconds: Long,
        limit: Int,
    ): List<InteractionOutboxOperation> = store.listReadyForAccount(
        accountId = accountId,
        readyAtEpochMilliseconds = readyAtEpochMilliseconds,
        limit = limit,
    )

    override suspend fun listPausedForAccount(
        accountId: String,
        terminalErrorCode: String,
        limit: Int,
    ): List<InteractionOutboxOperation> = store.listPausedForAccount(
        accountId = accountId,
        terminalErrorCode = terminalErrorCode,
        limit = limit,
    )

    override suspend fun deleteIfOperationMatches(
        operationId: Long,
        expectedAttemptCount: Int?,
        expectedTerminalErrorCode: String?,
    ): Boolean = store.deleteIfOperationMatches(
        operationId = operationId,
        expectedAttemptCount = expectedAttemptCount,
        expectedTerminalErrorCode = expectedTerminalErrorCode,
    )

    override suspend fun recordRetry(
        operationId: Long,
        expectedAttemptCount: Int,
        nextAttemptAtEpochMilliseconds: Long,
    ): Boolean = store.recordRetry(
        operationId = operationId,
        expectedAttemptCount = expectedAttemptCount,
        nextAttemptAtEpochMilliseconds = nextAttemptAtEpochMilliseconds,
    )

    override suspend fun recordTerminalFailure(
        operationId: Long,
        expectedAttemptCount: Int,
        terminalErrorCode: String,
    ): Boolean = store.recordTerminalFailure(
        operationId = operationId,
        expectedAttemptCount = expectedAttemptCount,
        terminalErrorCode = terminalErrorCode,
    )

    override suspend fun rearm(
        operationId: Long,
        expectedDesiredSelected: Boolean,
        rearmedAtEpochMilliseconds: Long,
    ): Boolean = store.rearm(
        operationId = operationId,
        expectedDesiredSelected = expectedDesiredSelected,
        rearmedAtEpochMilliseconds = rearmedAtEpochMilliseconds,
    )

    override suspend fun nextAttemptAtForAccount(accountId: String): Long? = store.nextAttemptAtForAccount(accountId)
}

internal class UnavailableInteractionOutboxPersistence : InteractionOutboxPersistence() {
    override val isDurable: Boolean = false

    override suspend fun enqueue(
        accountId: String,
        listingId: String,
        kind: InteractionOutboxKind,
        desiredSelected: Boolean,
        enqueuedAtEpochMilliseconds: Long,
    ): InteractionOutboxOperation = unavailable()

    override suspend fun listForAccount(accountId: String, limit: Int): List<InteractionOutboxOperation> = unavailable()

    override suspend fun listForAccountAndListingIds(
        accountId: String,
        listingIds: List<String>,
    ): List<InteractionOutboxOperation> = unavailable()

    override suspend fun listReadyForAccount(
        accountId: String,
        readyAtEpochMilliseconds: Long,
        limit: Int,
    ): List<InteractionOutboxOperation> = unavailable()

    override suspend fun listPausedForAccount(
        accountId: String,
        terminalErrorCode: String,
        limit: Int,
    ): List<InteractionOutboxOperation> = unavailable()

    override suspend fun deleteIfOperationMatches(
        operationId: Long,
        expectedAttemptCount: Int?,
        expectedTerminalErrorCode: String?,
    ): Boolean = unavailable()

    override suspend fun recordRetry(
        operationId: Long,
        expectedAttemptCount: Int,
        nextAttemptAtEpochMilliseconds: Long,
    ): Boolean = unavailable()

    override suspend fun recordTerminalFailure(
        operationId: Long,
        expectedAttemptCount: Int,
        terminalErrorCode: String,
    ): Boolean = unavailable()

    override suspend fun rearm(
        operationId: Long,
        expectedDesiredSelected: Boolean,
        rearmedAtEpochMilliseconds: Long,
    ): Boolean = unavailable()

    override suspend fun nextAttemptAtForAccount(accountId: String): Long? = unavailable()

    private fun unavailable(): Nothing = error("Durable interaction storage is unavailable.")
}
