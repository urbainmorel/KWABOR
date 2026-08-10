@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
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
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InteractionCoordinatorSubmissionTest {
    @Test
    fun submitReturnsDurableQueuedOutcomeWithoutDependingOnEventCollector() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        repository.drainCalls.clear()

        val result = coordinator.submit(
            expectedScope = A_SCOPE,
            listingId = LISTING_ID_ONE,
            kind = InteractionKind.Like,
            desiredSelected = true,
        )

        val queued = assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(result).value,
        )
        assertEquals(LISTING_ID_ONE, queued.pending.listingId)
        assertEquals(0, queued.pending.attemptCount)
        runCurrent()
        assertEquals(listOf(A_SCOPE), repository.drainCalls)
    }

    @Test
    fun submitRejectsStaleExpectedScopeBeforeWritingAnything() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        tracker.update(accountId = ACCOUNT_ID_B, accountSetupComplete = true)
        runCurrent()

        val result = coordinator.submit(
            expectedScope = A_SCOPE,
            listingId = LISTING_ID_ONE,
            kind = InteractionKind.Like,
            desiredSelected = true,
        )

        assertIs<DomainError.AuthenticationRequired>(assertIs<DomainResult.Failure>(result).error)
        assertEquals(emptyList(), repository.submittedCommands)
        assertEquals(emptyList(), repository.pending)
    }

    @Test
    fun scopeTransitionDuringDurableWriteKeepsRowAttributedToExpectedScopeAndReturnsSuperseded() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val writeReturnGate = CompletableDeferred<Unit>()
        repository.submitReturnGate = writeReturnGate
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val submission = async {
            coordinator.submit(
                expectedScope = A_SCOPE,
                listingId = LISTING_ID_ONE,
                kind = InteractionKind.Like,
                desiredSelected = true,
            )
        }
        runCurrent()
        assertEquals(A_SCOPE, repository.submittedCommands.single().scope)
        assertEquals(ACCOUNT_ID_A, repository.pending.single().accountId)

        tracker.update(accountId = ACCOUNT_ID_B, accountSetupComplete = true)
        runCurrent()
        writeReturnGate.complete(Unit)
        val result = submission.await()

        val superseded = assertIs<InteractionSubmitOutcome.Superseded>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(result).value,
        )
        assertEquals(repository.pending.single().operationId, superseded.operationId)
        assertEquals(ACCOUNT_ID_A, repository.pending.single().accountId)
    }
}

