@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kwabor.shared.presentation.favorites

import app.cash.turbine.test
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.domain.i18n.AppLocale
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
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.interaction.InteractionAccountDeletionPurgeOutcome
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.InteractionCoordinatorEvent
import com.kwabor.shared.presentation.interaction.InteractionReconciliationConsumer
import com.kwabor.shared.presentation.interaction.InteractionReconciliationSignal
import com.kwabor.shared.presentation.interaction.InteractionReconciliationStatus
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val durableStrings = stringsFor(AppLocale.French).favorites

class FavoritesDurableInteractionsTest {
    @Test
    fun localWriteFailureLeavesTheFavoriteVisibleAndNeverCallsLegacyMutation() = runTest {
        val interactions = DurableInteractionRepository().apply {
            submitFailure = DomainError.LocalStorageUnavailable()
        }
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)

        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()

        assertEquals(listOf(FAVORITE_ID), harness.visibleIds())
        assertTrue(harness.runtime.state.value.removingListingIds.isEmpty())
        assertEquals(durableStrings.removeFailed, harness.runtime.state.value.mutationMessage)
        assertTrue(pages.legacyMutationRequests.isEmpty())
        harness.close()
    }

    @Test
    fun freshDurableQueueHidesTheCardWithoutClaimingOffline() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)

        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()

        assertTrue(harness.visibleIds().isEmpty())
        assertFalse(harness.runtime.state.value.isOffline)
        assertEquals(0, interactions.pendingFor(FAVORITE_ID).attemptCount)
        assertTrue(pages.legacyMutationRequests.isEmpty())
        harness.close()
    }

    @Test
    fun manualRetryUsesTheViewerScopeCapturedAtDispatch() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        interactions.retryCalls.clear()

        harness.runtime.dispatch(FavoritesIntent.Retry)
        advanceUntilIdle()

        assertEquals(listOf(A_INTERACTION_SCOPE to true), interactions.retryCalls)
        harness.close()
    }

    @Test
    fun retryEventKeepsTheRemovalAndShowsOfflineOnlyAfterAnAttempt() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()
        val queued = interactions.pendingFor(FAVORITE_ID)

        interactions.enqueue(
            InteractionOperationOutcome.Retrying(
                command = queued.toCommand(A_INTERACTION_SCOPE),
                pending = queued.copy(
                    attemptCount = 1,
                    status = PendingInteractionStatus.Scheduled(TEST_NOW + 1_000L),
                ),
            ),
        )
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertTrue(harness.visibleIds().isEmpty())
        assertTrue(harness.runtime.state.value.isOffline)
        assertEquals(setOf(FAVORITE_ID), harness.runtime.state.value.durableRetryListingIds)
        harness.close()
    }

    @Test
    fun suspendedSessionKeepsTheDurableRemovalWithoutClaimingNetworkFailure() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()
        val queued = interactions.pendingFor(FAVORITE_ID)

        interactions.enqueue(
            InteractionOperationOutcome.Retrying(
                command = queued.toCommand(A_INTERACTION_SCOPE),
                pending = queued.copy(
                    attemptCount = 1,
                    status = PendingInteractionStatus.SuspendedForSession,
                ),
            ),
        )
        harness.coordinator.onForeground()
        advanceUntilIdle()

        assertTrue(harness.visibleIds().isEmpty())
        assertFalse(harness.runtime.state.value.isOffline)
        assertEquals(emptySet(), harness.runtime.state.value.durableRetryListingIds)
        harness.close()
    }

    @Test
    fun processRestartHydratesPendingRemovalBeforePublishingThePage() = runTest {
        val interactions = DurableInteractionRepository().apply {
            putPending(pending(operationId = 7L, attemptCount = 2, selected = false))
        }
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)

        assertTrue(harness.visibleIds().isEmpty())
        assertTrue(harness.runtime.state.value.isOffline)
        assertTrue(interactions.hydrationRequests.flatten().contains(FAVORITE_ID))
        harness.close()
    }

    @Test
    fun confirmedFavoriteSequenceRejectsAnOlderConfirmationAndEmitsNoLegacyEffect() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()
        val removal = interactions.pendingFor(FAVORITE_ID)

        harness.runtime.effects.test {
            pages.serverFavoriteIds.clear()
            interactions.enqueue(removal.confirmed(selected = false, sequence = 7L))
            harness.coordinator.onForeground()
            settleCoordinatorBackgroundWork()
            expectNoEvents()

            pages.serverFavoriteIds += FAVORITE_ID
            interactions.enqueue(
                pending(operationId = 8L, selected = true).confirmed(selected = true, sequence = 8L),
            )
            harness.coordinator.onForeground()
            settleCoordinatorBackgroundWork()
            assertEquals(listOf(FAVORITE_ID), harness.visibleIds())

            interactions.enqueue(
                pending(operationId = 9L, selected = false).confirmed(selected = false, sequence = 7L),
            )
            harness.coordinator.onForeground()
            settleCoordinatorBackgroundWork()
            assertEquals(listOf(FAVORITE_ID), harness.visibleIds())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(pages.legacyMutationRequests.isEmpty())
        harness.close()
    }

    @Test
    fun rejectedRemovalRestoresThenRefreshesWithANeutralMessage() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()
        val removal = interactions.pendingFor(FAVORITE_ID)

        interactions.enqueue(
            InteractionOperationOutcome.Rejected(
                command = removal.toCommand(A_INTERACTION_SCOPE),
                operationId = removal.operationId,
                reason = InteractionRejectionReason.PermissionDenied,
            ),
        )
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertEquals(listOf(FAVORITE_ID), harness.visibleIds())
        assertEquals(durableStrings.removeFailed, harness.runtime.state.value.mutationMessage)
        assertFalse(harness.runtime.state.value.isOffline)
        harness.close()
    }

    @Test
    fun overflowedLastRejectionRehydratesMissingOutboxAndRefreshesAuthoritativePage() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()
        val removal = interactions.pendingFor(FAVORITE_ID)
        val refreshCallsBeforeOverflow = pages.listCalls
        interactions.loadPendingFailuresRemaining = Int.MAX_VALUE
        val releaseSlowCollector = CompletableDeferred<Unit>()
        val slowCollector = backgroundScope.launch {
            harness.coordinator.events.collect { releaseSlowCollector.await() }
        }
        runCurrent()
        interactions.enqueue(
            *overflowLikeConfirmations(A_INTERACTION_SCOPE, count = 101).toTypedArray(),
            InteractionOperationOutcome.Rejected(
                command = removal.toCommand(A_INTERACTION_SCOPE),
                operationId = removal.operationId,
                reason = InteractionRejectionReason.PermissionDenied,
            ),
        )

        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertRetryableFavoriteReconciliation(harness, pages, refreshCallsBeforeOverflow)
        val hydrationCallsAfterFailure = interactions.hydrationRequests.size
        runCurrent()
        assertEquals(hydrationCallsAfterFailure, interactions.hydrationRequests.size)

        interactions.loadPendingFailuresRemaining = 0
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertEquals(listOf(FAVORITE_ID), harness.visibleIds())
        assertTrue(pages.listCalls > refreshCallsBeforeOverflow)
        releaseSlowCollector.complete(Unit)
        slowCollector.cancel()
        harness.close()
    }
}

