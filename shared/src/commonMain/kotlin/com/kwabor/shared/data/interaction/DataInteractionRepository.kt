package com.kwabor.shared.data.interaction

import androidx.sqlite.SQLiteException
import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.data.local.InteractionOutboxCapacityExceededException
import com.kwabor.shared.data.local.InteractionOutboxOperation
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.AccountScopedFavoriteMutationRepository
import com.kwabor.shared.domain.interaction.AccountScopedListingLikeRepository
import com.kwabor.shared.domain.interaction.ActiveInteractionScopeProvider
import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionConfirmation
import com.kwabor.shared.domain.interaction.InteractionDrainOutcome
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionOperationOutcome
import com.kwabor.shared.domain.interaction.InteractionRejectionReason
import com.kwabor.shared.domain.interaction.InteractionRepository
import com.kwabor.shared.domain.interaction.InteractionSubmitOutcome
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.domain.interaction.PendingInteractionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val INTERACTION_DRAIN_LIMIT = 100
private const val INTERACTION_LOAD_LIMIT = 100
private const val MAX_INTERACTION_HYDRATION_LISTING_IDS = 50

class DataInteractionRepository internal constructor(
    private val outbox: InteractionOutboxPersistence,
    private val listingLikeRepository: AccountScopedListingLikeRepository,
    private val favoriteMutationRepository: AccountScopedFavoriteMutationRepository,
    private val activeScopeProvider: ActiveInteractionScopeProvider,
    private val clockProvider: ClockProvider,
    private val retryDelayPolicy: InteractionRetryDelayPolicy,
) : InteractionRepository {
    private val drainMutex = Mutex()

    override suspend fun submit(command: InteractionCommand): DomainResult<InteractionSubmitOutcome> =
        runInteractionDataCall {
            outbox.requireDurableStorage()
            val canonicalCommand = command.toCanonicalCommand()
            val now = clockProvider.requireInteractionTime()
            val stored = outbox.enqueue(
                accountId = canonicalCommand.scope.accountId,
                listingId = canonicalCommand.listingId,
                kind = canonicalCommand.kind.toOutboxKind(),
                desiredSelected = canonicalCommand.desiredSelected,
                enqueuedAtEpochMilliseconds = now,
            )
            if (stored.terminalErrorCode == null) {
                InteractionSubmitOutcome.Queued(canonicalCommand, stored.toDomain())
            } else if (
                outbox.rearm(
                    operationId = stored.operationId,
                    expectedDesiredSelected = stored.desiredSelected,
                    rearmedAtEpochMilliseconds = now,
                )
            ) {
                InteractionSubmitOutcome.Queued(
                    command = canonicalCommand,
                    pending = stored.copy(
                        enqueuedAtEpochMilliseconds = now,
                        attemptCount = 0,
                        nextAttemptAtEpochMilliseconds = now,
                        terminalErrorCode = null,
                    ).toDomain(),
                )
            } else {
                InteractionSubmitOutcome.Superseded(
                    command = canonicalCommand,
                    operationId = stored.operationId,
                )
            }
        }

    override suspend fun loadPending(
        accountId: String,
        listingIds: List<String>,
    ): DomainResult<List<PendingInteraction>> = runInteractionDataCall {
        outbox.requireDurableStorage()
        val canonicalAccountId = accountId.toCanonicalInteractionUuid("account_id")
        val canonicalListingIds = listingIds
            .map { listingId -> listingId.toCanonicalInteractionUuid("listing_id") }
            .distinct()
        if (canonicalListingIds.size > MAX_INTERACTION_HYDRATION_LISTING_IDS) {
            throw InteractionDataException.Validation("error.interaction.too_many_listing_ids")
        }
        val operations = if (canonicalListingIds.isEmpty()) {
            outbox.listForAccount(canonicalAccountId, INTERACTION_LOAD_LIMIT)
        } else {
            outbox.listForAccountAndListingIds(canonicalAccountId, canonicalListingIds)
        }
        operations.map(InteractionOutboxOperation::toDomain)
    }

    override suspend fun drainDue(scope: InteractionAccountScope): DomainResult<InteractionDrainOutcome> =
        runInteractionDataCall {
            outbox.requireDurableStorage()
            val canonicalScope = scope.toCanonicalScope()
            drainMutex.withLock {
                val readyAt = clockProvider.requireInteractionTime()
                val ready = outbox.listReadyForAccount(
                    accountId = canonicalScope.accountId,
                    readyAtEpochMilliseconds = readyAt,
                    limit = INTERACTION_DRAIN_LIMIT,
                )
                val outcomes = mutableListOf<InteractionOperationOutcome>()
                for (operation in ready) {
                    val outcome = processOperation(operation, canonicalScope)
                    outcomes += outcome
                    if (outcome.stopsCurrentDrain()) break
                }
                InteractionDrainOutcome(scope = canonicalScope, operations = outcomes.toList())
            }
        }

    override suspend fun nextAttemptAt(accountId: String): DomainResult<Long?> = runInteractionDataCall {
        outbox.requireDurableStorage()
        val canonicalAccountId = accountId.toCanonicalInteractionUuid("account_id")
        outbox.nextAttemptAtForAccount(canonicalAccountId)
    }

    override suspend fun retryAccount(
        scope: InteractionAccountScope,
        includeManualFailures: Boolean,
    ): DomainResult<Int> = runInteractionDataCall {
        outbox.requireDurableStorage()
        val canonicalScope = scope.toCanonicalScope()
        if (activeScopeProvider.currentScope() != canonicalScope) {
            throw InteractionDataException.AuthenticationRequired
        }
        val rearmedAt = clockProvider.requireInteractionTime()
        var rearmedCount = rearmPaused(
            accountId = canonicalScope.accountId,
            terminalErrorCode = INTERACTION_TERMINAL_SESSION,
            rearmedAtEpochMilliseconds = rearmedAt,
        )
        if (includeManualFailures) {
            rearmedCount += rearmPaused(
                accountId = canonicalScope.accountId,
                terminalErrorCode = INTERACTION_TERMINAL_MANUAL,
                rearmedAtEpochMilliseconds = rearmedAt,
            )
        }
        rearmedCount
    }

    private suspend fun processOperation(
        operation: InteractionOutboxOperation,
        scope: InteractionAccountScope,
    ): InteractionOperationOutcome {
        val command = operation.toCommand(scope)
        if (!operation.isStillCurrent()) {
            return InteractionOperationOutcome.Superseded(command, operation.operationId)
        }
        return withContext(NonCancellable) {
            if (activeScopeProvider.currentScope() != scope) {
                return@withContext settleTerminalRetry(
                    operation = operation,
                    command = command,
                    terminalErrorCode = INTERACTION_TERMINAL_SESSION,
                    status = PendingInteractionStatus.SuspendedForSession,
                )
            }
            when (
                val result = operation.performRemote(
                    scope = scope,
                    listingLikeRepository = listingLikeRepository,
                    favoriteMutationRepository = favoriteMutationRepository,
                )
            ) {
                is DomainResult.Success -> settleSuccess(operation, command, result.value)
                is DomainResult.Failure -> settleFailure(operation, command, result.error)
            }
        }
    }

    private suspend fun settleSuccess(
        operation: InteractionOutboxOperation,
        command: InteractionCommand,
        confirmation: InteractionConfirmation,
    ): InteractionOperationOutcome = if (outbox.deleteIfOperationMatches(operation.operationId)) {
        InteractionOperationOutcome.Confirmed(command, confirmation)
    } else {
        InteractionOperationOutcome.Superseded(command, operation.operationId)
    }

    private suspend fun settleFailure(
        operation: InteractionOutboxOperation,
        command: InteractionCommand,
        error: DomainError,
    ): InteractionOperationOutcome = when (error) {
        is DomainError.NetworkUnavailable -> settleNetworkRetry(operation, command)
        is DomainError.AuthenticationRequired -> settleTerminalRetry(
            operation = operation,
            command = command,
            terminalErrorCode = INTERACTION_TERMINAL_SESSION,
            status = PendingInteractionStatus.SuspendedForSession,
        )
        is DomainError.Unexpected,
        is DomainError.LocalStorageUnavailable,
        -> settleTerminalRetry(
            operation = operation,
            command = command,
            terminalErrorCode = INTERACTION_TERMINAL_MANUAL,
            status = PendingInteractionStatus.SuspendedForManualRetry,
        )
        is DomainError.Validation -> settleRejected(
            operation,
            command,
            InteractionRejectionReason.Validation,
        )
        is DomainError.NotFound -> settleRejected(
            operation,
            command,
            InteractionRejectionReason.NotFound,
        )
        is DomainError.PermissionDenied -> settleRejected(
            operation,
            command,
            InteractionRejectionReason.PermissionDenied,
        )
    }

    private suspend fun settleNetworkRetry(
        operation: InteractionOutboxOperation,
        command: InteractionCommand,
    ): InteractionOperationOutcome {
        val nextAttemptCount = operation.attemptCount.requireIncrementableAttemptCount()
        val delay = retryDelayPolicy.delayMilliseconds(nextAttemptCount)
        val nextAttemptAt = clockProvider.requireInteractionTime().saturatingAdd(delay)
        val updated = outbox.recordRetry(
            operationId = operation.operationId,
            expectedAttemptCount = operation.attemptCount,
            nextAttemptAtEpochMilliseconds = nextAttemptAt,
        )
        return if (updated) {
            InteractionOperationOutcome.Retrying(
                command = command,
                pending = operation.copy(
                    attemptCount = nextAttemptCount,
                    nextAttemptAtEpochMilliseconds = nextAttemptAt,
                ).toDomain(),
            )
        } else {
            InteractionOperationOutcome.Superseded(command, operation.operationId)
        }
    }

    private suspend fun settleTerminalRetry(
        operation: InteractionOutboxOperation,
        command: InteractionCommand,
        terminalErrorCode: String,
        status: PendingInteractionStatus,
    ): InteractionOperationOutcome {
        val nextAttemptCount = operation.attemptCount.requireIncrementableAttemptCount()
        val updated = outbox.recordTerminalFailure(
            operationId = operation.operationId,
            expectedAttemptCount = operation.attemptCount,
            terminalErrorCode = terminalErrorCode,
        )
        return if (updated) {
            InteractionOperationOutcome.Retrying(
                command = command,
                pending = operation.copy(
                    attemptCount = nextAttemptCount,
                    terminalErrorCode = terminalErrorCode,
                ).toDomain().copy(status = status),
            )
        } else {
            InteractionOperationOutcome.Superseded(command, operation.operationId)
        }
    }

    private suspend fun settleRejected(
        operation: InteractionOutboxOperation,
        command: InteractionCommand,
        reason: InteractionRejectionReason,
    ): InteractionOperationOutcome {
        return if (
            outbox.deleteIfOperationMatches(
                operationId = operation.operationId,
                expectedAttemptCount = operation.attemptCount,
            )
        ) {
            InteractionOperationOutcome.Rejected(
                command = command,
                operationId = operation.operationId,
                reason = reason,
            )
        } else {
            InteractionOperationOutcome.Superseded(command, operation.operationId)
        }
    }

    private suspend fun InteractionOutboxOperation.isStillCurrent(): Boolean =
        outbox.listForAccountAndListingIds(accountId, listOf(listingId))
            .any { current -> current.operationId == operationId && current.kind == kind }

    private suspend fun rearmPaused(
        accountId: String,
        terminalErrorCode: String,
        rearmedAtEpochMilliseconds: Long,
    ): Int {
        var rearmedCount = 0
        while (true) {
            val paused = outbox.listPausedForAccount(
                accountId = accountId,
                terminalErrorCode = terminalErrorCode,
                limit = INTERACTION_DRAIN_LIMIT,
            )
            if (paused.isEmpty()) return rearmedCount
            var batchRearmedCount = 0
            paused.forEach { operation ->
                if (
                    outbox.rearm(
                        operationId = operation.operationId,
                        expectedDesiredSelected = operation.desiredSelected,
                        rearmedAtEpochMilliseconds = rearmedAtEpochMilliseconds,
                    )
                ) {
                    rearmedCount += 1
                    batchRearmedCount += 1
                }
            }
            if (paused.size < INTERACTION_DRAIN_LIMIT) return rearmedCount
            if (batchRearmedCount == 0) return rearmedCount
        }
    }
}

