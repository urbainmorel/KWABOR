package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Short critical-section gate for notification work owned by one account.
 *
 * The mutex protects counters and tokens only. Repository, Room and network calls run after the
 * mutex has been released while an operation lease keeps account deletion waiting for quiescence.
 */
internal class NotificationLifecycleGate {
    private val mutex = Mutex()
    private val blockedAccountIds = mutableSetOf<String>()
    private val activeOperationCounts = mutableMapOf<String, Int>()
    private val activeOperationLeases = mutableSetOf<NotificationAccountOperationLease>()
    private val operationIdleSignals = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val deletionBlocks = mutableMapOf<String, NotificationDeletionBlockToken>()
    private val accountGenerations = mutableMapOf<String, NotificationAccountLifecycleGeneration>()

    suspend fun beginOperation(
        expectedScope: NotificationAccountScope,
        currentScope: NotificationAccountScope?,
    ): NotificationAccountOperationLease? =
        mutex.withLock {
            val canonicalExpectedScope = expectedScope.toCanonicalNotificationAccountScope()
            val canonicalCurrentScope = currentScope?.toCanonicalNotificationAccountScope()
            if (!isAvailableLocked(canonicalExpectedScope, canonicalCurrentScope)) return@withLock null
            val accountId = canonicalExpectedScope.accountId
            val activeCount = activeOperationCounts[accountId] ?: 0
            check(activeCount < Int.MAX_VALUE) { "Notification account operation count overflow." }
            if (activeCount == 0) operationIdleSignals[accountId] = CompletableDeferred()
            activeOperationCounts[accountId] = activeCount + 1
            NotificationAccountOperationLease(
                accountId = accountId,
                generation =
                    accountGenerations.getOrPut(accountId) {
                        NotificationAccountLifecycleGeneration(revision = 0L)
                    },
            ).also { lease -> check(activeOperationLeases.add(lease)) }
        }

    suspend fun endOperation(lease: NotificationAccountOperationLease) {
        withContext(NonCancellable) {
            mutex.withLock {
                check(activeOperationLeases.remove(lease)) {
                    "Notification account operation lease was already released."
                }
                val activeCount =
                    checkNotNull(activeOperationCounts[lease.accountId]) {
                        "Notification account operation lease was already released."
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
        expectedScope: NotificationAccountScope,
        currentScope: NotificationAccountScope?,
    ): NotificationAccountLifecycleGeneration? =
        mutex.withLock {
            val canonicalExpectedScope = expectedScope.toCanonicalNotificationAccountScope()
            val canonicalCurrentScope = currentScope?.toCanonicalNotificationAccountScope()
            if (!isAvailableLocked(canonicalExpectedScope, canonicalCurrentScope)) return@withLock null
            accountGenerations.getOrPut(canonicalExpectedScope.accountId) {
                NotificationAccountLifecycleGeneration(revision = 0L)
            }
        }

    suspend fun isAvailableAtGeneration(
        expectedScope: NotificationAccountScope,
        currentScope: NotificationAccountScope?,
        generation: NotificationAccountLifecycleGeneration,
    ): Boolean =
        mutex.withLock {
            val canonicalExpectedScope = expectedScope.toCanonicalNotificationAccountScope()
            isAvailableLocked(canonicalExpectedScope, currentScope?.toCanonicalNotificationAccountScope()) &&
                accountGenerations[canonicalExpectedScope.accountId] === generation
        }

    suspend fun isLeaseCurrent(
        lease: NotificationAccountOperationLease,
        expectedScope: NotificationAccountScope,
        currentScope: NotificationAccountScope?,
    ): Boolean =
        mutex.withLock {
            val canonicalExpectedScope = expectedScope.toCanonicalNotificationAccountScope()
            lease in activeOperationLeases &&
                lease.accountId == canonicalExpectedScope.accountId &&
                isAvailableLocked(canonicalExpectedScope, currentScope?.toCanonicalNotificationAccountScope()) &&
                accountGenerations[canonicalExpectedScope.accountId] === lease.generation
        }

    suspend fun registerDeletionBlock(accountId: String): NotificationDeletionBlockRegistration =
        mutex.withLock {
            val canonicalAccountId = accountId.toCanonicalNotificationAccountId().requireNotificationAccountId()
            if (canonicalAccountId in blockedAccountIds) {
                return@withLock NotificationDeletionBlockRegistration.AlreadyBlocked
            }
            blockedAccountIds += canonicalAccountId
            accountGenerations[canonicalAccountId] = accountGenerations[canonicalAccountId].nextGeneration()
            val token = NotificationDeletionBlockToken(canonicalAccountId)
            deletionBlocks[canonicalAccountId] = token
            NotificationDeletionBlockRegistration.Owner(
                token = token,
                idle = operationIdleSignals[canonicalAccountId],
            )
        }

    suspend fun finishDeletionBlock(
        token: NotificationDeletionBlockToken,
        committed: Boolean,
    ): Boolean =
        withContext(NonCancellable) {
            mutex.withLock {
                if (deletionBlocks[token.accountId] !== token) return@withLock false
                deletionBlocks.remove(token.accountId)
                if (!committed) blockedAccountIds.remove(token.accountId)
                true
            }
        }

    suspend fun resume(accountId: String): Boolean =
        mutex.withLock {
            val canonicalAccountId = accountId.toCanonicalNotificationAccountId().requireNotificationAccountId()
            if (canonicalAccountId in deletionBlocks) return@withLock false
            blockedAccountIds.remove(canonicalAccountId)
        }

    suspend fun isAvailable(
        expectedScope: NotificationAccountScope,
        currentScope: NotificationAccountScope?,
    ): Boolean =
        mutex.withLock {
            isAvailableLocked(
                expectedScope.toCanonicalNotificationAccountScope(),
                currentScope?.toCanonicalNotificationAccountScope(),
            )
        }

    private fun isAvailableLocked(
        expectedScope: NotificationAccountScope,
        currentScope: NotificationAccountScope?,
    ): Boolean = expectedScope == currentScope && expectedScope.accountId !in blockedAccountIds
}

internal class NotificationAccountOperationLease(
    val accountId: String,
    val generation: NotificationAccountLifecycleGeneration,
)

internal class NotificationAccountLifecycleGeneration(val revision: Long)

internal class NotificationDeletionBlockToken internal constructor(val accountId: String)

internal sealed interface NotificationDeletionBlockRegistration {
    data object AlreadyBlocked : NotificationDeletionBlockRegistration

    data class Owner(
        val token: NotificationDeletionBlockToken,
        val idle: Deferred<Unit>?,
    ) : NotificationDeletionBlockRegistration
}

private fun NotificationAccountLifecycleGeneration?.nextGeneration(): NotificationAccountLifecycleGeneration =
    NotificationAccountLifecycleGeneration(
        revision =
            when (val current = this?.revision) {
                null -> 1L
                Long.MAX_VALUE -> Long.MAX_VALUE
                else -> current + 1L
            },
    )

private fun String.requireNotificationAccountId(): String =
    also { accountId -> require(accountId.isNotEmpty()) { "Notification lifecycle account id must not be empty." } }

private fun NotificationAccountScope.toCanonicalNotificationAccountScope(): NotificationAccountScope =
    copy(accountId = accountId.toCanonicalNotificationAccountId())
