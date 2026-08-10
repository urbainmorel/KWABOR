@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kwabor.shared.data.interaction

import com.kwabor.shared.data.local.InteractionOutboxCapacityExceededException
import com.kwabor.shared.data.local.InteractionOutboxKind
import com.kwabor.shared.data.local.InteractionOutboxOperation
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.interaction.AccountScopedFavoriteMutationRepository
import com.kwabor.shared.domain.interaction.AccountScopedListingLikeRepository
import com.kwabor.shared.domain.interaction.ActiveInteractionScopeProvider
import com.kwabor.shared.domain.interaction.InteractionAccountScope
import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionOperationOutcome
import com.kwabor.shared.domain.interaction.InteractionRejectionReason
import com.kwabor.shared.domain.interaction.InteractionSubmitOutcome
import com.kwabor.shared.domain.interaction.ListingLikeMutation
import com.kwabor.shared.domain.interaction.PendingInteractionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataInteractionRepositoryTest {
    @Test
    fun submitPersistsBeforeTransportAndRearmsSameTerminalWithStableId() = runTest {
        val fixture = interactionFixture()

        val first = fixture.repository.submit(likeCommand(desiredSelected = true))

        val firstQueued = assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(first).value,
        )
        assertEquals(0, fixture.catalog.calls.size)
        assertTrue(
            fixture.outbox.recordTerminalFailure(
                operationId = firstQueued.pending.operationId,
                expectedAttemptCount = 0,
                terminalErrorCode = INTERACTION_TERMINAL_MANUAL,
            ),
        )
        fixture.clock.now = 200L

        val repeated = fixture.repository.submit(likeCommand(desiredSelected = true))

        val repeatedQueued = assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(repeated).value,
        )
        assertEquals(firstQueued.pending.operationId, repeatedQueued.pending.operationId)
        assertEquals(0, repeatedQueued.pending.attemptCount)
        assertEquals(PendingInteractionStatus.Scheduled(200L), repeatedQueued.pending.status)
        assertEquals(0, fixture.catalog.calls.size)
    }

    @Test
    fun persistedIntentDrainsAfterRepositoryRecreation() = runTest {
        val fixture = interactionFixture()
        fixture.repository.submit(likeCommand(desiredSelected = true))
        val recreated = fixture.newRepository()

        val result = recreated.drainDue(ACCOUNT_SCOPE_A_ONE)

        val drain = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            result,
        ).value
        val confirmed = assertIs<InteractionOperationOutcome.Confirmed>(drain.operations.single())
        val confirmation = assertIs<com.kwabor.shared.domain.interaction.InteractionConfirmation.Like>(
            confirmed.confirmation,
        )
        assertEquals(true, confirmation.liked)
        assertEquals(7, confirmation.likesCount)
        assertEquals(emptyList(), fixture.outbox.snapshot(ACCOUNT_ID_A))
    }

    @Test
    fun newerTargetSupersedesNonCancellableInFlightResponse() = runTest {
        val transportGate = CompletableDeferred<Unit>()
        val fixture = interactionFixture()
        fixture.catalog.transportGate = transportGate
        fixture.repository.submit(likeCommand(desiredSelected = true))
        val firstDrain = async { fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE) }
        runCurrent()
        assertEquals(listOf(true), fixture.catalog.calls)

        val replacement = fixture.repository.submit(likeCommand(desiredSelected = false))
        val replacementQueued = assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(replacement).value,
        )
        transportGate.complete(Unit)
        val firstOutcome = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            firstDrain.await(),
        ).value.operations.single()

        assertIs<InteractionOperationOutcome.Superseded>(firstOutcome)
        assertEquals(
            replacementQueued.pending.operationId,
            fixture.outbox.snapshot(ACCOUNT_ID_A).single().operationId,
        )
        val secondDrain = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE),
        ).value
        val secondConfirmation = assertIs<com.kwabor.shared.domain.interaction.InteractionConfirmation.Like>(
            assertIs<InteractionOperationOutcome.Confirmed>(secondDrain.operations.single()).confirmation,
        )
        assertEquals(false, secondConfirmation.liked)
        assertEquals(listOf(true, false), fixture.catalog.calls)
    }

    @Test
    fun scopeIsRecheckedInsideTransportSectionAndAuthRearmsOnlyForExactAccountScope() = runTest {
        val fixture = interactionFixture()
        fixture.repository.submit(likeCommand(desiredSelected = true))
        fixture.outbox.beforeCurrentLookup = {
            fixture.activeScope.current = ACCOUNT_SCOPE_B_ONE
            fixture.outbox.beforeCurrentLookup = null
        }

        val fenced = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE),
        ).value

        val suspended = assertIs<InteractionOperationOutcome.Retrying>(fenced.operations.single()).pending
        assertEquals(PendingInteractionStatus.SuspendedForSession, suspended.status)
        assertEquals(0, fixture.catalog.calls.size)
        assertEquals(
            emptyList(),
            assertIs<DomainResult.Success<List<com.kwabor.shared.domain.interaction.PendingInteraction>>>(
                fixture.repository.loadPending(ACCOUNT_ID_B),
            ).value,
        )
        fixture.activeScope.current = ACCOUNT_SCOPE_A_TWO
        val staleEpochRetry = fixture.repository.retryAccount(ACCOUNT_SCOPE_A_ONE)
        assertIs<DomainError.AuthenticationRequired>(assertIs<DomainResult.Failure>(staleEpochRetry).error)

        val rearmed = fixture.repository.retryAccount(ACCOUNT_SCOPE_A_TWO)

        assertEquals(1, assertIs<DomainResult.Success<Int>>(rearmed).value)
        val resumed = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_TWO),
        ).value
        assertIs<InteractionOperationOutcome.Confirmed>(resumed.operations.single())
    }

    @Test
    fun expectedAccountRejectsTokenSwapEvenWhenPresentationScopeStillLooksCurrent() = runTest {
        val fixture = interactionFixture()
        fixture.repository.submit(likeCommand(desiredSelected = true))
        fixture.catalog.tokenAccountId = ACCOUNT_ID_B

        val mismatched = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE),
        ).value

        val suspended = assertIs<InteractionOperationOutcome.Retrying>(mismatched.operations.single()).pending
        assertEquals(PendingInteractionStatus.SuspendedForSession, suspended.status)
        assertEquals(listOf(ACCOUNT_ID_A), fixture.catalog.expectedAccountIds)
        assertEquals(1, fixture.outbox.snapshot(ACCOUNT_ID_A).size)
        fixture.catalog.tokenAccountId = ACCOUNT_ID_A
        assertEquals(1, assertIs<DomainResult.Success<Int>>(fixture.repository.retryAccount(ACCOUNT_SCOPE_A_ONE)).value)

        val resumed = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE),
        ).value

        assertIs<InteractionOperationOutcome.Confirmed>(resumed.operations.single())
        assertEquals(emptyList(), fixture.outbox.snapshot(ACCOUNT_ID_A))
    }

    @Test
    fun ambiguousLostResponseKeepsStableOperationAndConvergesThroughIdempotentRetry() = runTest {
        val fixture = interactionFixture(retryDelayMilliseconds = 1_000L)
        fixture.catalog.responses += DomainResult.Failure(DomainError.NetworkUnavailable())
        fixture.catalog.responses += successfulLikeMutation(liked = true)
        val queued = assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(
                fixture.repository.submit(likeCommand(desiredSelected = true)),
            ).value,
        )

        val firstDrain = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE),
        ).value

        val retrying = assertIs<InteractionOperationOutcome.Retrying>(firstDrain.operations.single()).pending
        assertEquals(queued.pending.operationId, retrying.operationId)
        assertEquals(1, retrying.attemptCount)
        assertEquals(PendingInteractionStatus.Scheduled(1_100L), retrying.status)
        assertEquals(true, fixture.catalog.serverDesiredSelected)
        val nextAttemptAt = assertIs<DomainResult.Success<Long?>>(fixture.repository.nextAttemptAt(ACCOUNT_ID_A)).value
        assertEquals(1_100L, nextAttemptAt)
        fixture.clock.now = 1_100L

        val recreated = fixture.newRepository()
        val secondDrain = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            recreated.drainDue(ACCOUNT_SCOPE_A_ONE),
        ).value

        assertIs<InteractionOperationOutcome.Confirmed>(secondDrain.operations.single())
        assertEquals(listOf(true, true), fixture.catalog.calls)
        assertEquals(emptyList(), fixture.outbox.snapshot(ACCOUNT_ID_A))
    }

    @Test
    fun validationPublishesRejectionAndReclaimsTheDurableRow() = runTest {
        val fixture = interactionFixture()
        fixture.catalog.responses += DomainResult.Failure(DomainError.Validation("invalid"))
        fixture.repository.submit(likeCommand(desiredSelected = true))

        val drain = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE),
        ).value

        val rejected = assertIs<InteractionOperationOutcome.Rejected>(drain.operations.single())
        assertEquals(InteractionRejectionReason.Validation, rejected.reason)
        assertEquals(
            emptyList(),
            assertIs<DomainResult.Success<List<com.kwabor.shared.domain.interaction.PendingInteraction>>>(
                fixture.repository.loadPending(ACCOUNT_ID_A),
            ).value,
        )
        assertEquals(emptyList(), fixture.outbox.snapshot(ACCOUNT_ID_A))
        assertEquals(0, assertIs<DomainResult.Success<Int>>(fixture.repository.retryAccount(ACCOUNT_SCOPE_A_ONE)).value)
    }

    @Test
    fun memoryOnlyOutboxFailsClosedBeforeAnyReadWriteOrTransport() = runTest {
        val fixture = interactionFixture(isDurable = false)

        val results = listOf(
            fixture.repository.submit(likeCommand(desiredSelected = true)),
            fixture.repository.loadPending(ACCOUNT_ID_A, listOf(LISTING_ID)),
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE),
            fixture.repository.nextAttemptAt(ACCOUNT_ID_A),
            fixture.repository.retryAccount(ACCOUNT_SCOPE_A_ONE),
            fixture.repository.purge(ACCOUNT_ID_A),
        )

        results.forEach { result ->
            assertIs<DomainError.LocalStorageUnavailable>(assertIs<DomainResult.Failure>(result).error)
        }
        assertEquals(0, fixture.outbox.calls)
        assertEquals(0, fixture.catalog.calls.size)
    }

    @Test
    fun restartedRepositoryCollectsRejectedRowsAndRecoversCapacityWithoutDeletingManualFailures() = runTest {
        val fixture = interactionFixture(maxOperationCount = 2)
        val rejected = assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(
                fixture.repository.submit(likeCommand(desiredSelected = true, listingId = LISTING_ID)),
            ).value,
        )
        val manual = assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(
                fixture.repository.submit(likeCommand(desiredSelected = true, listingId = LISTING_ID_TWO)),
            ).value,
        )
        assertTrue(
            fixture.outbox.recordTerminalFailure(
                operationId = rejected.pending.operationId,
                expectedAttemptCount = 0,
                terminalErrorCode = INTERACTION_TERMINAL_NOT_FOUND,
            ),
        )
        assertTrue(
            fixture.outbox.recordTerminalFailure(
                operationId = manual.pending.operationId,
                expectedAttemptCount = 0,
                terminalErrorCode = INTERACTION_TERMINAL_MANUAL,
            ),
        )

        val resumedSubmit = fixture.newRepository().submit(
            likeCommand(desiredSelected = false, listingId = LISTING_ID_THREE),
        )

        assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(resumedSubmit).value,
        )
        val recovered = fixture.outbox.snapshot(ACCOUNT_ID_A)
        assertEquals(2, recovered.size)
        assertTrue(recovered.any { operation -> operation.operationId == manual.pending.operationId })
        assertTrue(recovered.any { operation -> operation.listingId == LISTING_ID_THREE })
        assertTrue(recovered.none { operation -> operation.operationId == rejected.pending.operationId })
    }

    @Test
    fun unexpectedFailureRequiresExplicitManualRetry() = runTest {
        val fixture = interactionFixture()
        fixture.catalog.responses += DomainResult.Failure(DomainError.Unexpected())
        fixture.catalog.responses += successfulLikeMutation(liked = true)
        fixture.repository.submit(likeCommand(desiredSelected = true))

        val firstDrain = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE),
        ).value

        val paused = assertIs<InteractionOperationOutcome.Retrying>(firstDrain.operations.single()).pending
        assertEquals(PendingInteractionStatus.SuspendedForManualRetry, paused.status)
        assertNull(assertIs<DomainResult.Success<Long?>>(fixture.repository.nextAttemptAt(ACCOUNT_ID_A)).value)
        assertEquals(0, assertIs<DomainResult.Success<Int>>(fixture.repository.retryAccount(ACCOUNT_SCOPE_A_ONE)).value)
        assertEquals(
            1,
            assertIs<DomainResult.Success<Int>>(
                fixture.repository.retryAccount(ACCOUNT_SCOPE_A_ONE, includeManualFailures = true),
            ).value,
        )
        val retried = assertIs<DomainResult.Success<com.kwabor.shared.domain.interaction.InteractionDrainOutcome>>(
            fixture.repository.drainDue(ACCOUNT_SCOPE_A_ONE),
        ).value
        assertIs<InteractionOperationOutcome.Confirmed>(retried.operations.single())
    }

    @Test
    fun equalJitterBackoffIsDeterministicAndNeverExceedsFiveMinutes() {
        val maximumJitter = InteractionJitterSource { untilExclusive -> untilExclusive - 1L }
        val policy = EqualJitterInteractionRetryDelayPolicy(jitterSource = maximumJitter)

        assertEquals(1_000L, policy.delayMilliseconds(nextAttemptCount = 1))
        assertEquals(MAX_INTERACTION_RETRY_DELAY_MILLISECONDS, policy.delayMilliseconds(nextAttemptCount = 30))
    }
}