private suspend fun InteractionOutboxOperation.performRemote(
    scope: InteractionAccountScope,
    listingLikeRepository: AccountScopedListingLikeRepository,
    favoriteMutationRepository: AccountScopedFavoriteMutationRepository,
): DomainResult<InteractionConfirmation> = when (kind.toDomain()) {
    InteractionKind.Like -> performLike(scope, listingLikeRepository)
    InteractionKind.Favorite -> performFavorite(scope, favoriteMutationRepository)
}

private suspend fun InteractionOutboxOperation.performLike(
    scope: InteractionAccountScope,
    repository: AccountScopedListingLikeRepository,
): DomainResult<InteractionConfirmation> = when (
    val result = repository.setListingLike(
        expectedAccountId = accountId,
        listingId = listingId,
        liked = desiredSelected,
    )
) {
    is DomainResult.Success -> DomainResult.Success(
        InteractionConfirmation.Like(
            operationId = operationId,
            scope = scope,
            listingId = result.value.listingId,
            liked = result.value.liked,
            likesCount = result.value.likesCount,
            mutatedAtEpochMilliseconds = result.value.mutatedAtEpochMilliseconds,
        ),
    )
    is DomainResult.Failure -> result
}

private suspend fun InteractionOutboxOperation.performFavorite(
    scope: InteractionAccountScope,
    repository: AccountScopedFavoriteMutationRepository,
): DomainResult<InteractionConfirmation> = when (
    val result = repository.setFavorite(
        expectedAccountId = accountId,
        listingId = listingId,
        favorited = desiredSelected,
    )
) {
    is DomainResult.Success -> DomainResult.Success(
        InteractionConfirmation.Favorite(
            operationId = operationId,
            scope = scope,
            listingId = result.value.listingId,
            favorited = result.value.favorited,
            favoritedAtEpochMilliseconds = result.value.favoritedAtEpochMilliseconds,
            clientMutationSequence = result.value.clientMutationSequence,
        ),
    )
    is DomainResult.Failure -> result
}

