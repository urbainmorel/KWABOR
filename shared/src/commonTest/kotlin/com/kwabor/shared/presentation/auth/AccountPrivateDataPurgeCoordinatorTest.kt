package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AccountPrivateDataPurgeRepository
import com.kwabor.shared.domain.auth.AccountPrivateDataPurgeResult
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountPrivateDataPurgeCoordinatorTest {
    @Test
    fun waitsForBothParticipantsBeforeOneDurablePurgeThenInvalidatesAndAcquiresInOrder() = runTest {
        val log = mutableListOf<String>()
        val interactionIdle = CompletableDeferred<Unit>()
        val notificationIdle = CompletableDeferred<Unit>()
        val interaction = FakePurgeParticipant("interaction", log, interactionIdle)
        val notification = FakePurgeParticipant("notification", log, notificationIdle)
        val repository = FakePrivateDataPurgeRepository(log)
        val coordinator = purgeCoordinator(repository, interaction, notification)

        val purge = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID) }
        runCurrent()
        assertFalse(repository.wasCalled)
        interactionIdle.complete(Unit)
        runCurrent()
        assertFalse(repository.wasCalled)
        notificationIdle.complete(Unit)
        runCurrent()

        val acquired = assertIs<AccountPrivateDataPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<AccountPrivateDataPurgeOutcome>>(purge.await()).value,
        )
        assertEquals(PURGE_RESULT, acquired.result)
        assertEquals(
            listOf(
                "interaction.register",
                "notification.register",
                "repository.purge",
                "interaction.invalidate",
                "notification.invalidate",
                "notification.finish:true",
                "interaction.finish:true",
            ),
            log,
        )
        assertTrue(interaction.blocked)
        assertTrue(notification.blocked)
    }

    @Test
    fun exactOwnershipResumesBothParticipantsOnceAndRejectsStaleOrDuplicateHandoffs() = runTest {
        val interaction = FakePurgeParticipant("interaction", mutableListOf())
        val notification = FakePurgeParticipant("notification", mutableListOf())
        val coordinator = purgeCoordinator(FakePrivateDataPurgeRepository(), interaction, notification)
        val ownership = assertIs<AccountPrivateDataPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<AccountPrivateDataPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID),
            ).value,
        ).ownership

        assertFalse(
            coordinator.resumeAfterAccountDeletionFailure(
                AccountPrivateDataPurgeOwnership(ACCOUNT_ID),
            ),
        )
        assertTrue(coordinator.resumeAfterAccountDeletionFailure(ownership))
        assertFalse(coordinator.resumeAfterAccountDeletionFailure(ownership))
        assertEquals(1, interaction.resumeCount)
        assertEquals(1, notification.resumeCount)
        assertFalse(interaction.blocked)
        assertFalse(notification.blocked)
    }

    @Test
    fun repositoryFailureReleasesBothBlocksWithoutInvalidatingEitherParticipant() = runTest {
        val log = mutableListOf<String>()
        val interaction = FakePurgeParticipant("interaction", log)
        val notification = FakePurgeParticipant("notification", log)
        val coordinator = purgeCoordinator(
            repository = FakePrivateDataPurgeRepository(
                log = log,
                result = DomainResult.Failure(DomainError.LocalStorageUnavailable()),
            ),
            interaction = interaction,
            notification = notification,
        )

        assertIs<DomainResult.Failure>(coordinator.purgeForAccountDeletion(ACCOUNT_ID))

        assertEquals(
            listOf(
                "interaction.register",
                "notification.register",
                "repository.purge",
                "notification.finish:false",
                "interaction.finish:false",
            ),
            log,
        )
        assertFalse(interaction.blocked)
        assertFalse(notification.blocked)
    }

    @Test
    fun notificationRegistrationConflictRollsBackTheInteractionBlockWithoutTouchingStorage() = runTest {
        val log = mutableListOf<String>()
        val interaction = FakePurgeParticipant("interaction", log)
        val notification = FakePurgeParticipant("notification", log, initiallyBlocked = true)
        val repository = FakePrivateDataPurgeRepository(log)
        val coordinator = purgeCoordinator(repository, interaction, notification)

        assertIs<AccountPrivateDataPurgeOutcome.AlreadyBlocked>(
            assertIs<DomainResult.Success<AccountPrivateDataPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID),
            ).value,
        )

        assertEquals(
            listOf("interaction.register", "notification.register", "interaction.finish:false"),
            log,
        )
        assertFalse(repository.wasCalled)
        assertFalse(interaction.blocked)
    }

    @Test
    fun cancellationAtAcquiredHandoffTriggersOneLateResume() = runTest {
        val interaction = FakePurgeParticipant("interaction", mutableListOf())
        val notification = FakePurgeParticipant("notification", mutableListOf())
        val coordinator = purgeCoordinator(FakePrivateDataPurgeRepository(), interaction, notification)

        val caller = launch {
            val ownerJob = coroutineContext[Job] ?: error("Missing purge owner job")
            coordinator.purgeForAccountDeletion(ACCOUNT_ID) { ownerJob.cancel() }
        }
        caller.join()
        runCurrent()

        assertEquals(1, interaction.resumeCount)
        assertEquals(1, notification.resumeCount)
        assertFalse(interaction.blocked)
        assertFalse(notification.blocked)
    }

    @Test
    fun concurrentAttemptIsRejectedWhileTheFirstAttemptOwnsTheCompositeBlock() = runTest {
        val repositoryGate = CompletableDeferred<Unit>()
        val repository = FakePrivateDataPurgeRepository(gate = repositoryGate)
        val coordinator = purgeCoordinator(
            repository,
            FakePurgeParticipant("interaction", mutableListOf()),
            FakePurgeParticipant("notification", mutableListOf()),
        )
        val first = async { coordinator.purgeForAccountDeletion(ACCOUNT_ID) }
        runCurrent()

        assertIs<AccountPrivateDataPurgeOutcome.AlreadyBlocked>(
            assertIs<DomainResult.Success<AccountPrivateDataPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID),
            ).value,
        )
        repositoryGate.complete(Unit)
        assertIs<AccountPrivateDataPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<AccountPrivateDataPurgeOutcome>>(first.await()).value,
        )
        assertEquals(1, repository.callCount)
    }

    @Test
    fun invalidationFailureAfterCommitAutoResumesBothParticipantsBeforeReturningFailure() = runTest {
        val interaction = FakePurgeParticipant("interaction", mutableListOf())
        val notification = FakePurgeParticipant(
            name = "notification",
            log = mutableListOf(),
            invalidationFailuresRemaining = 1,
        )
        val coordinator = purgeCoordinator(FakePrivateDataPurgeRepository(), interaction, notification)

        assertIs<DomainResult.Failure>(coordinator.purgeForAccountDeletion(ACCOUNT_ID))

        assertFalse(interaction.blocked)
        assertFalse(notification.blocked)
        assertEquals(1, interaction.resumeCount)
        assertEquals(1, notification.resumeCount)
        assertIs<AccountPrivateDataPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<AccountPrivateDataPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID),
            ).value,
        )
    }

    @Test
    fun failedFinishReturnsRecoveryHandleAndRetrySettlesThenResumesExactOwnership() = runTest {
        val interaction = FakePurgeParticipant("interaction", mutableListOf())
        val notification = FakePurgeParticipant(
            name = "notification",
            log = mutableListOf(),
            committedFinishFailuresRemaining = 3,
        )
        val coordinator = purgeCoordinator(FakePrivateDataPurgeRepository(), interaction, notification)

        val recovery = assertIs<AccountPrivateDataPurgeOutcome.PostCommitRecoveryRequired>(
            assertIs<DomainResult.Success<AccountPrivateDataPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID),
            ).value,
        )
        assertFalse(interaction.blocked)
        assertTrue(notification.blocked)
        assertEquals(1, interaction.resumeCount)
        assertFalse(coordinator.resumeAfterAccountDeletionFailure(recovery.ownership))
        assertTrue(coordinator.resumeAfterAccountDeletionFailure(recovery.ownership))
        assertFalse(interaction.blocked)
        assertFalse(notification.blocked)
        assertEquals(1, interaction.resumeCount)
    }

    @Test
    fun partialResumeKeepsExactOwnershipUntilTheMissingParticipantSucceeds() = runTest {
        val interaction = FakePurgeParticipant("interaction", mutableListOf())
        val notification = FakePurgeParticipant(
            name = "notification",
            log = mutableListOf(),
            resumeFailuresRemaining = 1,
        )
        val coordinator = purgeCoordinator(FakePrivateDataPurgeRepository(), interaction, notification)
        val ownership = assertIs<AccountPrivateDataPurgeOutcome.Acquired>(
            assertIs<DomainResult.Success<AccountPrivateDataPurgeOutcome>>(
                coordinator.purgeForAccountDeletion(ACCOUNT_ID),
            ).value,
        ).ownership

        assertFalse(coordinator.resumeAfterAccountDeletionFailure(ownership))
        assertFalse(interaction.blocked)
        assertTrue(notification.blocked)
        assertEquals(1, interaction.resumeCount)
        assertTrue(coordinator.resumeAfterAccountDeletionFailure(ownership))
        assertEquals(1, interaction.resumeCount)
        assertEquals(1, notification.resumeCount)
    }

    private fun TestScope.purgeCoordinator(
        repository: AccountPrivateDataPurgeRepository,
        interaction: AccountPrivateDataPurgeParticipant,
        notification: AccountPrivateDataPurgeParticipant,
    ): AccountPrivateDataPurgeCoordinator = AccountPrivateDataPurgeCoordinator(
        repository = repository,
        interactionParticipant = interaction,
        notificationParticipant = notification,
        workerScope = backgroundScope,
    )
}