private data class InteractionFixture(
    val outbox: FakeInteractionOutboxPersistence,
    val catalog: FakeCatalogInteractionRepository,
    val favorites: FakeFavoritesRepository,
    val activeScope: MutableActiveInteractionScopeProvider,
    val clock: MutableInteractionClock,
    val retryDelayPolicy: InteractionRetryDelayPolicy,
) {
    val repository: DataInteractionRepository = newRepository()

    fun newRepository(): DataInteractionRepository = DataInteractionRepository(
        outbox = outbox,
        listingLikeRepository = catalog,
        favoriteMutationRepository = favorites,
        activeScopeProvider = activeScope,
        clockProvider = clock,
        retryDelayPolicy = retryDelayPolicy,
    )
}

private fun interactionFixture(
    retryDelayMilliseconds: Long = 1_000L,
    isDurable: Boolean = true,
    maxOperationCount: Int = Int.MAX_VALUE,
): InteractionFixture = InteractionFixture(
    outbox = FakeInteractionOutboxPersistence(
        isDurable = isDurable,
        maxOperationCount = maxOperationCount,
    ),
    catalog = FakeCatalogInteractionRepository(),
    favorites = FakeFavoritesRepository(),
    activeScope = MutableActiveInteractionScopeProvider(ACCOUNT_SCOPE_A_ONE),
    clock = MutableInteractionClock(now = 100L),
    retryDelayPolicy = InteractionRetryDelayPolicy { retryDelayMilliseconds },
)

