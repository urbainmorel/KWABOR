package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Account-scoped operation fence. Its mutex protects only tokens and counters; I/O is performed
 * outside the critical section while an identity lease keeps account deletion waiting for idle.
 */
internal class InteractionLifecycleGate {
    private val mutex = Mutex()
    private val blockedAccountIds = mutableSetOf<String>()
    private val activeOperationCounts = mutableMapOf<String, Int>()
    private val activeOperationLeases = mutableSetOf<InteractionAccountOperationLease>()
    private val operationIdleSignals = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val deletionBlocks = mutableMapOf<String, InteractionDeletionBlockToken>()
    private val accountGenerations = mutableMapOf<String, InteractionAccountLifecycleGeneration>()
    private val lifecycleSnapshots = MutableStateFlow<Map<String, InteractionLifecycleSnapshot>>(emptyMap())

    fun captureQueuedCommandFence(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
    ): InteractionQueuedCommandFence {
        val canonicalExpectedScope = expectedScope.toInteractionLifecycleScope()
        if (canonicalExpectedScope != currentScope?.toInteractionLifecycleScope()) {
            return InteractionQueuedCommandFence.Blocked
        }
        val snapshot = lifecycleSnapshots.value[canonicalExpectedScope.accountId]
        return if (snapshot?.blocked == true) {
            InteractionQueuedCommandFence.Blocked
        } else {
            InteractionQueuedCommandFence.Captured(snapshot?.revision ?: 0L)
        }
    }

    suspend fun beginOperation(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
    ): InteractionAccountOperationLease? = mutex.withLock {
        val canonicalExpectedScope = expectedScope.toInteractionLifecycleScope()
        val canonicalCurrentScope = currentScope?.toInteractionLifecycleScope()
        if (!isAvailableLocked(canonicalExpectedScope, canonicalCurrentScope)) return@withLock null
        val accountId = canonicalExpectedScope.accountId
        val activeCount = activeOperationCounts[accountId] ?: 0
        check(activeCount < Int.MAX_VALUE) { "Interaction account operation count overflow." }
        if (activeCount == 0) operationIdleSignals[accountId] = CompletableDeferred()
        activeOperationCounts[accountId] = activeCount + 1
        InteractionAccountOperationLease(
            accountId = accountId,
            generation = accountGenerations.getOrPut(accountId) {
                InteractionAccountLifecycleGeneration(revision = 0L)
            },
        ).also { lease -> check(activeOperationLeases.add(lease)) }
    }