class FavoritesDurableOverflowTest {
    @Test
    fun overflowSignalOvertakesQueuedProcessorButSettledWatermarkPreventsStaleRemoval() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(
            initialFavoriteIds = listOf(FAVORITE_ID, OVERTAKE_FAVORITE_ID),
        )
        val harness = durableHarness(pages, interactions)
        val slowCollector = blockFavoritesEventCollector(harness.coordinator)
        val blocker = blockFavoritesHydration(harness, interactions)

        val refreshCallsBeforeOverflow = triggerDroppedFavoriteRejection(harness, interactions, pages)

        slowCollector.release()
        runCurrent()
        blocker.gate.complete(Unit)
        settleCoordinatorBackgroundWork()

        assertTrue(harness.coordinator.reconciliationSignals.value != null)
        assertTrue(OVERTAKE_FAVORITE_ID in harness.visibleIds())
        val refreshCallsAfterRelease = pages.listCalls
        assertTrue(refreshCallsAfterRelease >= refreshCallsBeforeOverflow)

        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertTrue(OVERTAKE_FAVORITE_ID in harness.visibleIds())
        assertFalse(OVERTAKE_FAVORITE_ID in harness.runtime.state.value.removingListingIds)
        assertTrue(pages.listCalls >= refreshCallsAfterRelease)
        assertTrue(pages.listCalls > refreshCallsBeforeOverflow)
        slowCollector.close()
        harness.close()
    }

    @Test
    fun durableEventBurstWhileRealHandlerIsBlockedStaysBoundedAndRequestsReconciliation() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(
            initialFavoriteIds = listOf(FAVORITE_ID, OVERTAKE_FAVORITE_ID),
        )
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.ScreenDisappeared)
        settleCoordinatorBackgroundWork()
        val blocker = blockFavoritesHydration(harness, interactions)
        interactions.loadPendingFailuresRemaining = Int.MAX_VALUE
        val hydrationCallsBeforeBurst = interactions.hydrationRequests.size

        spamQueuedFavoriteEvents(
            coordinator = harness.coordinator,
            count = FAVORITES_DURABLE_EVENT_BUFFER_CAPACITY * 100,
        )

        assertEquals(hydrationCallsBeforeBurst, interactions.hydrationRequests.size)
        assertEquals(null, harness.coordinator.reconciliationSignals.value)
        blocker.gate.complete(Unit)
        assertSingleFailedOverflowReconciliation(harness, interactions, hydrationCallsBeforeBurst)

        interactions.loadPendingFailuresRemaining = 0
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertFalse(OVERTAKE_FAVORITE_ID in harness.visibleIds())
        assertTrue(OVERTAKE_FAVORITE_ID in harness.runtime.state.value.removingListingIds)
        assertTrue(
            InteractionReconciliationConsumer.Favorites in
                requireNotNull(harness.coordinator.reconciliationSignals.value).acknowledgedConsumers,
        )
        harness.close()
    }

    @Test
    fun queuedAfterAcknowledgedDeliveryWatermarkDoesNotHydrateOrGetLost() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        val releaseSlowCollector = CompletableDeferred<Unit>()
        val slowCollector = backgroundScope.launch {
            harness.coordinator.events.collect { releaseSlowCollector.await() }
        }
        runCurrent()
        interactions.enqueue(*overflowFavoriteConfirmations(A_INTERACTION_SCOPE, count = 102).toTypedArray())
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()
        val acknowledgedByFavorites = requireNotNull(harness.coordinator.reconciliationSignals.value)
        harness.coordinator.deliveryCommitGate.acknowledgeReconciliation(
            acknowledgedByFavorites,
            InteractionReconciliationConsumer.Explore,
        )
        advanceUntilIdle()
        assertEquals(null, harness.coordinator.reconciliationSignals.value)
        releaseSlowCollector.complete(Unit)
        settleCoordinatorBackgroundWork()

        interactions.loadPendingFailuresRemaining = 1
        val result = harness.coordinator.submit(
            expectedScope = A_INTERACTION_SCOPE,
            listingId = FAVORITE_ID,
            kind = InteractionKind.Favorite,
            desiredSelected = false,
        )
        assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(result).value,
        )
        advanceUntilIdle()

        assertFalse(FAVORITE_ID in harness.visibleIds())
        assertTrue(FAVORITE_ID in harness.runtime.state.value.removingListingIds)
        assertEquals(1, interactions.loadPendingFailuresRemaining)
        slowCollector.cancel()
        harness.close()
    }
}