private class FakeCatalogInteractionRepository : AccountScopedListingLikeRepository {
    val calls = mutableListOf<Boolean>()
    val expectedAccountIds = mutableListOf<String>()
    val responses = ArrayDeque<DomainResult<ListingLikeMutation>>()
    var transportGate: CompletableDeferred<Unit>? = null
    var serverDesiredSelected: Boolean? = null
    var tokenAccountId: String = ACCOUNT_ID_A

    override suspend fun setListingLike(
        expectedAccountId: String,
        listingId: String,
        liked: Boolean,
    ): DomainResult<ListingLikeMutation> {
        calls += liked
        expectedAccountIds += expectedAccountId
        if (tokenAccountId != expectedAccountId) {
            return DomainResult.Failure(DomainError.AuthenticationRequired())
        }
        serverDesiredSelected = liked
        transportGate?.await()
        return responses.removeFirstOrNull() ?: successfulLikeMutation(listingId = listingId, liked = liked)
    }
}

private class FakeFavoritesRepository : AccountScopedFavoriteMutationRepository {
    private var sequence = 0L

    override suspend fun setFavorite(
        expectedAccountId: String,
        listingId: String,
        favorited: Boolean,
    ): DomainResult<FavoriteMutation> = DomainResult.Success(
        FavoriteMutation(
            listingId = listingId,
            favorited = favorited,
            favoritedAtEpochMilliseconds = 1L.takeIf { favorited },
            clientMutationSequence = ++sequence,
        ),
    )
}