private fun InteractionOutboxOperation.toCommand(scope: InteractionAccountScope): InteractionCommand =
    InteractionCommand(
        scope = scope,
        listingId = listingId,
        kind = kind.toDomain(),
        desiredSelected = desiredSelected,
    )

private fun InteractionOutboxPersistence.requireDurableStorage() {
    if (!isDurable) throw InteractionDataException.LocalStorageUnavailable
}

private fun com.kwabor.shared.data.local.InteractionOutboxKind.toDomain(): InteractionKind = when (this) {
    com.kwabor.shared.data.local.InteractionOutboxKind.Like -> InteractionKind.Like
    com.kwabor.shared.data.local.InteractionOutboxKind.Favorite -> InteractionKind.Favorite
}

private fun InteractionCommand.toCanonicalCommand(): InteractionCommand = copy(
    scope = scope.toCanonicalScope(),
    listingId = listingId.toCanonicalInteractionUuid("listing_id"),
)

private fun InteractionAccountScope.toCanonicalScope(): InteractionAccountScope = copy(
    accountId = accountId.toCanonicalInteractionUuid("account_id"),
)

private fun String.toCanonicalInteractionUuid(fieldName: String): String {
    val canonical = trim().lowercase()
    if (!canonical.isValidUuid()) {
        throw InteractionDataException.Validation("error.interaction.${fieldName}_invalid")
    }
    return canonical
}