class FavoritesDurableOverflowAccumulatorTest {
    @Test
    fun newerDropWaitsForThePublishedDebtAcknowledgement() = runTest {
        val accumulator = FavoritesDurableOverflowAccumulator()
        accumulator.resetScope(A_INTERACTION_SCOPE)
        val first = queuedFavoriteEvent(deliverySequence = 1L)
        val second = queuedFavoriteEvent(deliverySequence = 2L)

        val published = requireNotNull(
            accumulator.offer(first, A_INTERACTION_SCOPE, tryEnqueue = { false }),
        )
        assertEquals(null, accumulator.offer(second, A_INTERACTION_SCOPE, tryEnqueue = { false }))
        assertEquals(
            null,
            accumulator.acknowledge(
                reconciliationSignal(scope = B_INTERACTION_SCOPE, deliveryWatermark = Long.MAX_VALUE),
            ),
        )
        assertEquals(null, accumulator.acknowledge(reconciliationSignal(deliveryWatermark = 0L)))
        val next = requireNotNull(accumulator.acknowledge(reconciliationSignal(deliveryWatermark = 1L)))

        assertEquals(first, published.event)
        assertEquals(second, next.event)
    }

    @Test
    fun scopeResetDiscardsStaleDebtWithoutResettingAcceptedDepth() = runTest {
        val accumulator = FavoritesDurableOverflowAccumulator()
        val accepted = queuedFavoriteEvent(deliverySequence = 1L)
        val stale = queuedFavoriteEvent(deliverySequence = 2L)

        assertEquals(
            null,
            accumulator.offer(accepted, A_INTERACTION_SCOPE, tryEnqueue = { true }),
        )
        accumulator.resetScope(B_INTERACTION_SCOPE)
        assertEquals(null, accumulator.offer(stale, B_INTERACTION_SCOPE, tryEnqueue = { false }))

        assertEquals(null, accumulator.eventHandled())
    }
}