private class MutableActiveInteractionScopeProvider(
    var current: InteractionAccountScope?,
) : ActiveInteractionScopeProvider {
    override fun currentScope(): InteractionAccountScope? = current
}

private class MutableInteractionClock(var now: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = now
}

private class FakeInteractionOutboxPersistence(
    override val isDurable: Boolean = true,
    private val maxOperationCount: Int = Int.MAX_VALUE,
) : InteractionOutboxPersistence() {
    private val operations = mutableMapOf<InteractionKey, InteractionOutboxOperation>()
    private var nextOperationId = 1L
    var beforeCurrentLookup: (() -> Unit)? = null
    var calls: Int = 0
        private set

    override suspend fun enqueue(
        accountId: String,
        listingId: String,
        kind: InteractionOutboxKind,
        desiredSelected: Boolean,
        enqueuedAtEpochMilliseconds: Long,
    ): InteractionOutboxOperation {
        calls += 1
        val garbageCollectedKeys = operations.filterValues { operation ->
            operation.accountId == accountId && operation.terminalErrorCode in FAKE_DISPOSABLE_TERMINAL_ERRORS
        }.keys.toList()
        garbageCollectedKeys.forEach(operations::remove)
        val key = InteractionKey(accountId, listingId, kind)
        val current = operations[key]
        if (current?.desiredSelected == desiredSelected) return current
        val accountOperationCount = operations.count { (_, operation) -> operation.accountId == accountId }
        if (current == null && accountOperationCount >= maxOperationCount) {
            throw InteractionOutboxCapacityExceededException(maxOperationCount)
        }
        val inserted = InteractionOutboxOperation(
            operationId = nextOperationId++,
            accountId = accountId,
            listingId = listingId,
            kind = kind,
            desiredSelected = desiredSelected,
            enqueuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
            attemptCount = 0,
            nextAttemptAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
            terminalErrorCode = null,
        )
        operations[key] = inserted
        return inserted
    }

    override suspend fun listForAccount(accountId: String, limit: Int): List<InteractionOutboxOperation> =
        sortedOperations().also { calls += 1 }.filter { operation -> operation.accountId == accountId }.take(limit)

    override suspend fun listForAccountAndListingIds(
        accountId: String,
        listingIds: List<String>,
    ): List<InteractionOutboxOperation> {
        calls += 1
        beforeCurrentLookup?.invoke()
        return sortedOperations().filter { operation ->
            operation.accountId == accountId && operation.listingId in listingIds
        }
    }

    override suspend fun listReadyForAccount(
        accountId: String,
        readyAtEpochMilliseconds: Long,
        limit: Int,
    ): List<InteractionOutboxOperation> {
        calls += 1
        return sortedOperations().filter { operation ->
            operation.accountId == accountId &&
                operation.terminalErrorCode == null &&
                operation.nextAttemptAtEpochMilliseconds <= readyAtEpochMilliseconds
        }.take(limit)
    }

    override suspend fun listPausedForAccount(
        accountId: String,
        terminalErrorCode: String,
        limit: Int,
    ): List<InteractionOutboxOperation> {
        calls += 1
        return sortedOperations().filter { operation ->
            operation.accountId == accountId && operation.terminalErrorCode == terminalErrorCode
        }.take(limit)
    }

    override suspend fun deleteIfOperationMatches(
        operationId: Long,
        expectedAttemptCount: Int?,
        expectedTerminalErrorCode: String?,
    ): Boolean {
        val entry = operations.entries.firstOrNull { (_, operation) -> operation.operationId == operationId }
            ?: return false
        val operation = entry.value
        return if (
            expectedAttemptCount == null ||
            (
                operation.attemptCount == expectedAttemptCount &&
                    operation.terminalErrorCode == expectedTerminalErrorCode
                )
        ) {
            operations.remove(entry.key) != null
        } else {
            false
        }
    }

    override suspend fun recordRetry(
        operationId: Long,
        expectedAttemptCount: Int,
        nextAttemptAtEpochMilliseconds: Long,
    ): Boolean = updateByOperationId(operationId) { current ->
        current.takeIf { operation ->
            operation.attemptCount == expectedAttemptCount && operation.terminalErrorCode == null
        }?.copy(
            attemptCount = current.attemptCount + 1,
            nextAttemptAtEpochMilliseconds = nextAttemptAtEpochMilliseconds,
        )
    }

    override suspend fun recordTerminalFailure(
        operationId: Long,
        expectedAttemptCount: Int,
        terminalErrorCode: String,
    ): Boolean = updateByOperationId(operationId) { current ->
        current.takeIf { operation ->
            operation.attemptCount == expectedAttemptCount && operation.terminalErrorCode == null
        }?.copy(
            attemptCount = current.attemptCount + 1,
            terminalErrorCode = terminalErrorCode,
        )
    }

    override suspend fun rearm(
        operationId: Long,
        expectedDesiredSelected: Boolean,
        rearmedAtEpochMilliseconds: Long,
    ): Boolean = updateByOperationId(operationId) { current ->
        current.takeIf { operation -> operation.desiredSelected == expectedDesiredSelected }?.copy(
            enqueuedAtEpochMilliseconds = rearmedAtEpochMilliseconds,
            attemptCount = 0,
            nextAttemptAtEpochMilliseconds = rearmedAtEpochMilliseconds,
            terminalErrorCode = null,
        )
    }

    override suspend fun nextAttemptAtForAccount(accountId: String): Long? = operations.values
        .filter { operation -> operation.accountId == accountId && operation.terminalErrorCode == null }
        .minOfOrNull(InteractionOutboxOperation::nextAttemptAtEpochMilliseconds)

    override suspend fun purgeAccount(accountId: String): Int {
        val keys = operations.filterValues { operation -> operation.accountId == accountId }.keys.toList()
        keys.forEach(operations::remove)
        return keys.size
    }

    fun snapshot(accountId: String): List<InteractionOutboxOperation> =
        sortedOperations().filter { operation -> operation.accountId == accountId }

    private fun updateByOperationId(
        operationId: Long,
        transform: (InteractionOutboxOperation) -> InteractionOutboxOperation?,
    ): Boolean {
        val entry = operations.entries.firstOrNull { (_, operation) -> operation.operationId == operationId }
            ?: return false
        val replacement = transform(entry.value)
        return if (replacement == null) {
            false
        } else {
            operations[entry.key] = replacement
            true
        }
    }

    private fun sortedOperations(): List<InteractionOutboxOperation> = operations.values.sortedWith(
        compareBy(
            InteractionOutboxOperation::nextAttemptAtEpochMilliseconds,
            InteractionOutboxOperation::enqueuedAtEpochMilliseconds,
            InteractionOutboxOperation::operationId,
        ),
    )
}