    suspend fun endOperation(lease: InteractionAccountOperationLease) {
        withContext(NonCancellable) {
            mutex.withLock {
                check(activeOperationLeases.remove(lease)) {
                    "Interaction account operation lease was already released."
                }
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

    suspend fun availableGeneration(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
    ): InteractionAccountLifecycleGeneration? = mutex.withLock {
        val canonicalExpectedScope = expectedScope.toInteractionLifecycleScope()
        if (!isAvailableLocked(canonicalExpectedScope, currentScope?.toInteractionLifecycleScope())) {
            return@withLock null
        }
        accountGenerations.getOrPut(canonicalExpectedScope.accountId) {
            InteractionAccountLifecycleGeneration(revision = 0L)
        }
    }

    suspend fun isAvailableAtGeneration(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
        generation: InteractionAccountLifecycleGeneration,
    ): Boolean = mutex.withLock {
        val canonicalExpectedScope = expectedScope.toInteractionLifecycleScope()
        isAvailableLocked(canonicalExpectedScope, currentScope?.toInteractionLifecycleScope()) &&
            accountGenerations[canonicalExpectedScope.accountId] === generation
    }

    suspend fun isLeaseCurrent(
        lease: InteractionAccountOperationLease,
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
    ): Boolean = mutex.withLock {
        val canonicalExpectedScope = expectedScope.toInteractionLifecycleScope()
        lease in activeOperationLeases &&
            lease.accountId == canonicalExpectedScope.accountId &&
            isAvailableLocked(canonicalExpectedScope, currentScope?.toInteractionLifecycleScope()) &&
            accountGenerations[canonicalExpectedScope.accountId] === lease.generation
    }

    suspend fun runIfLeaseCurrent(
        lease: InteractionAccountOperationLease,
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
        action: () -> Unit,
    ): Boolean = mutex.withLock {
        val canonicalExpectedScope = expectedScope.toInteractionLifecycleScope()
        val current = lease in activeOperationLeases &&
            lease.accountId == canonicalExpectedScope.accountId &&
            isAvailableLocked(canonicalExpectedScope, currentScope?.toInteractionLifecycleScope()) &&
            accountGenerations[canonicalExpectedScope.accountId] === lease.generation
        if (!current) return@withLock false
        action()
        true
    }

    suspend fun runIfAvailable(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
        action: () -> Unit,
    ): Boolean {
        mutex.lock()
        return try {
            if (!isAvailableLocked(expectedScope.toInteractionLifecycleScope(), currentScope?.toInteractionLifecycleScope())) {
                return false
            }
            action()
            true
        } finally {
            mutex.unlock()
        }
    }

    suspend fun registerDeletionBlock(accountId: String): InteractionDeletionBlockRegistration = mutex.withLock {
        val canonicalAccountId = accountId.toInteractionLifecycleAccountId().requireInteractionAccountId()
        if (canonicalAccountId in blockedAccountIds) {
            return@withLock InteractionDeletionBlockRegistration.AlreadyBlocked
        }
        blockedAccountIds += canonicalAccountId
        accountGenerations[canonicalAccountId] = accountGenerations[canonicalAccountId].nextGeneration()
        lifecycleSnapshots.value = lifecycleSnapshots.value + (
            canonicalAccountId to InteractionLifecycleSnapshot(
                revision = checkNotNull(accountGenerations[canonicalAccountId]).revision,
                blocked = true,
            )
        )
        val token = InteractionDeletionBlockToken(canonicalAccountId)
        deletionBlocks[canonicalAccountId] = token
        InteractionDeletionBlockRegistration.Owner(
            token = token,
            idle = operationIdleSignals[canonicalAccountId],
        )
    }

    suspend fun finishDeletionBlock(token: InteractionDeletionBlockToken, committed: Boolean): Boolean =
        withContext(NonCancellable) {
            mutex.withLock {
                if (deletionBlocks[token.accountId] !== token) return@withLock false
                deletionBlocks.remove(token.accountId)
                if (!committed) {
                    blockedAccountIds.remove(token.accountId)
                    lifecycleSnapshots.value = lifecycleSnapshots.value + (
                        token.accountId to InteractionLifecycleSnapshot(
                            revision = accountGenerations[token.accountId]?.revision ?: 0L,
                            blocked = false,
                        )
                    )
                }
                true
            }
        }

    suspend fun resume(accountId: String): Boolean = mutex.withLock {
        val canonicalAccountId = accountId.toInteractionLifecycleAccountId().requireInteractionAccountId()
        if (canonicalAccountId in deletionBlocks) return@withLock false
        blockedAccountIds.remove(canonicalAccountId).also { removed ->
            if (removed) {
                lifecycleSnapshots.value = lifecycleSnapshots.value + (
                    canonicalAccountId to InteractionLifecycleSnapshot(
                        revision = accountGenerations[canonicalAccountId]?.revision ?: 0L,
                        blocked = false,
                    )
                )
            }
        }
    }

    private fun isAvailableLocked(
        expectedScope: InteractionAccountScope,
        currentScope: InteractionAccountScope?,
    ): Boolean = expectedScope == currentScope && expectedScope.accountId !in blockedAccountIds
}

internal class InteractionAccountOperationLease(
    val accountId: String,
    val generation: InteractionAccountLifecycleGeneration,
)

internal class InteractionAccountLifecycleGeneration(val revision: Long)

internal class InteractionDeletionBlockToken internal constructor(val accountId: String)

internal sealed interface InteractionDeletionBlockRegistration {
    data object AlreadyBlocked : InteractionDeletionBlockRegistration

    data class Owner(
        val token: InteractionDeletionBlockToken,
        val idle: Deferred<Unit>?,
    ) : InteractionDeletionBlockRegistration
}

internal sealed interface InteractionQueuedCommandFence {
    data object NotRequired : InteractionQueuedCommandFence

    data object Blocked : InteractionQueuedCommandFence

    data class Captured(val revision: Long) : InteractionQueuedCommandFence
}

private data class InteractionLifecycleSnapshot(
    val revision: Long,
    val blocked: Boolean,
)

private fun InteractionAccountLifecycleGeneration?.nextGeneration(): InteractionAccountLifecycleGeneration =
    InteractionAccountLifecycleGeneration(
        revision = when (val current = this?.revision) {
            null -> 1L
            Long.MAX_VALUE -> Long.MAX_VALUE
            else -> current + 1L
        },
    )

internal fun String.toInteractionLifecycleAccountId(): String = trim().lowercase()

internal fun InteractionAccountScope.toInteractionLifecycleScope(): InteractionAccountScope =
    copy(accountId = accountId.toInteractionLifecycleAccountId())

private fun String.requireInteractionAccountId(): String =
    also { accountId -> require(accountId.isNotEmpty()) { "Interaction lifecycle account id must not be empty." } }