class FavoritesDurablePurgeTest {
    @Test
    fun directSubmitResultCapturedBeforePurgeCannotApplyRemovalAfterSameScopeResumeAndSignalAck() = runTest {
        val tracker = ViewerSessionScopeTracker()
        val viewerScope = tracker.update(ACCOUNT_ID_A, accountSetupComplete = true)
        val interactions = DurableInteractionRepository()
        val coordinator = InteractionCoordinator(interactions, tracker, DurableClock, backgroundScope)
        val commitFence = requireNotNull(
            coordinator.captureFavoritesDirectCommitFence(viewerScope.toInteractionScope()),
        )
        submitQueuedFavoriteRemoval(coordinator, listingId = FAVORITE_ID)

        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID_A),
            ).value,
        )
        coordinator.resumeAfterAccountDeletionFailure(ACCOUNT_ID_A)
        coordinator.acknowledgeAllReconciliationConsumers()
        assertEquals(null, coordinator.reconciliationSignals.value)
        var removalOverlayApplied = false

        val committed = coordinator.runIfFavoritesDirectCommitCurrent(commitFence, Mutex()) {
            removalOverlayApplied = true
        }

        assertFalse(committed)
        assertFalse(removalOverlayApplied)
    }

    @Test
    fun bufferedQueuedBeforePurgeIsIgnoredAfterResumeOfTheSameScope() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(
            initialFavoriteIds = listOf(FAVORITE_ID, OVERTAKE_FAVORITE_ID),
        )
        val harness = durableHarness(pages, interactions)
        val originalScope = harness.tracker.currentScope
        val blocker = blockFavoritesHydration(harness, interactions)
        submitQueuedFavoriteRemoval(
            harness.coordinator,
            listingId = OVERTAKE_FAVORITE_ID,
        )
        runCurrent()
        val refreshCallsBeforePurge = pages.listCalls

        val purge = async { harness.coordinator.purgeForAccountDeletion(ACCOUNT_ID_A) }
        runCurrent()
        assertTrue(purge.isCompleted)
        assertIs<InteractionAccountDeletionPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<InteractionAccountDeletionPurgeOutcome>>(purge.await()).value,
        )
        assertEquals(originalScope, harness.tracker.currentScope)
        interactions.loadPendingFailuresRemaining = 1
        harness.coordinator.resumeAfterAccountDeletionFailure(ACCOUNT_ID_A)
        runCurrent()
        blocker.gate.complete(Unit)
        settleCoordinatorBackgroundWork()

        assertTrue(harness.coordinator.reconciliationSignals.value != null)
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertEquals(originalScope, harness.runtime.state.value.viewerScope)
        assertEquals(setOf(FAVORITE_ID, OVERTAKE_FAVORITE_ID), harness.visibleIds().toSet())
        assertTrue(harness.runtime.state.value.removingListingIds.isEmpty())
        assertTrue(pages.listCalls > refreshCallsBeforePurge)
        harness.close()
    }

    @Test
    fun staleAccountEventCannotMutateTheNewViewerPage() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()
        val accountARemoval = interactions.pendingFor(FAVORITE_ID)

        pages.serverFavoriteIds.clear()
        pages.serverFavoriteIds += ACCOUNT_B_FAVORITE_ID
        val accountBScope = harness.tracker.update(ACCOUNT_ID_B, accountSetupComplete = true)
        harness.runtime.dispatch(FavoritesIntent.ViewerContextChanged(accountBScope))
        advanceUntilIdle()
        assertEquals(listOf(ACCOUNT_B_FAVORITE_ID), harness.visibleIds())

        interactions.enqueue(accountARemoval.confirmed(selected = false, sequence = 1L))
        harness.coordinator.onForeground()
        advanceUntilIdle()

        assertEquals(listOf(ACCOUNT_B_FAVORITE_ID), harness.visibleIds())
        assertEquals(accountBScope, harness.runtime.state.value.viewerScope)
        harness.close()
    }

    @Test
    fun lateConfirmationForOperationOneCannotOverrideQueuedOfflineOperationTwo() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()
        val operationOne = interactions.pendingFor(FAVORITE_ID)
        val operationTwo = queueRepeatedFavoriteAddition(harness, interactions, operationOne)
        publishDurableOutcome(
            harness,
            interactions,
            InteractionOperationOutcome.Retrying(
                command = operationTwo.toCommand(A_INTERACTION_SCOPE),
                pending = operationTwo.copy(attemptCount = 1),
            ),
        )
        publishDurableOutcome(harness, interactions, operationOne.confirmed(selected = false, sequence = 1L))

        assertEquals(listOf(FAVORITE_ID), harness.visibleIds())
        assertTrue(harness.runtime.state.value.isOffline)
        assertEquals(operationTwo.operationId, interactions.pendingFor(FAVORITE_ID).operationId)
        harness.close()
    }

    @Test
    fun supersededOperationRehydratesTheLatestDesiredState() = runTest {
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
        advanceUntilIdle()
        val operationOne = interactions.pendingFor(FAVORITE_ID)
        val operationTwo = pending(
            operationId = operationOne.operationId + 1L,
            selected = true,
        )
        interactions.putPending(operationTwo)
        interactions.hydrationRequests.clear()

        interactions.enqueue(
            InteractionOperationOutcome.Superseded(
                command = operationOne.toCommand(A_INTERACTION_SCOPE),
                operationId = operationOne.operationId,
            ),
        )
        harness.coordinator.onForeground()
        settleCoordinatorBackgroundWork()

        assertEquals(listOf(FAVORITE_ID), harness.visibleIds())
        assertTrue(interactions.hydrationRequests.flatten().contains(FAVORITE_ID))
        assertEquals(operationTwo.operationId, interactions.pendingFor(FAVORITE_ID).operationId)
        harness.close()
    }

    @Test
    fun hydrationAfterMoreThanFiftyItemsStillIncludesTheFirstPage() = runTest {
        val pageOneIds = listingIds(from = 1, through = 20)
        val pageTwoIds = listingIds(from = 21, through = 40)
        val pageThreeIds = listingIds(from = 41, through = 60)
        val interactions = DurableInteractionRepository()
        val pages = DurableFavoritesRepository(
            pages = ArrayDeque(
                listOf(
                    favoritePage(pageOneIds, nextCursor = "cursor-2"),
                    favoritePage(pageTwoIds, nextCursor = "cursor-3"),
                    favoritePage(pageThreeIds, nextCursor = null),
                ),
            ),
        )
        val harness = durableHarness(pages, interactions)
        harness.runtime.dispatch(FavoritesIntent.LoadNext)
        advanceUntilIdle()
        interactions.putPending(
            pending(operationId = 41L, listingId = pageOneIds.first(), attemptCount = 1, selected = false),
        )
        interactions.hydrationRequests.clear()

        harness.runtime.dispatch(FavoritesIntent.LoadNext)
        advanceUntilIdle()

        val hydratedIds = interactions.hydrationRequests.flatten().toSet()
        assertEquals(60, hydratedIds.size)
        assertTrue(pageOneIds.first() in hydratedIds)
        assertFalse(pageOneIds.first() in harness.visibleIds())
        assertTrue(harness.runtime.state.value.isOffline)
        harness.close()
    }

    @Test
    fun hydrationWindowsOneThousandAndOneItemsWithoutDroppingTheLastPendingRemoval() = runTest {
        val favoriteIds = listingIds(from = 1, through = 1_001)
        val lastFavoriteId = favoriteIds.last()
        val interactions = DurableInteractionRepository().apply {
            putPending(
                pending(
                    operationId = 1L,
                    listingId = lastFavoriteId,
                    selected = false,
                ),
            )
        }
        val pages = DurableFavoritesRepository(initialFavoriteIds = favoriteIds)

        val harness = durableHarness(pages, interactions)

        assertTrue(interactions.hydrationRequests.all { request -> request.size <= 1_000 })
        assertTrue(interactions.hydrationRequests.flatten().contains(lastFavoriteId))
        assertFalse(lastFavoriteId in harness.visibleIds())
        assertTrue(lastFavoriteId in harness.runtime.state.value.removingListingIds)
        harness.close()
    }

    @Test
    fun screenAppearanceConvergesAfterMissingAConfirmationEvent() = runTest {
        val tracker = ViewerSessionScopeTracker()
        val scope = tracker.update(ACCOUNT_ID_A, accountSetupComplete = true)
        val interactions = DurableInteractionRepository()
        val coordinator = InteractionCoordinator(interactions, tracker, DurableClock, backgroundScope)
        runCurrent()
        val queued = coordinator.submit(
            expectedScope = A_INTERACTION_SCOPE,
            listingId = FAVORITE_ID,
            kind = InteractionKind.Favorite,
            desiredSelected = true,
        )
        val operation = assertIs<InteractionSubmitOutcome.Queued>(
            assertIs<DomainResult.Success<InteractionSubmitOutcome>>(queued).value,
        )
        runCurrent()
        interactions.enqueue(operation.pending.confirmed(selected = true, sequence = 1L))
        coordinator.onForeground()
        advanceUntilIdle()

        val pages = DurableFavoritesRepository(initialFavoriteIds = listOf(FAVORITE_ID))
        val runtime = FavoritesRuntime(
            presenter = FavoritesPresenter(pages),
            strings = durableStrings,
            coroutineScope = this,
            interactionCoordinator = coordinator,
        )
        runtime.dispatch(FavoritesIntent.ViewerContextChanged(scope))
        runtime.dispatch(FavoritesIntent.ScreenAppeared)
        advanceUntilIdle()

        assertEquals(listOf(FAVORITE_ID), runtime.state.value.items.map(FavoriteListingItem::id))
        assertTrue(interactions.drainCalls >= 3)
        runtime.close()
    }
}