private data class InteractionKey(
    val accountId: String,
    val listingId: String,
    val kind: InteractionOutboxKind,
)

private fun likeCommand(desiredSelected: Boolean, listingId: String = LISTING_ID): InteractionCommand =
    InteractionCommand(
        scope = ACCOUNT_SCOPE_A_ONE,
        listingId = listingId,
        kind = InteractionKind.Like,
        desiredSelected = desiredSelected,
    )

private fun successfulLikeMutation(listingId: String = LISTING_ID, liked: Boolean): DomainResult<ListingLikeMutation> =
    DomainResult.Success(
        ListingLikeMutation(
            listingId = listingId,
            liked = liked,
            likesCount = 7,
            mutatedAtEpochMilliseconds = 1_786_305_600_000L,
        ),
    )

private const val ACCOUNT_ID_A = "11111111-1111-4111-8111-111111111111"
private const val ACCOUNT_ID_B = "22222222-2222-4222-8222-222222222222"
private const val LISTING_ID = "33333333-3333-4333-8333-333333333333"
private const val LISTING_ID_TWO = "44444444-4444-4444-8444-444444444444"
private const val LISTING_ID_THREE = "55555555-5555-4555-8555-555555555555"
private val ACCOUNT_SCOPE_A_ONE = InteractionAccountScope(ACCOUNT_ID_A, epoch = 1L)
private val ACCOUNT_SCOPE_A_TWO = InteractionAccountScope(ACCOUNT_ID_A, epoch = 2L)
private val ACCOUNT_SCOPE_B_ONE = InteractionAccountScope(ACCOUNT_ID_B, epoch = 1L)
private val FAKE_DISPOSABLE_TERMINAL_ERRORS = setOf(
    INTERACTION_TERMINAL_VALIDATION,
    INTERACTION_TERMINAL_NOT_FOUND,
    INTERACTION_TERMINAL_PERMISSION,
)
