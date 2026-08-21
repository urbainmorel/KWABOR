package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationDrainOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDrainSingleFlightTest {
    @Test
    fun cancelledWaiterDoesNotReturnBeforeSharedRequestAndFollowerSettle() = runTest {
        val singleFlight = NotificationDrainSingleFlight(backgroundScope)
        val remoteStarted = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()
        var invocationCount = 0
        val expected = NotificationDrainOutcome(SINGLE_FLIGHT_SCOPE, emptyList())
        val leader = async {
            singleFlight.execute(SINGLE_FLIGHT_SCOPE) {
                invocationCount += 1
                remoteStarted.complete(Unit)
                releaseRemote.await()
                expected
            }
        }
        remoteStarted.await()
        val follower = async {
            singleFlight.execute(SINGLE_FLIGHT_SCOPE) {
                error("A follower must join the active request.")
            }
        }

        leader.cancel()
        runCurrent()

        assertFalse(leader.isCompleted)
        assertFalse(follower.isCompleted)
        assertEquals(1, invocationCount)
        assertEquals(1, singleFlight.activeRequestCount())

        releaseRemote.complete(Unit)

        assertEquals(expected, follower.await())
        assertFailsWith<CancellationException> { leader.await() }
        runCurrent()
        assertEquals(0, singleFlight.activeRequestCount())
    }

    @Test
    fun newerEpochCancelsAndJoinsItsPredecessorBeforeStarting() = runTest {
        val singleFlight = NotificationDrainSingleFlight(backgroundScope)
        val predecessorStarted = CompletableDeferred<Unit>()
        val predecessorFinished = CompletableDeferred<Unit>()
        val successorStarted = CompletableDeferred<Unit>()
        val oldDrain = async {
            singleFlight.execute(SINGLE_FLIGHT_SCOPE) {
                try {
                    predecessorStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    predecessorFinished.complete(Unit)
                }
            }
        }
        predecessorStarted.await()
        val newerScope = SINGLE_FLIGHT_SCOPE.copy(epoch = SINGLE_FLIGHT_SCOPE.epoch + 1)
        val expected = NotificationDrainOutcome(newerScope, emptyList())
        val newDrain = async {
            singleFlight.execute(newerScope) {
                assertTrue(predecessorFinished.isCompleted)
                successorStarted.complete(Unit)
                expected
            }
        }

        successorStarted.await()

        assertEquals(expected, newDrain.await())
        assertFailsWith<CancellationException> { oldDrain.await() }
        runCurrent()
        assertEquals(0, singleFlight.activeRequestCount())
    }

    @Test
    fun differentAccountsRunConcurrently() = runTest {
        val singleFlight = NotificationDrainSingleFlight(backgroundScope)
        val accountAStarted = CompletableDeferred<Unit>()
        val accountBStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scopeB = NotificationAccountScope(SINGLE_FLIGHT_ACCOUNT_B, epoch = 1)
        val drainA = async {
            singleFlight.execute(SINGLE_FLIGHT_SCOPE) {
                accountAStarted.complete(Unit)
                release.await()
                NotificationDrainOutcome(SINGLE_FLIGHT_SCOPE, emptyList())
            }
        }
        val drainB = async {
            singleFlight.execute(scopeB) {
                accountBStarted.complete(Unit)
                release.await()
                NotificationDrainOutcome(scopeB, emptyList())
            }
        }

        accountAStarted.await()
        accountBStarted.await()

        assertEquals(2, singleFlight.activeRequestCount())
        release.complete(Unit)
        drainA.await()
        drainB.await()
        runCurrent()
        assertEquals(0, singleFlight.activeRequestCount())
    }

    @Test
    fun detachedWorkerFailureIsRethrownOnlyInTheAwaitingCaller() = runTest {
        val singleFlight = NotificationDrainSingleFlight(backgroundScope)

        val failure = assertFailsWith<IllegalStateException> {
            singleFlight.execute(SINGLE_FLIGHT_SCOPE) {
                error("expected worker failure")
            }
        }

        assertEquals("expected worker failure", failure.message)
        runCurrent()
        assertEquals(0, singleFlight.activeRequestCount())
    }
}

private val SINGLE_FLIGHT_SCOPE = NotificationAccountScope(
    accountId = "10000000-0000-4000-8000-000000000001",
    epoch = 1,
)
private const val SINGLE_FLIGHT_ACCOUNT_B = "10000000-0000-4000-8000-000000000002"
