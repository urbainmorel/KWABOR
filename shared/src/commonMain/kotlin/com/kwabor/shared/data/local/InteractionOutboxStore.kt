package com.kwabor.shared.data.local

import com.kwabor.shared.data.core.isValidUuid

internal const val DEFAULT_MAX_INTERACTION_OUTBOX_OPERATIONS = 1_000
internal const val DEFAULT_INTERACTION_OUTBOX_READ_LIMIT = 50
internal const val MAX_INTERACTION_OUTBOX_READ_LIMIT = 100
internal const val MAX_INTERACTION_OUTBOX_VISIBLE_LISTING_IDS = 50

internal enum class InteractionOutboxKind(internal val storedValue: String) {
    Like(storedValue = "like"),
    Favorite(storedValue = "favorite"),
    ;

    internal companion object {
        fun fromStoredValue(value: String): InteractionOutboxKind? = entries.firstOrNull { kind ->
            kind.storedValue == value
        }
    }
}

internal enum class InteractionOutboxTerminalError(
    internal val storedValue: String,
    internal val isDisposable: Boolean,
) {
    Session(storedValue = "session", isDisposable = false),
    Manual(storedValue = "manual", isDisposable = false),
    Validation(storedValue = "validation", isDisposable = true),
    NotFound(storedValue = "not_found", isDisposable = true),
    Permission(storedValue = "permission", isDisposable = true),
}

internal data class InteractionOutboxOperation(
    val operationId: Long,
    val accountId: String,
    val listingId: String,
    val kind: InteractionOutboxKind,
    val desiredSelected: Boolean,
    val enqueuedAtEpochMilliseconds: Long,
    val attemptCount: Int,
    val nextAttemptAtEpochMilliseconds: Long,
    val terminalErrorCode: String?,
)

internal class InteractionOutboxCapacityExceededException(maxOperationCount: Int) :
    IllegalStateException("Interaction outbox capacity of $maxOperationCount operations was reached.")

