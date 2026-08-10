package com.kwabor.shared.domain.interaction

import com.kwabor.shared.domain.core.DomainResult

interface InteractionRepository {
    suspend fun submit(command: InteractionCommand): DomainResult<InteractionSubmitOutcome>

    suspend fun loadPending(
        accountId: String,
        listingIds: List<String> = emptyList(),
    ): DomainResult<List<PendingInteraction>>

    suspend fun drainDue(scope: InteractionAccountScope): DomainResult<InteractionDrainOutcome>

    suspend fun nextAttemptAt(accountId: String): DomainResult<Long?>

    suspend fun retryAccount(scope: InteractionAccountScope, includeManualFailures: Boolean = false): DomainResult<Int>

    suspend fun purge(accountId: String): DomainResult<Int>
}

fun interface ActiveInteractionScopeProvider {
    fun currentScope(): InteractionAccountScope?
}