class InteractionCoordinatorSubmissionAndEventsTest {
    @Test
    fun activeCollectorReceivesFullHundredConfirmationBurstWithoutOverflowLoss() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        val received = mutableListOf<InteractionCoordinatorEvent>()
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            coordinator.events.collect { event ->
                received += event
                if (received.size == 1) releaseCollector.await()
            }
        }
        runCurrent()
        repository.drainOutcomes += hundredConfirmedOutcomes()

        coordinator.onForeground()
        runCurrent()

        assertEquals(1, received.size)
        releaseCollector.complete(Unit)
        settleCoordinatorBackgroundWork()
        assertEquals(100, received.filterIsInstance<InteractionCoordinatorEvent.Confirmed>().size)
        collector.cancel()
    }

    @Test
    fun submitReturnsAfterDurableWriteWhenEventBufferIsSaturated() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            coordinator.events.collect { releaseCollector.await() }
        }
        repository.drainOutcomes += confirmedOutcomes(count = 101)

        coordinator.onForeground()
        runCurrent()
        val submission = async {
            coordinator.submit(
                expectedScope = A_SCOPE,
                listingId = LISTING_ID_ONE,
                kind = InteractionKind.Like,
                desiredSelected = true,
            )
        }
        runCurrent()

        assertTrue(submission.isCompleted)
        assertIs<DomainResult.Success<InteractionSubmitOutcome>>(submission.await())
        releaseCollector.complete(Unit)
        collector.cancel()
    }

    @Test
    fun saturatedAccountBEventsDoNotBlockAccountAPurgeFinalization() = runTest {
        val tracker = ViewerSessionScopeTracker().apply {
            update(accountId = ACCOUNT_ID_B, accountSetupComplete = true)
        }
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            coordinator.events.collect { releaseCollector.await() }
        }
        runCurrent()
        repository.drainOutcomes += (1L..102L).map { operationId ->
            confirmedOutcome(operationId = operationId, scope = B_SCOPE)
        }

        coordinator.onForeground()
        runCurrent()
        val purge = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()

        assertTrue(purge.isCompleted)
        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(purge.await()).value,
        )
        releaseCollector.complete(Unit)
        collector.cancel()
    }

    @Test
    fun staleEpochCompletionIsFilteredAfterAccountTransition() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val drainGate = CompletableDeferred<Unit>()
        repository.drainGate = drainGate
        repository.drainOutcomes += listOf(confirmedOutcome(operationId = 1L, scope = A_SCOPE))
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        val received = mutableListOf<InteractionCoordinatorEvent>()
        val collector = backgroundScope.launch { coordinator.events.collect(received::add) }
        runCurrent()

        coordinator.onForeground()
        runCurrent()
        tracker.update(accountId = ACCOUNT_ID_B, accountSetupComplete = true)
        drainGate.complete(Unit)
        settleCoordinatorBackgroundWork()

        assertEquals(emptyList(), received.filterIsInstance<InteractionCoordinatorEvent.Confirmed>())
        assertNull(coordinator.reconciliationSignals.value)
        collector.cancel()
    }

    @Test
    fun accountDeletionPurgeWaitsForInflightSubmitThenRemovesItsRow() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val submitGate = CompletableDeferred<Unit>()
        repository.submitReturnGate = submitGate
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val submission = async {
            coordinator.submit(A_SCOPE, LISTING_ID_ONE, InteractionKind.Like, desiredSelected = true)
        }
        runCurrent()
        val purge = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()

        assertEquals(1, repository.pending.size)
        assertFalse(purge.isCompleted)
        submitGate.complete(Unit)
        assertIs<InteractionSubmitOutcome.Superseded>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(submission.await()).value,
        )
        val purgeOutcome = assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(purge.await()).value
        assertEquals(1, assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(purgeOutcome).purgedCount)
        assertEquals(emptyList(), repository.pending)
    }

    @Test
    fun blockedHydrationOutsideCommitLeaseDoesNotDelayPurgeAndLateCommitIsRejected() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        val generation = requireNotNull(coordinator.deliveryCommitGate.captureLifecycleGeneration(A_SCOPE))
        val hydrationStarted = CompletableDeferred<Unit>()
        val releaseHydration = CompletableDeferred<Unit>()
        var committed = false
        val lateCommit = async {
            hydrationStarted.complete(Unit)
            releaseHydration.await()
            coordinator.deliveryCommitGate.runIfLifecycleGenerationCurrent(A_SCOPE, generation) {
                committed = true
            }
        }
        hydrationStarted.await()

        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID_A),
            ).value,
        )
        coordinator.resumeAfterAccountDeletionFailure(ACCOUNT_ID_A)
        releaseHydration.complete(Unit)

        assertFalse(lateCommit.await())
        assertFalse(committed)
    }

    @Test
    fun purgeRejectsBlankNormalizesAccountAndBlocksEveryLaterSubmit() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val blank = coordinator.purgeForAccountDeletion("   ")
        assertEquals(
            DomainError.Validation("error.interaction.account_id_invalid"),
            assertIs<DomainResult.Failure>(blank).error,
        )
        assertEquals(emptyList(), repository.purgeCalls)

        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(
                coordinator.purgeForAccountDeletion("  ${ACCOUNT_ID_A.uppercase()}  "),
            ).value,
        )
        val submission = coordinator.submit(
            A_SCOPE,
            LISTING_ID_ONE,
            InteractionKind.Favorite,
            desiredSelected = true,
        )
        assertIs<DomainError.AuthenticationRequired>(assertIs<DomainResult.Failure>(submission).error)
        assertEquals(listOf(ACCOUNT_ID_A), repository.purgeCalls)
        assertEquals(emptyList(), repository.submittedCommands)
    }

    @Test
    fun purgeDomainFailureAutomaticallyUnblocksFutureSubmit() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            purgeFailure = DomainError.LocalStorageUnavailable("error.interaction.purge_failed")
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        assertIs<DomainResult.Failure>(coordinator.purgeForAccountDeletion(ACCOUNT_ID_A))
        repository.purgeFailure = null
        val submission = coordinator.submit(
            A_SCOPE,
            LISTING_ID_ONE,
            InteractionKind.Like,
            desiredSelected = true,
        )

        assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(submission).value,
        )
        assertEquals(1, repository.pending.size)
    }

    @Test
    fun explicitResumeAfterRemoteDeletionFailureIsIdempotentAndAllowsSubmitAgain() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID_A),
            ).value,
        )

        coordinator.resumeAfterAccountDeletionFailure(ACCOUNT_ID_A.uppercase())
        coordinator.resumeAfterAccountDeletionFailure(ACCOUNT_ID_A)
        val submission = coordinator.submit(
            A_SCOPE,
            LISTING_ID_ONE,
            InteractionKind.Like,
            desiredSelected = true,
        )

        assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(submission).value,
        )
    }
}