private class FakePrivateDataPurgeRepository(
    private val log: MutableList<String> = mutableListOf(),
    private val result: DomainResult<AccountPrivateDataPurgeResult> = DomainResult.Success(PURGE_RESULT),
    private val gate: CompletableDeferred<Unit>? = null,
) : AccountPrivateDataPurgeRepository {
    var callCount = 0
        private set
    val wasCalled: Boolean
        get() = callCount > 0

    override suspend fun purge(expectedAccountId: String): DomainResult<AccountPrivateDataPurgeResult> {
        assertEquals(ACCOUNT_ID, expectedAccountId)
        callCount += 1
        log += "repository.purge"
        gate?.await()
        return result
    }
}

private class FakePurgeParticipant(
    private val name: String,
    private val log: MutableList<String>,
    private val idle: CompletableDeferred<Unit>? = null,
    initiallyBlocked: Boolean = false,
    private var invalidationFailuresRemaining: Int = 0,
    private var committedFinishFailuresRemaining: Int = 0,
    private var resumeFailuresRemaining: Int = 0,
) : AccountPrivateDataPurgeParticipant {
    private var activeToken: FakePurgeBlockToken? = null
    var blocked: Boolean = initiallyBlocked
        private set
    var resumeCount: Int = 0
        private set

    override suspend fun register(accountId: String): AccountPrivateDataPurgeBlockRegistration {
        assertEquals(ACCOUNT_ID, accountId)
        log += "$name.register"
        if (blocked) return AccountPrivateDataPurgeBlockRegistration.AlreadyBlocked
        blocked = true
        val token = FakePurgeBlockToken()
        activeToken = token
        return AccountPrivateDataPurgeBlockRegistration.Owner(token, idle)
    }

    override suspend fun finish(token: AccountPrivateDataPurgeBlockToken, committed: Boolean): Boolean {
        log += "$name.finish:$committed"
        if (activeToken !== token) return false
        if (committed && committedFinishFailuresRemaining > 0) {
            committedFinishFailuresRemaining -= 1
            return false
        }
        activeToken = null
        if (!committed) blocked = false
        return true
    }

    override suspend fun invalidate(accountId: String) {
        assertEquals(ACCOUNT_ID, accountId)
        log += "$name.invalidate"
        if (invalidationFailuresRemaining > 0) {
            invalidationFailuresRemaining -= 1
            error("forced invalidation failure")
        }
    }

    override suspend fun resume(accountId: String): Boolean {
        assertEquals(ACCOUNT_ID, accountId)
        if (!blocked || activeToken != null) return false
        if (resumeFailuresRemaining > 0) {
            resumeFailuresRemaining -= 1
            return false
        }
        blocked = false
        resumeCount += 1
        log += "$name.resume"
        return true
    }
}

private class FakePurgeBlockToken : AccountPrivateDataPurgeBlockToken

private val PURGE_RESULT = AccountPrivateDataPurgeResult(
    interactionOperationCount = 1,
    notificationItemCount = 2,
    notificationSnapshotCount = 1,
    notificationOperationCount = 3,
    notificationPreferenceCount = 4,
)
private const val ACCOUNT_ID = "10000000-0000-4000-8000-000000000001"
