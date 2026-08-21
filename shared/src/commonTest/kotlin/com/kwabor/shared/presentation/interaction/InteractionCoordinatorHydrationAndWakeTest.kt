@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.interaction.InteractionCommand
import com.kwabor.shared.domain.interaction.InteractionKind
import com.kwabor.shared.domain.interaction.InteractionOperationOutcome
import com.kwabor.shared.domain.interaction.InteractionRejectionReason
import com.kwabor.shared.domain.interaction.PendingInteraction
import com.kwabor.shared.domain.interaction.PendingInteractionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InteractionCoordinatorHydrationAndWakeTest {
    @Test
    fun hydrationRestoresSuspendedIntentAndFiltersRejectedOperation() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            pending = hydrationPendingFixtures()
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val hydration = assertIs<DomainResult.Success<InteractionHydration>>(
            coordinator.hydrate(A_SCOPE, listOf(LISTING_ID_ONE, LISTING_ID_TWO)),
        ).value

        assertEquals(listOf(1L, 2L, 3L), hydration.pending.map(PendingInteraction::operationId))
        val offlineOverlay = hydration.overlays.single { overlay -> overlay.listingId == LISTING_ID_ONE }
        assertEquals(true, offlineOverlay.liked)
        assertEquals(true, offlineOverlay.favorited)
        assertTrue(offlineOverlay.restoresOfflineState)
        val suspendedOverlay = hydration.overlays.single { overlay -> overlay.listingId == LISTING_ID_TWO }
        assertEquals(true, suspendedOverlay.liked)
        assertFalse(suspendedOverlay.restoresOfflineState)
        assertFalse(hydration.pending.first().status.isTerminal)
    }

    @Test
    fun hydrationNormalizesDeduplicatesAndLoadsOneHundredTwentyIdsInBoundedChunks() = runTest {
        val listingIds = (1..120).map(::listingId)
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            pending = listingIds.mapIndexed { index, listingId ->
                pending(
                    operationId = index.toLong() + 1L,
                    listingId = listingId,
                    kind = InteractionKind.Like,
                    attemptCount = 0,
                    status = PendingInteractionStatus.Scheduled(100L),
                )
            }
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        val requestedIds = buildList {
            listingIds.forEachIndexed { index, listingId ->
                add("  $listingId  ")
                if (index % 10 == 0) add(listingId)
            }
            add(" ")
        }

        val hydration = assertIs<DomainResult.Success<InteractionHydration>>(
            coordinator.hydrate(A_SCOPE, requestedIds),
        ).value

        assertEquals(listOf(50, 50, 20), repository.loadPendingCalls.map { it.second.size })
        assertEquals(listingIds, repository.loadPendingCalls.flatMap { it.second })
        assertEquals(listingIds, hydration.pending.map(PendingInteraction::listingId))
    }

    @Test
    fun hydrationCanonicalizesUppercaseUuidBeforeDeduplicationAndFiltering() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            pending = listOf(
                pending(
                    operationId = 1L,
                    listingId = LISTING_ID_WITH_LETTERS,
                    kind = InteractionKind.Favorite,
                    attemptCount = 0,
                    status = PendingInteractionStatus.Scheduled(100L),
                ),
            )
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val hydration = assertIs<DomainResult.Success<InteractionHydration>>(
            coordinator.hydrate(
                A_SCOPE,
                listOf("  ${LISTING_ID_WITH_LETTERS.uppercase()}  ", LISTING_ID_WITH_LETTERS),
            ),
        ).value

        assertEquals(listOf(listOf(LISTING_ID_WITH_LETTERS)), repository.loadPendingCalls.map { it.second })
        assertEquals(listOf(LISTING_ID_WITH_LETTERS), hydration.pending.map(PendingInteraction::listingId))
    }

    @Test
    fun hydrationWithNoNormalizedIdReturnsEmptyWithoutLoadingAllPendingRows() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            pending = listOf(
                pending(
                    operationId = 1L,
                    listingId = LISTING_ID_ONE,
                    kind = InteractionKind.Like,
                    attemptCount = 1,
                    status = PendingInteractionStatus.Scheduled(100L),
                ),
            )
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val hydration = assertIs<DomainResult.Success<InteractionHydration>>(
            coordinator.hydrate(A_SCOPE, listOf("", "  ")),
        ).value

        assertEquals(emptyList(), hydration.pending)
        assertEquals(emptyList(), hydration.overlays)
        assertEquals(emptyList(), repository.loadPendingCalls)
    }

    @Test
    fun hydrationRejectsMoreThanOneThousandUniqueIdsWithoutReadingStorage() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val result = coordinator.hydrate(A_SCOPE, (1..1_001).map(::listingId))

        val error = assertIs<DomainError.Validation>(assertIs<DomainResult.Failure>(result).error)
        assertEquals("error.interaction.too_many_listing_ids", error.messageKey)
        assertEquals(emptyList(), repository.loadPendingCalls)
    }

    @Test
    fun hydrationScopeTransitionAfterFirstChunkReturnsNoPartialResult() = runTest {
        val listingIds = (1..51).map(::listingId)
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            afterLoadPending = { callCount ->
                if (callCount == 1) tracker.update(accountId = ACCOUNT_ID_B, accountSetupComplete = true)
            }
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val result = coordinator.hydrate(A_SCOPE, listingIds)

        assertIs<DomainError.AuthenticationRequired>(assertIs<DomainResult.Failure>(result).error)
        assertEquals(listOf(listingIds.take(50)), repository.loadPendingCalls.map { it.second })
    }

    @Test
    fun hydrationFinishingAfterPurgeReturnsNoOverlay() = runTest {
        val tracker = authenticatedTracker()
        val loadGate = CompletableDeferred<Unit>()
        val repository = FakeInteractionRepository().apply {
            pending = listOf(
                pending(
                    operationId = 1L,
                    listingId = LISTING_ID_ONE,
                    kind = InteractionKind.Like,
                    attemptCount = 1,
                    status = PendingInteractionStatus.Scheduled(100L),
                ),
            )
            loadPendingGate = loadGate
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val hydration = async { coordinator.hydrate(A_SCOPE, listOf(LISTING_ID_ONE)) }
        runCurrent()
        val purge = async { coordinator.commitAccountDeletionBlock(ACCOUNT_ID_A) }
        runCurrent()
        assertFalse(purge.isCompleted)
        loadGate.complete(Unit)

        assertIs<DomainError.AuthenticationRequired>(
            assertIs<DomainResult.Failure>(hydration.await()).error,
        )
        purge.await()
    }

    @Test
    fun staleManualRetryDoesNotRearmOrDrainTheReplacementAccount() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        repository.retryCalls.clear()
        repository.drainCalls.clear()

        coordinator.retryManually(A_SCOPE)
        tracker.update(accountId = ACCOUNT_ID_B, accountSetupComplete = true)
        runCurrent()

        assertEquals(listOf(B_SCOPE to false), repository.retryCalls)
        assertEquals(listOf(B_SCOPE), repository.drainCalls)
        assertFalse(repository.retryCalls.any { (scope, manual) -> scope == B_SCOPE && manual })
    }

    @Test
    fun schedulerNeverSleepsLongerThanFiveMinutes() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            nextAttemptAt = 10L * 60L * 1_000L
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(0L), backgroundScope)
        runCurrent()
        val initialDrainCount = repository.drainCalls.size

        advanceTimeBy(5L * 60L * 1_000L - 1L)
        runCurrent()
        assertEquals(initialDrainCount, repository.drainCalls.size)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(repository.drainCalls.size > initialDrainCount)
        coordinator.onScreenAppeared()
    }

    @Test
    fun failedDrainDoesNotRescheduleAnAlreadyDueOperationInAHotLoop() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            drainFailure = DomainError.LocalStorageUnavailable()
            nextAttemptAt = 100L
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)

        runCurrent()
        val failedDrainCount = repository.drainCalls.size
        runCurrent()

        assertEquals(1, failedDrainCount)
        assertEquals(failedDrainCount, repository.drainCalls.size)
        coordinator.onScreenAppeared()
        runCurrent()
        assertEquals(failedDrainCount + 1, repository.drainCalls.size)
    }

    @Test
    fun networkRetryWaitsForItsOwnBackoffInsteadOfDrainingAnotherDueOperationImmediately() = runTest {
        val tracker = authenticatedTracker()
        val retryAt = 1_100L
        val repository = FakeInteractionRepository().apply {
            nextAttemptAtResults += listOf(100L, null)
            drainOutcomes += listOf(
                retryingOutcome(PendingInteractionStatus.Scheduled(retryAt)),
            )
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)

        runCurrent()
        val initialDrainCount = repository.drainCalls.size
        assertEquals(1, initialDrainCount)
        repository.nextAttemptAtResults.clear()
        advanceTimeBy(retryAt - 101L)
        runCurrent()
        assertEquals(initialDrainCount, repository.drainCalls.size)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(initialDrainCount + 1, repository.drainCalls.size)
        coordinator.onScreenAppeared()
    }

    @Test
    fun sessionSuspensionDoesNotAutoDrainAnotherDueOperationUntilAnExternalWake() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            nextAttemptAtResults += listOf(100L, null)
            drainOutcomes += listOf(
                retryingOutcome(PendingInteractionStatus.SuspendedForSession),
            )
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)

        runCurrent()
        val suspendedDrainCount = repository.drainCalls.size
        assertEquals(1, suspendedDrainCount)
        repository.nextAttemptAtResults.clear()
        advanceTimeBy(5L * 60L * 1_000L)
        runCurrent()
        assertEquals(suspendedDrainCount, repository.drainCalls.size)

        coordinator.onScreenAppeared()
        runCurrent()
        assertEquals(suspendedDrainCount + 1, repository.drainCalls.size)
    }
}