class InteractionCoordinatorPurgeTest {
    @Test
    fun purgeExceptionAlsoUnblocksFutureSubmit() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            purgeThrowable = IllegalStateException("storage unavailable")
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        assertFailsWith<IllegalStateException> {
            coordinator.purgeForAccountDeletion(ACCOUNT_ID_A)
        }
        repository.purgeThrowable = null
        val submission = coordinator.submit(
            A_SCOPE,
            LISTING_ID_ONE,
            InteractionKind.Like,
            desiredSelected = true,
        )

        assertIs<DomainResult.Success<InteractionSubmitOutcome>>(submission)
    }

    @Test
    fun purgeCancellationAlsoUnblocksFutureSubmit() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val purgeGate = CompletableDeferred<Unit>()
        repository.purgeGate = purgeGate
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val purge = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()
        purge.cancel()
        runCurrent()
        assertTrue(purge.isCancelled)
        purgeGate.complete(Unit)
        settleCoordinatorBackgroundWork()

        val submission = coordinator.submit(
            A_SCOPE,
            LISTING_ID_ONE,
            InteractionKind.Like,
            desiredSelected = true,
        )
        assertIs<DomainResult.Success<InteractionSubmitOutcome>>(submission)
    }

    @Test
    fun cancelledPurgeOwnerDoesNotLeakALateRepositoryException() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val purgeGate = CompletableDeferred<Unit>()
        repository.purgeGate = purgeGate
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val purge = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()
        purge.cancel()
        repository.purgeThrowable = IllegalStateException("storage unavailable")
        purgeGate.complete(Unit)
        settleCoordinatorBackgroundWork()

        assertTrue(purge.isCancelled)
        val submission = coordinator.submit(
            A_SCOPE,
            LISTING_ID_ONE,
            InteractionKind.Like,
            desiredSelected = true,
        )
        assertIs<DomainResult.Success<InteractionSubmitOutcome>>(submission)
        assertEquals(listOf(ACCOUNT_ID_A), repository.purgeCalls)
    }

    @Test
    fun cancellationAfterLocalPurgeStillInvalidatesBufferedDeliveryAndResumesOnce() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val purgeEffectCompleted = CompletableDeferred<Unit>()
        val purgeReturnGate = CompletableDeferred<Unit>()
        repository.purgeEffectCompleted = purgeEffectCompleted
        repository.purgeReturnGate = purgeReturnGate
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        val collector = blockFirstInteractionEvent(coordinator)
        assertIs<DomainResult.Success<InteractionSubmitOutcome>>(
            coordinator.submit(A_SCOPE, LISTING_ID_ONE, InteractionKind.Like, desiredSelected = true),
        )
        runCurrent()
        val queuedBeforePurge = assertIs<InteractionCoordinatorEvent.Queued>(collector.captured.await())

        val purge = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        purgeEffectCompleted.await()
        purge.cancel()
        runCurrent()
        assertTrue(purge.isCancelled)
        purgeReturnGate.complete(Unit)
        settleCoordinatorBackgroundWork()

        val purgeSignal = requireNotNull(coordinator.reconciliationSignals.value)
        assertTrue(purgeSignal.requiresPendingValidation)
        assertTrue(purgeSignal.deliveryWatermark >= queuedBeforePurge.deliverySequence)
        var staleCommitted = false
        val accepted = coordinator.deliveryCommitGate.runIfEventDeliveryValid(queuedBeforePurge) {
            staleCommitted = true
        }
        assertFalse(accepted)
        assertFalse(staleCommitted)
        assertIs<DomainResult.Success<InteractionSubmitOutcome>>(
            coordinator.submit(A_SCOPE, LISTING_ID_ONE, InteractionKind.Like, desiredSelected = true),
        )
        assertEquals(listOf(ACCOUNT_ID_A), repository.purgeCalls)
        collector.close()
    }

    @Test
    fun acquiredCallbackPublishesOwnershipBeforeCallerCancellationCanLoseTheResult() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        val acquired = CompletableDeferred<Unit>()

        val purge = launch {
            val ownerJob = coroutineContext[Job] ?: error("Missing purge owner job")
            coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) {
                acquired.complete(Unit)
                ownerJob.cancel()
            }
        }
        acquired.await()
        purge.join()

        val secondOutcome = assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(
            coordinator.purgeForAccountDeletion(ACCOUNT_ID_A),
        ).value
        assertIs<InteractionAccountDeletionPurgeOutcome.AlreadyBlocked>(secondOutcome)
        assertEquals(listOf(ACCOUNT_ID_A), repository.purgeCalls)
    }

    @Test
    fun concurrentPurgesShareOneStorageOperation() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val purgeGate = CompletableDeferred<Unit>()
        repository.purgeGate = purgeGate
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        val first = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()
        val second = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        purgeGate.complete(Unit)
        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(first.await()).value,
        )
        assertIs<InteractionAccountDeletionPurgeOutcome.AlreadyBlocked>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(second.await()).value,
        )
        assertEquals(listOf(ACCOUNT_ID_A), repository.purgeCalls)
    }

    @Test
    fun confirmationFinishingAfterPurgeIsNotPublished() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        val drainGate = CompletableDeferred<Unit>()
        repository.drainGate = drainGate
        repository.drainOutcomes += listOf(confirmedOutcome(operationId = 1L, scope = A_SCOPE))
        val received = mutableListOf<InteractionCoordinatorEvent>()
        val collector = backgroundScope.launch { coordinator.events.collect(received::add) }
        runCurrent()

        coordinator.onForeground()
        runCurrent()
        val purge = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()
        assertFalse(purge.isCompleted)
        drainGate.complete(Unit)
        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(purge.await()).value,
        )
        advanceUntilIdle()

        assertEquals(emptyList(), received.filterIsInstance<InteractionCoordinatorEvent.Confirmed>())
        val purgeSignal = requireNotNull(coordinator.reconciliationSignals.value)
        assertTrue(purgeSignal.requiresPendingValidation)
        assertTrue(purgeSignal.stateVersion > 0L)
        collector.cancel()
    }

    @Test
    fun hydrationCapturedBeforePurgeIsRejectedAfterResumeOfTheSameScope() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository().apply {
            pending = listOf(
                pending(
                    operationId = 1L,
                    listingId = LISTING_ID_ONE,
                    kind = InteractionKind.Like,
                    attemptCount = 0,
                    status = PendingInteractionStatus.Scheduled(100L),
                ),
            )
            captureLoadPendingBeforeGate = true
            loadPendingGate = CompletableDeferred()
        }
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        val hydration = async { coordinator.hydrate(A_SCOPE, listOf(LISTING_ID_ONE)) }
        runCurrent()
        assertEquals(1, repository.loadPendingCalls.size)

        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID_A),
            ).value,
        )
        coordinator.resumeAfterAccountDeletionFailure(ACCOUNT_ID_A)
        repository.loadPendingGate?.complete(Unit)

        assertIs<DomainError.AuthenticationRequired>(
            assertIs<DomainResult.Failure>(hydration.await()).error,
        )
        assertEquals(emptyList(), repository.pending)
    }

    @Test
    fun slowAccountADrainAndPurgeDoNotBlockAccountBSubmit() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val drainGate = CompletableDeferred<Unit>()
        repository.drainGate = drainGate
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()

        coordinator.onForeground()
        runCurrent()
        val purgeA = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()
        assertFalse(purgeA.isCompleted)
        tracker.update(accountId = ACCOUNT_ID_B, accountSetupComplete = true)
        val submissionB = coordinator.submit(
            B_SCOPE,
            LISTING_ID_ONE,
            InteractionKind.Favorite,
            desiredSelected = true,
        )

        assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(submissionB).value,
        )
        drainGate.complete(Unit)
        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(purgeA.await()).value,
        )
        assertEquals(listOf(ACCOUNT_ID_B), repository.pending.map(PendingInteraction::accountId))
    }

    @Test
    fun oneHundredThousandWakeRequestsAreConflatedWhileDrainIsBlocked() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val drainGate = CompletableDeferred<Unit>()
        repository.drainGate = drainGate
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        runCurrent()
        assertEquals(listOf(A_SCOPE), repository.drainCalls)

        repeat(100_000) {
            coordinator.retryManually(A_SCOPE)
            coordinator.onForeground()
        }
        runCurrent()
        assertEquals(listOf(A_SCOPE), repository.drainCalls)

        drainGate.complete(Unit)
        settleCoordinatorBackgroundWork()

        assertEquals(2, repository.drainCalls.size)
        assertEquals(1, repository.retryCalls.count { (_, includeManual) -> includeManual })
    }
}