private fun TestScope.settleCoordinatorBackgroundWork() {
    runCurrent()
    advanceUntilIdle()
}

private fun TestScope.assertSingleFailedOverflowReconciliation(
    harness: DurableFavoritesHarness,
    interactions: DurableInteractionRepository,
    hydrationCallsBeforeBurst: Int,
) {
    settleCoordinatorBackgroundWork()
    val failedHydrationCalls = interactions.hydrationRequests.size - hydrationCallsBeforeBurst
    assertEquals(1, failedHydrationCalls)
    val retryableSignal = requireNotNull(harness.coordinator.reconciliationSignals.value)
    assertTrue(retryableSignal.requiresPendingValidation)
    assertFalse(InteractionReconciliationConsumer.Favorites in retryableSignal.acknowledgedConsumers)
    runCurrent()
    assertEquals(
        hydrationCallsBeforeBurst + failedHydrationCalls,
        interactions.hydrationRequests.size,
    )
}

private fun assertRetryableFavoriteReconciliation(
    harness: DurableFavoritesHarness,
    pages: DurableFavoritesRepository,
    expectedRefreshCalls: Int,
) {
    assertTrue(harness.visibleIds().isEmpty())
    assertTrue(harness.runtime.state.value.removingListingIds.isEmpty())
    val signal = requireNotNull(harness.coordinator.reconciliationSignals.value)
    assertFalse(InteractionReconciliationConsumer.Favorites in signal.acknowledgedConsumers)
    assertEquals(expectedRefreshCalls, pages.listCalls)
}

private data class BlockedFavoritesEventCollector(
    private val releaseGate: CompletableDeferred<Unit>,
    private val job: Job,
) {
    fun release() {
        releaseGate.complete(Unit)
    }

    fun close() {
        release()
        job.cancel()
    }
}

private suspend fun TestScope.blockFavoritesEventCollector(
    coordinator: InteractionCoordinator,
): BlockedFavoritesEventCollector {
    val release = CompletableDeferred<Unit>()
    val job = backgroundScope.launch {
        coordinator.events.collect { release.await() }
    }
    runCurrent()
    return BlockedFavoritesEventCollector(release, job)
}

private data class BlockedFavoritesHydration(val gate: CompletableDeferred<Unit>)