private fun ClockProvider.requireInteractionTime(): Long = nowEpochMilliseconds().also { now ->
    if (now < 0L) throw InteractionDataException.Unexpected
}

private fun Int.requireIncrementableAttemptCount(): Int {
    if (this == Int.MAX_VALUE) throw InteractionDataException.Unexpected
    return this + 1
}

private fun Long.saturatingAdd(increment: Long): Long =
    if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

private fun InteractionOperationOutcome.stopsCurrentDrain(): Boolean = when (this) {
    is InteractionOperationOutcome.Confirmed,
    is InteractionOperationOutcome.Rejected,
    is InteractionOperationOutcome.Superseded,
    -> false
    is InteractionOperationOutcome.Retrying -> when (pending.status) {
        is PendingInteractionStatus.Scheduled,
        PendingInteractionStatus.SuspendedForSession,
        -> true
        PendingInteractionStatus.SuspendedForManualRetry,
        is PendingInteractionStatus.Rejected,
        -> false
    }
}

private suspend inline fun <T> runInteractionDataCall(crossinline block: suspend () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: CancellationException) {
    throw exception
} catch (exception: InteractionDataException) {
    DomainResult.Failure(exception.domainError)
} catch (_: InteractionOutboxCapacityExceededException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable("error.interaction.outbox_full"))
} catch (_: SQLiteException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable())
} catch (_: IllegalArgumentException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable())
} catch (_: IllegalStateException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable())
}

private sealed class InteractionDataException(
    val domainError: DomainError,
) : RuntimeException(domainError.messageKey) {
    class Validation(messageKey: String) : InteractionDataException(DomainError.Validation(messageKey))

    data object AuthenticationRequired : InteractionDataException(DomainError.AuthenticationRequired())

    data object LocalStorageUnavailable : InteractionDataException(DomainError.LocalStorageUnavailable())

    data object Unexpected : InteractionDataException(DomainError.Unexpected())
}