class InteractionCoordinatorEventSaturationTest {
    @Test
    fun failedRevalidationAfterAcknowledgementCreatesANewerRetryableDebt() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            coordinator.events.collect { releaseCollector.await() }
        }
        runCurrent()
        repository.drainOutcomes += confirmedOutcomes(count = 102)
        coordinator.onForeground()
        runCurrent()
        val acknowledged = requireNotNull(coordinator.reconciliationSignals.value)
        coordinator.acknowledgeAllReconciliationConsumers()
        assertEquals(null, coordinator.reconciliationSignals.value)
        assertFalse(
            coordinator.deliveryCommitGate.acknowledgeReconciliation(
                acknowledged,
                InteractionReconciliationConsumer.Favorites,
            ),
        )
        val oldQueued = oldQueuedEvent()

        assertTrue(coordinator.deliveryCommitGate.requestReconciliationFor(oldQueued))
        assertFalse(
            coordinator.deliveryCommitGate.requestReconciliationFor(oldQueued.copy(scope = B_SCOPE)),
        )

        val retried = requireNotNull(coordinator.reconciliationSignals.value)
        assertTrue(retried.stateVersion > acknowledged.stateVersion)
        assertEquals(1L, retried.deliveryWatermark)
        assertTrue(retried.requiresPendingValidation)
        releaseCollector.complete(Unit)
        collector.cancel()
    }

    @Test
    fun reconciliationCapturedBeforePurgeCannotCommitAfterResumeOfTheSameScope() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            coordinator.events.collect { releaseCollector.await() }
        }
        runCurrent()
        repository.drainOutcomes += confirmedOutcomes(count = 102)
        coordinator.onForeground()
        runCurrent()
        val capturedSignal = requireNotNull(coordinator.reconciliationSignals.value)

        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID_A),
            ).value,
        )
        coordinator.resumeAfterAccountDeletionFailure(ACCOUNT_ID_A)
        var committed = false

        val accepted = coordinator.deliveryCommitGate.runIfReconciliationCurrent(capturedSignal) {
            committed = true
        }

        assertFalse(accepted)
        assertFalse(committed)
        val purgeSignal = requireNotNull(coordinator.reconciliationSignals.value)
        assertTrue(purgeSignal.stateVersion > capturedSignal.stateVersion)
        assertTrue(purgeSignal.requiresPendingValidation)
        releaseCollector.complete(Unit)
        collector.cancel()
    }

    @Test
    fun overflowedLastConfirmationRequestsConfluentReconciliationAndDoesNotBlockPurge() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            coordinator.events.collect { releaseCollector.await() }
        }
        runCurrent()
        val outcomes = confirmedOutcomes(count = 102)
        repository.drainOutcomes += outcomes

        coordinator.onForeground()
        runCurrent()
        val signal = assertIs<InteractionReconciliationSignal>(coordinator.reconciliationSignals.value)
        assertEquals(A_SCOPE, signal.scope)
        assertTrue(signal.revision > 0L)
        assertEquals(102L, signal.deliveryWatermark)
        val lastConfirmation = assertIs<InteractionOperationOutcome.Confirmed>(outcomes.last())
        assertEquals(
            lastConfirmation.confirmation.operationId,
            signal.terminalWatermark(lastConfirmation.command.listingId, lastConfirmation.command.kind),
        )
        val purge = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()

        assertTrue(purge.isCompleted)
        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(purge.await()).value,
        )
        val purgeSignal = requireNotNull(coordinator.reconciliationSignals.value)
        assertTrue(purgeSignal.requiresPendingValidation)
        assertTrue(purgeSignal.stateVersion > signal.stateVersion)
        releaseCollector.complete(Unit)
        collector.cancel()
    }

    @Test
    fun overflowedLastRejectionRequestsConfluentReconciliationAndDoesNotBlockPurge() = runTest {
        val tracker = authenticatedTracker()
        val repository = FakeInteractionRepository()
        val coordinator = InteractionCoordinator(repository, tracker, FixedClock(100L), backgroundScope)
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch {
            coordinator.events.collect { releaseCollector.await() }
        }
        runCurrent()
        repository.drainOutcomes += confirmedOutcomes(count = 101) + rejectedOutcome(operationId = 102L)

        coordinator.onForeground()
        runCurrent()
        val signal = assertIs<InteractionReconciliationSignal>(coordinator.reconciliationSignals.value)
        assertEquals(A_SCOPE, signal.scope)
        assertTrue(signal.revision > 0L)
        assertEquals(102L, signal.deliveryWatermark)
        assertEquals(102L, signal.terminalWatermark(LISTING_ID_ONE, InteractionKind.Favorite))
        val purge = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()

        assertTrue(purge.isCompleted)
        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(purge.await()).value,
        )
        val purgeSignal = requireNotNull(coordinator.reconciliationSignals.value)
        assertTrue(purgeSignal.requiresPendingValidation)
        assertTrue(purgeSignal.stateVersion > signal.stateVersion)
        releaseCollector.complete(Unit)
        collector.cancel()
    }
}