private suspend fun TestScope.blockFavoritesHydration(
    harness: DurableFavoritesHarness,
    interactions: DurableInteractionRepository,
): BlockedFavoritesHydration {
    harness.runtime.dispatch(FavoritesIntent.RemoveFavorite(FAVORITE_ID))
    advanceUntilIdle()
    val pending = interactions.pendingFor(FAVORITE_ID)
    val gate = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    interactions.loadPendingGate = gate
    interactions.loadPendingStarted = started
    interactions.enqueue(
        InteractionOperationOutcome.Superseded(
            command = pending.toCommand(A_INTERACTION_SCOPE),
            operationId = pending.operationId,
        ),
    )
    harness.coordinator.onForeground()
    runCurrent()
    assertTrue(started.isCompleted)
    return BlockedFavoritesHydration(gate)
}

private suspend fun TestScope.spamQueuedFavoriteEvents(coordinator: InteractionCoordinator, count: Int) {
    repeat(count) {
        submitQueuedFavoriteRemoval(coordinator, OVERTAKE_FAVORITE_ID)
        yield()
    }
    runCurrent()
}

private fun queuedFavoriteEvent(
    deliverySequence: Long,
    scope: InteractionAccountScope = A_INTERACTION_SCOPE,
): InteractionCoordinatorEvent.Queued {
    val command = InteractionCommand(
        scope = scope,
        listingId = OVERTAKE_FAVORITE_ID,
        kind = InteractionKind.Favorite,
        desiredSelected = false,
    )
    return InteractionCoordinatorEvent.Queued(
        scope = scope,
        deliverySequence = deliverySequence,
        command = command,
        pending = pending(
            operationId = deliverySequence,
            accountId = scope.accountId,
            listingId = OVERTAKE_FAVORITE_ID,
            selected = false,
        ),
    )
}

private fun reconciliationSignal(scope: InteractionAccountScope = A_INTERACTION_SCOPE, deliveryWatermark: Long) =
    InteractionReconciliationSignal(
        scope = scope,
        revision = 1L,
        stateVersion = 1L,
        deliveryWatermark = deliveryWatermark,
        terminalWatermarks = emptyMap(),
        status = InteractionReconciliationStatus(
            requiresPendingValidation = true,
            acknowledgedConsumers = emptySet(),
        ),
    )

private suspend fun TestScope.triggerDroppedFavoriteRejection(
    harness: DurableFavoritesHarness,
    interactions: DurableInteractionRepository,
    pages: DurableFavoritesRepository,
): Int {
    val target = submitQueuedFavoriteRemoval(harness.coordinator, OVERTAKE_FAVORITE_ID)
    runCurrent()
    val rejection = InteractionOperationOutcome.Rejected(
        command = target.command,
        operationId = target.pending.operationId,
        reason = InteractionRejectionReason.PermissionDenied,
    )
    interactions.enqueue(
        *(overflowFavoriteConfirmations(A_INTERACTION_SCOPE, count = 101) + rejection).toTypedArray(),
    )
    val refreshCalls = pages.listCalls
    interactions.loadPendingFailuresRemaining = 3
    harness.coordinator.onForeground()
    runCurrent()
    assertTrue(harness.coordinator.reconciliationSignals.value != null)
    return refreshCalls
}

private suspend fun submitQueuedFavoriteRemoval(
    coordinator: InteractionCoordinator,
    listingId: String,
): InteractionSubmitOutcome.Queued = assertIs(
    assertIs<DomainResult.Success<InteractionSubmitOutcome>>(
        coordinator.submit(
            A_INTERACTION_SCOPE,
            listingId,
            InteractionKind.Favorite,
            desiredSelected = false,
        ),
    ).value,
)

private suspend fun InteractionCoordinator.acknowledgeAllReconciliationConsumers() {
    deliveryCommitGate.acknowledgeReconciliation(
        requireNotNull(reconciliationSignals.value),
        InteractionReconciliationConsumer.Explore,
    )
    deliveryCommitGate.acknowledgeReconciliation(
        requireNotNull(reconciliationSignals.value),
        InteractionReconciliationConsumer.Favorites,
    )
}

private suspend fun TestScope.durableHarness(
    pages: DurableFavoritesRepository,
    interactions: DurableInteractionRepository,
): DurableFavoritesHarness {
    val tracker = ViewerSessionScopeTracker()
    val scope = tracker.update(ACCOUNT_ID_A, accountSetupComplete = true)
    val coordinator = InteractionCoordinator(interactions, tracker, DurableClock, backgroundScope)
    val runtime = FavoritesRuntime(
        presenter = FavoritesPresenter(pages),
        strings = durableStrings,
        coroutineScope = this,
        interactionCoordinator = coordinator,
    )
    runtime.dispatch(FavoritesIntent.ViewerContextChanged(scope))
    runtime.dispatch(FavoritesIntent.ScreenAppeared)
    advanceUntilIdle()
    return DurableFavoritesHarness(runtime, coordinator, tracker)
}

