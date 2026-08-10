package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.InteractionAccountScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class InteractionLifecycleGate {
    private val mutex = Mutex()
    private val blockedAccountIds = mutableSetOf<String>()
    private val activeOperationCounts = mutableMapOf<String, Int>()
    private val operationIdleSignals = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val activePurges = mutableMapOf<String, CompletableDeferred<DomainResult<Int>>>()
    private val accountGenerations = mutableMapOf<String, InteractionAccountLifecycleGeneration>()

    suspend fun beginOperation(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
    ): InteractionAccountOperationLease? = mutex.withLock {
        if (!isAvailableLocked(expectedScope, currentScope)) return@withLock null
        val accountId = expectedScope.accountId
        val activeCount = activeOperationCounts[accountId] ?: 0
        check(activeCount < Int.MAX_VALUE) { "Interaction account operation count overflow." }
        if (activeCount == 0) operationIdleSignals[accountId] = CompletableDeferred()
        activeOperationCounts[accountId] = activeCount + 1
        InteractionAccountOperationLease(accountId)
    }

    suspend fun endOperation(lease: InteractionAccountOperationLease) {
        withContext(NonCancellable) {
            mutex.withLock {
                val activeCount = checkNotNull(activeOperationCounts[lease.accountId]) {
                    "Interaction account operation lease was already released."
                }
                if (activeCount == 1) {
                    activeOperationCounts.remove(lease.accountId)
                    operationIdleSignals.remove(lease.accountId)?.complete(Unit)
                } else {
                    activeOperationCounts[lease.accountId] = activeCount - 1
                }
            }
        }
    }

    suspend fun isAvailable(expectedScope: InteractionAccountScope, currentScope: InteractionAccountScope?): Boolean =
        mutex.withLock { isAvailableLocked(expectedScope, currentScope) }

    suspend fun availableGeneration(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
    ): InteractionAccountLifecycleGeneration? = mutex.withLock {
        if (!isAvailableLocked(expectedScope, currentScope)) return@withLock null
        accountGenerations.getOrPut(expectedScope.accountId) {
            InteractionAccountLifecycleGeneration(revision = 0L)
        }
    }

    suspend fun isAvailableAtGeneration(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
        generation: InteractionAccountLifecycleGeneration,
    ): Boolean = mutex.withLock {
        isAvailableLocked(expectedScope, currentScope) &&
            accountGenerations[expectedScope.accountId] === generation
    }

    suspend fun runIfAvailable(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
        action: () -> Unit,
    ): Boolean {
        mutex.lock()
        return try {
            if (!isAvailableLocked(expectedScope, currentScope)) return false
            action()
            true
        } finally {
            mutex.unlock()
        }
    }

    suspend fun registerPurge(accountId: String): InteractionAccountDeletionPurgeRegistration = mutex.withLock {
        activePurges[accountId]?.let { completion ->
            return@withLock InteractionAccountDeletionPurgeRegistration.Existing(completion)
        }
        if (accountId in blockedAccountIds) {
            return@withLock InteractionAccountDeletionPurgeRegistration.AlreadyPurged
        }
        blockedAccountIds += accountId
        accountGenerations[accountId] = accountGenerations[accountId].nextGeneration()
        val completion = CompletableDeferred<DomainResult<Int>>()
        activePurges[accountId] = completion
        InteractionAccountDeletionPurgeRegistration.Owner(
            completion = completion,
            settlement = CompletableDeferred(),
            idle = operationIdleSignals[accountId],
        )
    }

    suspend fun finishPurge(
        accountId: String,
        registration: InteractionAccountDeletionPurgeRegistration.Owner,
        result: DomainResult<Int>?,
        failure: Throwable? = null,
    ) {
        withContext(NonCancellable) {
            mutex.withLock {
                activePurges.remove(accountId)
                if (result !is DomainResult.Success) blockedAccountIds.remove(accountId)
                registration.settlement.complete(result)
                when {
                    result != null -> registration.completion.complete(result)
                    failure != null -> registration.completion.completeExceptionally(failure)
                    else -> registration.completion.cancel()
                }
            }
        }
    }

    suspend fun resume(accountId: String): Boolean = mutex.withLock {
        accountId !in activePurges && blockedAccountIds.remove(accountId)
    }

    private fun isAvailableLocked(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
    ): Boolean = expectedScope == currentScope && expectedScope.accountId !in blockedAccountIds
}

internal data class InteractionAccountOperationLease(val accountId: String)

internal class InteractionAccountLifecycleGeneration(val revision: Long)

private fun InteractionAccountLifecycleGeneration?.nextGeneration(): InteractionAccountLifecycleGeneration =
    InteractionAccountLifecycleGeneration(
        revision = when (val current = this?.revision) {
            null -> 1L
            Long.MAX_VALUE -> Long.MAX_VALUE
            else -> current + 1L
        },
    )

internal sealed interface InteractionAccountDeletionPurgeRegistration {
    data object AlreadyPurged : InteractionAccountDeletionPurgeRegistration

    data class Existing(
        val completion: CompletableDeferred<DomainResult<Int>>,
    ) : InteractionAccountDeletionPurgeRegistration

    data class Owner(
        val completion: CompletableDeferred<DomainResult<Int>>,
        val settlement: CompletableDeferred<DomainResult<Int>?>,
        val idle: CompletableDeferred<Unit>?,
    ) : InteractionAccountDeletionPurgeRegistration
}

internal suspend fun InteractionAccountDeletionPurgeRegistration.Existing.awaitAlreadyBlockedOutcome():
    DomainResult<InteractionAccountDeletionPurgeOutcome> =
    when (val result = completion.await()) {
        is DomainResult.Failure -> result
        is DomainResult.Success -> DomainResult.Success(InteractionAccountDeletionPurgeOutcome.AlreadyBlocked)
    }