internal class FakeInteractionRepository : InteractionRepository {
    val submittedCommands = mutableListOf<InteractionCommand>()
    val drainCalls = mutableListOf<InteractionAccountScope>()
    val retryCalls = mutableListOf<Pair<InteractionAccountScope, Boolean>>()
    val loadPendingCalls = mutableListOf<Pair<String, List<String>>>()
    val purgeCalls = mutableListOf<String>()
    val drainOutcomes = ArrayDeque<List<InteractionOperationOutcome>>()
    val nextAttemptAtResults = mutableListOf<Long?>()
    var pending: List<PendingInteraction> = emptyList()
    var nextAttemptAt: Long? = null
    var drainGate: CompletableDeferred<Unit>? = null
    var loadPendingGate: CompletableDeferred<Unit>? = null
    var captureLoadPendingBeforeGate: Boolean = false
    var purgeGate: CompletableDeferred<Unit>? = null
    var purgeEffectCompleted: CompletableDeferred<Unit>? = null
    var purgeReturnGate: CompletableDeferred<Unit>? = null
    var purgeFailure: DomainError? = null
    var drainFailure: DomainError? = null
    var purgeThrowable: Throwable? = null
    var submitReturnGate: CompletableDeferred<Unit>? = null
    var afterLoadPending: ((Int) -> Unit)? = null
    private var operationId = 0L