private suspend fun TestScope.queueRepeatedFavoriteAddition(
    harness: DurableFavoritesHarness,
    interactions: DurableInteractionRepository,
    previous: PendingInteraction,
): PendingInteraction {
    harness.coordinator.submit(A_INTERACTION_SCOPE, FAVORITE_ID, InteractionKind.Favorite, desiredSelected = true)
    advanceUntilIdle()
    val current = interactions.pendingFor(FAVORITE_ID)
    assertTrue(current.operationId > previous.operationId)
    val repeated = harness.coordinator.submit(
        A_INTERACTION_SCOPE,
        FAVORITE_ID,
        InteractionKind.Favorite,
        desiredSelected = true,
    )
    val queued = assertIs<InteractionSubmitOutcome.Queued>(
        assertIs<DomainResult.Success<InteractionSubmitOutcome>>(repeated).value,
    )
    assertEquals(current.operationId, queued.pending.operationId)
    advanceUntilIdle()
    return current
}

private suspend fun TestScope.publishDurableOutcome(
    harness: DurableFavoritesHarness,
    interactions: DurableInteractionRepository,
    outcome: InteractionOperationOutcome,
) {
    interactions.enqueue(outcome)
    harness.coordinator.onForeground()
    settleCoordinatorBackgroundWork()
}

private data class DurableFavoritesHarness(
    val runtime: FavoritesRuntime,
    val coordinator: InteractionCoordinator,
    val tracker: ViewerSessionScopeTracker,
) {
    fun visibleIds(): List<String> = runtime.state.value.items.map(FavoriteListingItem::id)

    fun close() {
        runtime.close()
    }
}

private class DurableFavoritesRepository(
    initialFavoriteIds: List<String> = emptyList(),
    private val pages: ArrayDeque<FavoriteListingPage>? = null,
) : FavoritesRepository {
    val serverFavoriteIds = initialFavoriteIds.toMutableList()
    val legacyMutationRequests = mutableListOf<Pair<String, Boolean>>()
    var listCalls = 0
        private set

    override suspend fun listFavorites(
        filter: ListingType?,
        page: ListingPageRequest,
    ): DomainResult<FavoriteListingPage> {
        listCalls += 1
        val queuedPage = pages?.removeFirstOrNull()
        val result = queuedPage ?: favoritePage(serverFavoriteIds, nextCursor = null)
        return DomainResult.Success(result)
    }

    override suspend fun setFavorite(listingId: String, favorited: Boolean): DomainResult<FavoriteMutation> {
        legacyMutationRequests += listingId to favorited
        return DomainResult.Failure(DomainError.Unexpected())
    }
}

private class DurableInteractionRepository : InteractionRepository {
    private val pendingByKey = mutableMapOf<Pair<String, InteractionKind>, PendingInteraction>()
    private val drainQueue = ArrayDeque<List<InteractionOperationOutcome>>()
    private var nextOperationId = 0L
    var submitFailure: DomainError? = null
    var loadPendingFailuresRemaining: Int = 0
    var loadPendingGate: CompletableDeferred<Unit>? = null
    var loadPendingStarted: CompletableDeferred<Unit>? = null
    var drainCalls: Int = 0
        private set
    val hydrationRequests = mutableListOf<List<String>>()
    val retryCalls = mutableListOf<Pair<InteractionAccountScope, Boolean>>()

    override suspend fun submit(command: InteractionCommand): DomainResult<InteractionSubmitOutcome> {
        submitFailure?.let { error -> return DomainResult.Failure(error) }
        val key = command.listingId to command.kind
        val current = pendingByKey[key]
        val operation = if (current?.desiredSelected == command.desiredSelected) {
            current
        } else {
            pending(
                operationId = ++nextOperationId,
                accountId = command.scope.accountId,
                listingId = command.listingId,
                selected = command.desiredSelected,
            )
        }
        pendingByKey[key] = operation
        return DomainResult.Success(InteractionSubmitOutcome.Queued(command, operation))
    }

    override suspend fun loadPending(
        accountId: String,
        listingIds: List<String>,
    ): DomainResult<List<PendingInteraction>> {
        hydrationRequests += listingIds
        loadPendingGate?.also { gate ->
            loadPendingGate = null
            loadPendingStarted?.complete(Unit)
            loadPendingStarted = null
            gate.await()
        }
        if (loadPendingFailuresRemaining > 0) {
            loadPendingFailuresRemaining -= 1
            return DomainResult.Failure(DomainError.LocalStorageUnavailable())
        }
        return DomainResult.Success(
            pendingByKey.values.filter { pending ->
                pending.accountId == accountId &&
                    (listingIds.isEmpty() || pending.listingId in listingIds)
            },
        )
    }

    override suspend fun drainDue(scope: InteractionAccountScope): DomainResult<InteractionDrainOutcome> {
        drainCalls += 1
        val outcomes = drainQueue.removeFirstOrNull().orEmpty()
        outcomes.forEach(::applyOutcome)
        return DomainResult.Success(InteractionDrainOutcome(scope = scope, operations = outcomes))
    }

    override suspend fun nextAttemptAt(accountId: String): DomainResult<Long?> = DomainResult.Success(null)

    override suspend fun retryAccount(
        scope: InteractionAccountScope,
        includeManualFailures: Boolean,
    ): DomainResult<Int> {
        retryCalls += scope to includeManualFailures
        return DomainResult.Success(0)
    }

    override suspend fun purge(accountId: String): DomainResult<Int> {
        val keys = pendingByKey.filterValues { pending -> pending.accountId == accountId }.keys
        keys.forEach(pendingByKey::remove)
        return DomainResult.Success(keys.size)
    }