private fun retryingOutcome(status: PendingInteractionStatus): InteractionOperationOutcome {
    val command = InteractionCommand(
        scope = A_SCOPE,
        listingId = LISTING_ID_ONE,
        kind = InteractionKind.Like,
        desiredSelected = true,
    )
    return InteractionOperationOutcome.Retrying(
        command = command,
        pending = pending(
            operationId = 1L,
            listingId = command.listingId,
            kind = command.kind,
            attemptCount = 1,
            status = status,
        ),
    )
}

private fun hydrationPendingFixtures(): List<PendingInteraction> = listOf(
    pending(
        operationId = 1L,
        listingId = LISTING_ID_ONE,
        kind = InteractionKind.Like,
        attemptCount = 0,
        status = PendingInteractionStatus.Scheduled(100L),
    ),
    pending(
        operationId = 2L,
        listingId = LISTING_ID_ONE,
        kind = InteractionKind.Favorite,
        attemptCount = 2,
        status = PendingInteractionStatus.Scheduled(2_000L),
    ),
    pending(
        operationId = 3L,
        listingId = LISTING_ID_TWO,
        kind = InteractionKind.Like,
        attemptCount = 1,
        status = PendingInteractionStatus.SuspendedForManualRetry,
    ),
    pending(
        operationId = 4L,
        listingId = LISTING_ID_TWO,
        kind = InteractionKind.Favorite,
        attemptCount = 1,
        status = PendingInteractionStatus.Rejected(InteractionRejectionReason.PermissionDenied),
    ),
)