    override suspend fun submit(command: InteractionCommand): DomainResult<InteractionSubmitOutcome> {
        submittedCommands += command
        val queued = pending(
            operationId = ++operationId,
            listingId = command.listingId,
            kind = command.kind,
            attemptCount = 0,
            status = PendingInteractionStatus.Scheduled(100L),
        ).copy(
            accountId = command.scope.accountId,
            desiredSelected = command.desiredSelected,
        )
        pending = pending + queued
        submitReturnGate?.await()
        return DomainResult.Success(InteractionSubmitOutcome.Queued(command, queued))
    }

    override suspend fun loadPending(
        accountId: String,
        listingIds: List<String>,
    ): DomainResult<List<PendingInteraction>> {
        loadPendingCalls += accountId to listingIds
        afterLoadPending?.invoke(loadPendingCalls.size)
        val captured = pending.filter { interaction ->
            interaction.accountId == accountId && interaction.listingId in listingIds
        }
        loadPendingGate?.await()
        return DomainResult.Success(
            if (captureLoadPendingBeforeGate) {
                captured
            } else {
                pending.filter { interaction ->
                    interaction.accountId == accountId && interaction.listingId in listingIds
                }
            },
        )
    }

    override suspend fun drainDue(scope: InteractionAccountScope): DomainResult<InteractionDrainOutcome> {
        drainCalls += scope
        drainGate?.await()
        drainFailure?.let { error -> return DomainResult.Failure(error) }
        return DomainResult.Success(
            InteractionDrainOutcome(
                scope = scope,
                operations = drainOutcomes.removeFirstOrNull().orEmpty(),
            ),
        )
    }