internal class InteractionOutboxStore(
    private val dao: InteractionOutboxDao,
    private val reader: InteractionOutboxReader = dao,
    private val maxOperationCount: Int = DEFAULT_MAX_INTERACTION_OUTBOX_OPERATIONS,
) {
    init {
        require(maxOperationCount in 1..DEFAULT_MAX_INTERACTION_OUTBOX_OPERATIONS) {
            "Interaction outbox capacity must be between 1 and $DEFAULT_MAX_INTERACTION_OUTBOX_OPERATIONS."
        }
    }

    suspend fun enqueue(
        accountId: String,
        listingId: String,
        kind: InteractionOutboxKind,
        desiredSelected: Boolean,
        enqueuedAtEpochMilliseconds: Long,
    ): InteractionOutboxOperation {
        accountId.requireCanonicalOutboxUuid("accountId")
        listingId.requireCanonicalOutboxUuid("listingId")
        enqueuedAtEpochMilliseconds.requireOutboxTimestamp("enqueuedAtEpochMilliseconds")
        val operation = dao.enqueueCoalesced(
            operation = InteractionOutboxEntity(
                accountId = accountId,
                listingId = listingId,
                kind = kind.storedValue,
                desiredSelectedRaw = desiredSelected.toStoredInteger(),
                enqueuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
                attemptCount = 0,
                nextAttemptAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
                terminalErrorCode = null,
            ),
            maxOperationCount = maxOperationCount,
            garbageCollectedTerminalErrorCodes = DISPOSABLE_INTERACTION_TERMINAL_ERROR_CODES,
        )
        val validOperation = operation.toValidOperationOrNull()
        if (validOperation != null) return validOperation
        dao.deleteByOperationId(operation.operationId)
        error("Interaction outbox returned a logically invalid coalesced operation.")
    }

    suspend fun listForAccount(
        accountId: String,
        limit: Int = DEFAULT_INTERACTION_OUTBOX_READ_LIMIT,
    ): List<InteractionOutboxOperation> {
        accountId.requireCanonicalOutboxUuid("accountId")
        limit.requireOutboxReadLimit()
        return validateOrEvict(reader.findForAccount(accountId = accountId, limit = limit))
    }

    suspend fun listReadyForAccount(
        accountId: String,
        readyAtEpochMilliseconds: Long,
        limit: Int = DEFAULT_INTERACTION_OUTBOX_READ_LIMIT,
    ): List<InteractionOutboxOperation> {
        accountId.requireCanonicalOutboxUuid("accountId")
        readyAtEpochMilliseconds.requireOutboxTimestamp("readyAtEpochMilliseconds")
        limit.requireOutboxReadLimit()
        return validateOrEvict(
            reader.findReadyForAccount(
                accountId = accountId,
                readyAtEpochMilliseconds = readyAtEpochMilliseconds,
                limit = limit,
            ),
        )
    }

    suspend fun nextAttemptAtForAccount(accountId: String): Long? {
        accountId.requireCanonicalOutboxUuid("accountId")
        while (true) {
            val entity = reader.findNextScheduledForAccount(accountId) ?: return null
            val operation = entity.toValidOperationOrNull()
            if (operation != null) return operation.nextAttemptAtEpochMilliseconds
            dao.deleteByOperationId(entity.operationId)
        }
    }

    suspend fun listPausedForAccount(
        accountId: String,
        limit: Int = MAX_INTERACTION_OUTBOX_READ_LIMIT,
    ): List<InteractionOutboxOperation> {
        accountId.requireCanonicalOutboxUuid("accountId")
        limit.requireOutboxReadLimit()
        return validateOrEvict(
            reader.findPausedForAccount(
                accountId = accountId,
                terminalErrorCode = null,
                limit = limit,
            ),
        )
    }

    suspend fun listPausedForAccount(
        accountId: String,
        terminalErrorCode: String,
        limit: Int = MAX_INTERACTION_OUTBOX_READ_LIMIT,
    ): List<InteractionOutboxOperation> {
        accountId.requireCanonicalOutboxUuid("accountId")
        terminalErrorCode.requireTerminalErrorCode()
        limit.requireOutboxReadLimit()
        return validateOrEvict(
            reader.findPausedForAccount(
                accountId = accountId,
                terminalErrorCode = terminalErrorCode,
                limit = limit,
            ),
        )
    }

    suspend fun listForAccountAndListingIds(
        accountId: String,
        listingIds: List<String>,
    ): List<InteractionOutboxOperation> {
        accountId.requireCanonicalOutboxUuid("accountId")
        require(listingIds.size <= MAX_INTERACTION_OUTBOX_VISIBLE_LISTING_IDS) {
            "Interaction outbox visible listing ids cannot exceed $MAX_INTERACTION_OUTBOX_VISIBLE_LISTING_IDS."
        }
        listingIds.forEach { listingId -> listingId.requireCanonicalOutboxUuid("listingId") }
        if (listingIds.isEmpty()) return emptyList()
        return validateOrEvict(
            reader.findForAccountAndListingIds(
                accountId = accountId,
                listingIds = listingIds.distinct(),
            ),
        )
    }

    suspend fun deleteIfOperationMatches(
        operationId: Long,
        expectedAttemptCount: Int? = null,
        expectedTerminalErrorCode: String? = null,
    ): Boolean {
        operationId.requirePersistedOperationId()
        require(expectedAttemptCount != null || expectedTerminalErrorCode == null) {
            "Interaction outbox terminal delete code requires an expected attempt count."
        }
        expectedAttemptCount?.requirePersistedAttemptCount()
        expectedTerminalErrorCode?.requireTerminalErrorCode()
        return dao.deleteByOperationId(
            operationId = operationId,
            expectedAttemptCount = expectedAttemptCount?.toLong(),
            expectedTerminalErrorCode = expectedTerminalErrorCode,
        ) > 0
    }

    suspend fun recordRetry(
        operationId: Long,
        expectedAttemptCount: Int,
        nextAttemptAtEpochMilliseconds: Long,
    ): Boolean {
        operationId.requirePersistedOperationId()
        expectedAttemptCount.requireRetryableAttemptCount()
        nextAttemptAtEpochMilliseconds.requireOutboxTimestamp("nextAttemptAtEpochMilliseconds")
        return dao.recordRetry(
            operationId = operationId,
            expectedAttemptCount = expectedAttemptCount,
            nextAttemptAtEpochMilliseconds = nextAttemptAtEpochMilliseconds,
        ) > 0
    }

    suspend fun recordTerminalFailure(
        operationId: Long,
        expectedAttemptCount: Int,
        terminalErrorCode: String,
    ): Boolean {
        operationId.requirePersistedOperationId()
        expectedAttemptCount.requireRetryableAttemptCount()
        terminalErrorCode.requireTerminalErrorCode()
        return dao.recordTerminalFailure(
            operationId = operationId,
            expectedAttemptCount = expectedAttemptCount,
            terminalErrorCode = terminalErrorCode,
        ) > 0
    }

    suspend fun rearm(operationId: Long, expectedDesiredSelected: Boolean, rearmedAtEpochMilliseconds: Long): Boolean {
        operationId.requirePersistedOperationId()
        rearmedAtEpochMilliseconds.requireOutboxTimestamp("rearmedAtEpochMilliseconds")
        return dao.rearm(
            operationId = operationId,
            expectedDesiredSelected = expectedDesiredSelected.toStoredInteger(),
            rearmedAtEpochMilliseconds = rearmedAtEpochMilliseconds,
        ) > 0
    }

    private suspend fun validateOrEvict(entities: List<InteractionOutboxEntity>): List<InteractionOutboxOperation> {
        val operations = ArrayList<InteractionOutboxOperation>(entities.size)
        entities.forEach { entity ->
            val operation = entity.toValidOperationOrNull()
            if (operation == null) {
                dao.deleteByOperationId(entity.operationId)
            } else {
                operations += operation
            }
        }
        return operations.toList()
    }
}