    fun putPending(pending: PendingInteraction) {
        nextOperationId = maxOf(nextOperationId, pending.operationId)
        pendingByKey[pending.listingId to pending.kind] = pending
    }

    fun pendingFor(listingId: String): PendingInteraction =
        requireNotNull(pendingByKey[listingId to InteractionKind.Favorite])

    fun enqueue(vararg outcomes: InteractionOperationOutcome) {
        drainQueue += outcomes.toList()
    }

    private fun applyOutcome(outcome: InteractionOperationOutcome) {
        when (outcome) {
            is InteractionOperationOutcome.Confirmed -> removeIfCurrent(
                outcome.command,
                outcome.confirmation.operationId,
            )
            is InteractionOperationOutcome.Rejected -> removeIfCurrent(outcome.command, outcome.operationId)
            is InteractionOperationOutcome.Retrying -> putPending(outcome.pending)
            is InteractionOperationOutcome.Superseded -> Unit
        }
    }

    private fun removeIfCurrent(command: InteractionCommand, operationId: Long) {
        val key = command.listingId to command.kind
        if (pendingByKey[key]?.operationId == operationId) pendingByKey.remove(key)
    }
}

private object DurableClock : ClockProvider {
    override fun nowEpochMilliseconds(): Long = TEST_NOW
}

private fun pending(
    operationId: Long,
    accountId: String = ACCOUNT_ID_A,
    listingId: String = FAVORITE_ID,
    attemptCount: Int = 0,
    selected: Boolean,
): PendingInteraction = PendingInteraction(
    operationId = operationId,
    accountId = accountId,
    listingId = listingId,
    kind = InteractionKind.Favorite,
    desiredSelected = selected,
    enqueuedAtEpochMilliseconds = TEST_NOW,
    attemptCount = attemptCount,
    status = PendingInteractionStatus.Scheduled(TEST_NOW),
)

private fun PendingInteraction.toCommand(scope: InteractionAccountScope): InteractionCommand = InteractionCommand(
    scope = scope,
    listingId = listingId,
    kind = kind,
    desiredSelected = desiredSelected,
)

private fun PendingInteraction.confirmed(selected: Boolean, sequence: Long): InteractionOperationOutcome.Confirmed {
    val scope = InteractionAccountScope(accountId = accountId, epoch = A_INTERACTION_SCOPE.epoch)
    return InteractionOperationOutcome.Confirmed(
        command = toCommand(scope).copy(desiredSelected = selected),
        confirmation = InteractionConfirmation.Favorite(
            operationId = operationId,
            scope = scope,
            listingId = listingId,
            favorited = selected,
            favoritedAtEpochMilliseconds = TEST_NOW.takeIf { selected },
            clientMutationSequence = sequence,
        ),
    )
}

private fun overflowFavoriteConfirmations(
    scope: InteractionAccountScope,
    count: Int,
): List<InteractionOperationOutcome> = (1..count).map { index ->
    val listingId = "overflow-favorite-$index"
    val command = InteractionCommand(
        scope = scope,
        listingId = listingId,
        kind = InteractionKind.Favorite,
        desiredSelected = true,
    )
    InteractionOperationOutcome.Confirmed(
        command = command,
        confirmation = InteractionConfirmation.Favorite(
            operationId = 1_000L + index,
            scope = scope,
            listingId = listingId,
            favorited = true,
            favoritedAtEpochMilliseconds = TEST_NOW,
            clientMutationSequence = 1_000L + index,
        ),
    )
}

private fun overflowLikeConfirmations(scope: InteractionAccountScope, count: Int): List<InteractionOperationOutcome> =
    (1..count).map { index ->
        val listingId = "overflow-like-$index"
        val command = InteractionCommand(
            scope = scope,
            listingId = listingId,
            kind = InteractionKind.Like,
            desiredSelected = true,
        )
        InteractionOperationOutcome.Confirmed(
            command = command,
            confirmation = InteractionConfirmation.Like(
                operationId = 2_000L + index,
                scope = scope,
                listingId = listingId,
                liked = true,
                likesCount = index,
                mutatedAtEpochMilliseconds = TEST_NOW,
            ),
        )
    }

private fun favoritePage(ids: List<String>, nextCursor: String?): FavoriteListingPage = FavoriteListingPage(
    items = ids.map { id -> favoriteListing(id = id) },
    nextCursor = nextCursor,
)

private fun listingIds(from: Int, through: Int): List<String> = (from..through).map { index ->
    "listing-${index.toString().padStart(3, '0')}"
}

private const val ACCOUNT_ID_A = "account-a"
private const val ACCOUNT_ID_B = "account-b"
private const val ACCOUNT_B_FAVORITE_ID = "account-b-favorite"
private const val OVERTAKE_FAVORITE_ID = "overtake-favorite"
private const val TEST_NOW = 1_000L
private val A_INTERACTION_SCOPE = InteractionAccountScope(accountId = ACCOUNT_ID_A, epoch = 1L)
private val B_INTERACTION_SCOPE = InteractionAccountScope(accountId = ACCOUNT_ID_B, epoch = 2L)