    override suspend fun nextAttemptAt(accountId: String): DomainResult<Long?> = DomainResult.Success(
        if (nextAttemptAtResults.isEmpty()) nextAttemptAt else nextAttemptAtResults.removeAt(0),
    )

    override suspend fun retryAccount(
        scope: InteractionAccountScope,
        includeManualFailures: Boolean,
    ): DomainResult<Int> {
        retryCalls += scope to includeManualFailures
        return DomainResult.Success(0)
    }

    override suspend fun purge(accountId: String): DomainResult<Int> {
        purgeCalls += accountId
        purgeGate?.await()
        purgeThrowable?.let { throwable -> throw throwable }
        purgeFailure?.let { error -> return DomainResult.Failure(error) }
        val retained = pending.filterNot { interaction -> interaction.accountId == accountId }
        val purged = pending.size - retained.size
        pending = retained
        purgeEffectCompleted?.complete(Unit)
        purgeReturnGate?.await()
        return DomainResult.Success(purged)
    }
}

internal class FixedClock(private val now: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = now
}

internal fun authenticatedTracker(): ViewerSessionScopeTracker = ViewerSessionScopeTracker().apply {
    update(accountId = ACCOUNT_ID_A, accountSetupComplete = true)
}

internal fun pending(
    operationId: Long,
    listingId: String,
    kind: InteractionKind,
    attemptCount: Int,
    status: PendingInteractionStatus,
): PendingInteraction = PendingInteraction(
    operationId = operationId,
    accountId = ACCOUNT_ID_A,
    listingId = listingId,
    kind = kind,
    desiredSelected = true,
    enqueuedAtEpochMilliseconds = 100L,
    attemptCount = attemptCount,
    status = status,
)

private data class BlockedInteractionEventCollector(
    val captured: CompletableDeferred<InteractionCoordinatorEvent>,
    private val release: CompletableDeferred<Unit>,
    private val job: Job,
) {
    fun close() {
        release.complete(Unit)
        job.cancel()
    }
}

