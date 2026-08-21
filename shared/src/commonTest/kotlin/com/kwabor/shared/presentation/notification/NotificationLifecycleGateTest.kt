package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationLifecycleGateTest {
    private val scope = NotificationAccountScope(accountId = "account-a", epoch = 8L)

    @Test
    fun deletionBlockCapturesInflightIdleRejectsNewWorkAndFencesAbaLease() =
        runTest {
            val gate = NotificationLifecycleGate()
            val lease = requireNotNull(gate.beginOperation(scope, scope))

            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    gate.registerDeletionBlock(scope.accountId),
                )
            assertFalse(requireNotNull(owner.idle).isCompleted)
            assertNull(gate.beginOperation(scope, scope))
            assertFalse(gate.isLeaseCurrent(lease, scope, scope))

            gate.endOperation(lease)
            assertTrue(owner.idle.isCompleted)
            assertTrue(gate.finishDeletionBlock(owner.token, committed = false))
            val replacement = requireNotNull(gate.beginOperation(scope, scope))
            assertFalse(gate.isLeaseCurrent(lease, scope, scope))
            assertTrue(gate.isLeaseCurrent(replacement, scope, scope))
            gate.endOperation(replacement)
        }

    @Test
    fun committedFinishIsExactTokenAndKeepsAccountBlockedUntilExplicitResume() =
        runTest {
            val gate = NotificationLifecycleGate()
            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    gate.registerDeletionBlock(scope.accountId),
                )

            assertTrue(gate.finishDeletionBlock(owner.token, committed = true))
            assertFalse(gate.finishDeletionBlock(owner.token, committed = true))
            assertNull(gate.beginOperation(scope, scope))
            assertTrue(gate.resume(scope.accountId))
            assertFalse(gate.resume(scope.accountId))

            val lease = requireNotNull(gate.beginOperation(scope, scope))
            gate.endOperation(lease)
        }

    @Test
    fun idleCompletesOnlyAfterEveryCapturedOperationEnds() =
        runTest {
            val gate = NotificationLifecycleGate()
            val first = requireNotNull(gate.beginOperation(scope, scope))
            val second = requireNotNull(gate.beginOperation(scope, scope))
            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    gate.registerDeletionBlock(scope.accountId),
                )
            val idle = requireNotNull(owner.idle)
            val waiter = async { idle.await() }

            gate.endOperation(first)
            runCurrent()
            assertFalse(waiter.isCompleted)
            gate.endOperation(second)
            runCurrent()

            assertTrue(waiter.isCompleted)
            assertTrue(gate.finishDeletionBlock(owner.token, committed = false))
        }

    @Test
    fun endedLeaseIsNeverCurrentWithoutADeletionGenerationChange() = runTest {
        val gate = NotificationLifecycleGate()
        val lease = requireNotNull(gate.beginOperation(scope, scope))
        assertTrue(gate.isLeaseCurrent(lease, scope, scope))

        gate.endOperation(lease)

        assertFalse(gate.isLeaseCurrent(lease, scope, scope))
    }

    @Test
    fun uppercaseOperationAndLowercaseDeletionShareOneCanonicalGate() =
        runTest {
            val gate = NotificationLifecycleGate()
            val uppercaseScope = NotificationAccountScope("ACCOUNT-A", scope.epoch)
            val lease = requireNotNull(gate.beginOperation(uppercaseScope, uppercaseScope))

            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    gate.registerDeletionBlock("account-a"),
                )

            assertFalse(requireNotNull(owner.idle).isCompleted)
            assertNull(gate.beginOperation(uppercaseScope, uppercaseScope))
            assertFalse(gate.isLeaseCurrent(lease, uppercaseScope, uppercaseScope))
            gate.endOperation(lease)
            assertTrue(owner.idle.isCompleted)
            assertTrue(gate.finishDeletionBlock(owner.token, committed = false))
        }
}