private fun InteractionOutboxEntity.toValidOperationOrNull(): InteractionOutboxOperation? {
    val parsedKind = InteractionOutboxKind.fromStoredValue(kind) ?: return null
    val parsedDesiredSelected = desiredSelectedRaw.toStoredBooleanOrNull() ?: return null
    if (!accountId.isCanonicalOutboxUuid() || !listingId.isCanonicalOutboxUuid()) return null
    if (operationId <= 0 || attemptCount !in 0..Int.MAX_VALUE.toLong()) return null
    if (!enqueuedAtEpochMilliseconds.isValidOutboxTimestamp()) return null
    if (!nextAttemptAtEpochMilliseconds.isValidOutboxTimestamp()) return null
    if (terminalErrorCode != null && !terminalErrorCode.isValidTerminalErrorCode()) return null
    return InteractionOutboxOperation(
        operationId = operationId,
        accountId = accountId,
        listingId = listingId,
        kind = parsedKind,
        desiredSelected = parsedDesiredSelected,
        enqueuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
        attemptCount = attemptCount.toInt(),
        nextAttemptAtEpochMilliseconds = nextAttemptAtEpochMilliseconds,
        terminalErrorCode = terminalErrorCode,
    )
}

private fun String.requireCanonicalOutboxUuid(fieldName: String) {
    require(isCanonicalOutboxUuid()) { "Interaction outbox $fieldName must be a lowercase UUID." }
}

private fun String.isCanonicalOutboxUuid(): Boolean = isValidUuid() && this == lowercase()

private fun Long.requirePersistedOperationId() {
    require(this > 0) { "Interaction outbox operationId must be positive." }
}

private fun Int.requireRetryableAttemptCount() {
    require(this in 0 until Int.MAX_VALUE) { "Interaction outbox attempt count cannot be incremented." }
}

private fun Int.requirePersistedAttemptCount() {
    require(this >= 0) { "Interaction outbox attempt count must be non-negative." }
}

private fun Long.requireOutboxTimestamp(fieldName: String) {
    require(isValidOutboxTimestamp()) { "Interaction outbox $fieldName must not be negative." }
}

private fun Long.isValidOutboxTimestamp(): Boolean = this >= 0

private fun Int.requireOutboxReadLimit() {
    require(this in 1..MAX_INTERACTION_OUTBOX_READ_LIMIT) {
        "Interaction outbox read limit must be between 1 and $MAX_INTERACTION_OUTBOX_READ_LIMIT."
    }
}

private fun String.requireTerminalErrorCode() {
    require(isValidTerminalErrorCode()) { "Interaction outbox terminal error code is invalid." }
}

private fun String.isValidTerminalErrorCode(): Boolean =
    length in 1..MAX_TERMINAL_ERROR_CODE_LENGTH && TERMINAL_ERROR_CODE_PATTERN.matches(this)

private const val MAX_TERMINAL_ERROR_CODE_LENGTH = 64
private val TERMINAL_ERROR_CODE_PATTERN = Regex("^[a-z][a-z0-9_]*$")
private val DISPOSABLE_INTERACTION_TERMINAL_ERROR_CODES = InteractionOutboxTerminalError.entries
    .filter(InteractionOutboxTerminalError::isDisposable)
    .map(InteractionOutboxTerminalError::storedValue)

private fun Boolean.toStoredInteger(): Long = if (this) 1L else 0L

private fun Long.toStoredBooleanOrNull(): Boolean? = when (this) {
    0L -> false
    1L -> true
    else -> null
}