private suspend fun TestScope.blockFirstInteractionEvent(
    coordinator: InteractionCoordinator,
): BlockedInteractionEventCollector {
    val captured = CompletableDeferred<InteractionCoordinatorEvent>()
    val release = CompletableDeferred<Unit>()
    val job = backgroundScope.launch {
        coordinator.events.collect { event ->
            captured.complete(event)
            release.await()
        }
    }
    runCurrent()
    return BlockedInteractionEventCollector(captured, release, job)
}

private fun TestScope.settleCoordinatorBackgroundWork() {
    runCurrent()
    advanceUntilIdle()
}

private suspend fun InteractionCoordinator.acknowledgeAllReconciliationConsumers() {
    assertTrue(
        deliveryCommitGate.acknowledgeReconciliation(
            requireNotNull(reconciliationSignals.value),
            InteractionReconciliationConsumer.Explore,
        ),
    )
    assertTrue(
        deliveryCommitGate.acknowledgeReconciliation(
            requireNotNull(reconciliationSignals.value),
            InteractionReconciliationConsumer.Favorites,
        ),
    )
}

private fun oldQueuedEvent(): InteractionCoordinatorEvent.Queued = InteractionCoordinatorEvent.Queued(
    scope = A_SCOPE,
    deliverySequence = 1L,
    command = InteractionCommand(
        scope = A_SCOPE,
        listingId = LISTING_ID_ONE,
        kind = InteractionKind.Like,
        desiredSelected = true,
    ),
    pending = pending(
        operationId = 1L,
        listingId = LISTING_ID_ONE,
        kind = InteractionKind.Like,
        attemptCount = 0,
        status = PendingInteractionStatus.Scheduled(100L),
    ),
)

private fun hundredConfirmedOutcomes(): List<InteractionOperationOutcome> = confirmedOutcomes(count = 100)

private fun confirmedOutcomes(count: Int): List<InteractionOperationOutcome> = (1L..count.toLong()).map { operationId ->
    confirmedOutcome(operationId = operationId, scope = A_SCOPE)
}

private fun confirmedOutcome(operationId: Long, scope: InteractionAccountScope): InteractionOperationOutcome {
    val listingId = "33333333-3333-4333-8333-${operationId.toString().padStart(12, '0')}"
    val command = InteractionCommand(
        scope = scope,
        listingId = listingId,
        kind = InteractionKind.Like,
        desiredSelected = true,
    )
    return InteractionOperationOutcome.Confirmed(
        command = command,
        confirmation = InteractionConfirmation.Like(
            operationId = operationId,
            scope = scope,
            listingId = listingId,
            liked = true,
            likesCount = null,
            mutatedAtEpochMilliseconds = 100L,
        ),
    )
}

private fun rejectedOutcome(operationId: Long): InteractionOperationOutcome.Rejected =
    InteractionOperationOutcome.Rejected(
        command = InteractionCommand(
            scope = A_SCOPE,
            listingId = LISTING_ID_ONE,
            kind = InteractionKind.Favorite,
            desiredSelected = false,
        ),
        operationId = operationId,
        reason = InteractionRejectionReason.PermissionDenied,
    )

internal const val ACCOUNT_ID_A = "11111111-1111-4111-8111-111111111111"
internal const val ACCOUNT_ID_B = "22222222-2222-4222-8222-222222222222"
internal const val LISTING_ID_ONE = "33333333-3333-4333-8333-333333333333"
internal const val LISTING_ID_TWO = "44444444-4444-4444-8444-444444444444"
internal const val LISTING_ID_WITH_LETTERS = "abcdefab-cdef-4abc-8def-abcdefabcdef"
internal val A_SCOPE = InteractionAccountScope(accountId = ACCOUNT_ID_A, epoch = 1L)
internal val B_SCOPE = InteractionAccountScope(accountId = ACCOUNT_ID_B, epoch = 2L)

internal fun listingId(index: Int): String = "55555555-5555-4555-8555-${index.toString().padStart(12, '0')}"
