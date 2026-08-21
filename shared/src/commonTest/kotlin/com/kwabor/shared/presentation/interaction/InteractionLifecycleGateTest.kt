package com.kwabor.shared.presentation.interaction

import com.kwabor.shared.domain.interaction.InteractionAccountScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InteractionLifecycleGateTest {
    @Test
    fun identityLeasesCannotBeReleasedTwiceOrSignalIdleWhileAnotherLeaseIsActive() = runTest {
        val gate = InteractionLifecycleGate()
        val first = requireNotNull(gate.beginOperation(SCOPE, SCOPE))
        val second = requireNotNull(gate.beginOperation(SCOPE, SCOPE))
        val owner = assertIs<InteractionDeletionBlockRegistration.Owner>(
            gate.registerDeletionBlock(ACCOUNT_ID),
        )
        val idle = requireNotNull(owner.idle)

        gate.endOperation(first)
        assertFalse(idle.isCompleted)
        assertFailsWith<IllegalStateException> { gate.endOperation(first) }
        assertFalse(idle.isCompleted)

        gate.endOperation(second)
        assertTrue(idle.isCompleted)
        assertTrue(gate.finishDeletionBlock(owner.token, committed = true))
    }

    @Test
    fun queuedFenceCapturedBeforeACommittedPurgeIsStaleAfterSameScopeResume() = runTest {
        val gate = InteractionLifecycleGate()
        val captured = assertIs<InteractionQueuedCommandFence.Captured>(
            gate.captureQueuedCommandFence(SCOPE, SCOPE),
        )
        val owner = assertIs<InteractionDeletionBlockRegistration.Owner>(
            gate.registerDeletionBlock(ACCOUNT_ID),
        )
        assertTrue(gate.finishDeletionBlock(owner.token, committed = true))
        assertTrue(gate.resume(ACCOUNT_ID))
        val replacement = assertIs<InteractionQueuedCommandFence.Captured>(
            gate.captureQueuedCommandFence(SCOPE, SCOPE),
        )

        assertTrue(replacement.revision > captured.revision)
    }
}

private const val ACCOUNT_ID = "10000000-0000-4000-8000-000000000001"
private val SCOPE = InteractionAccountScope(accountId = ACCOUNT_ID, epoch = 1)
