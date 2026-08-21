package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationDrainOutcome
import com.kwabor.shared.domain.notification.NotificationPendingSyncStatus
import com.kwabor.shared.domain.notification.NotificationSubmitOutcome
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.NotificationSyncRepository
import com.kwabor.shared.domain.notification.PendingNotificationSync
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSyncCoordinatorTest {
    @Test
    fun successfulSubmitWakesDrainAndScheduledBackoffWakesAgain() =
        runTest {
            val tracker = ViewerSessionScopeTracker()
            val viewerScope = tracker.update(accountId = "account-a", accountSetupComplete = true)
            val scope = requireNotNull(viewerScope.toNotificationAccountScopeOrNull())
            val repository = RecordingNotificationSyncRepository(nextAttemptAt = TEST_NOW + 50L)
            val coordinator = NotificationSyncCoordinator(repository, tracker, FixedCoordinatorClock(TEST_NOW), this)

            val result = coordinator.submit(NotificationSyncCommand.MarkRead(scope, "notification-a"))
            runCurrent()

            assertIs<DomainResult.Success<NotificationSubmitOutcome>>(result)
            assertEquals(1, repository.drainCalls)
            advanceTimeBy(49L)
            runCurrent()
            assertEquals(1, repository.drainCalls)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, repository.drainCalls)
            coordinator.close()
        }

    @Test
    fun resumeAfterFailedDeletionAlwaysCreatesANewWake() =
        runTest {
            val tracker = ViewerSessionScopeTracker()
            val scope =
                requireNotNull(
                    tracker.update(
                        accountId = "account-a",
                        accountSetupComplete = true,
                    ).toNotificationAccountScopeOrNull(),
                )
            val repository = RecordingNotificationSyncRepository(nextAttemptAt = null)
            val coordinator = NotificationSyncCoordinator(repository, tracker, FixedCoordinatorClock(TEST_NOW), this)
            runCurrent()
            val baseline = repository.drainCalls
            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    coordinator.registerAccountDeletionBlock(scope.accountId),
                )
            coordinator.wake(NotificationWakeRetryMode.Automatic)
            runCurrent()
            assertEquals(baseline, repository.drainCalls)
            assertTrue(coordinator.finishAccountDeletionBlock(owner.token, committed = true))

            assertTrue(coordinator.resumeAfterAccountDeletionFailure(scope.accountId))
            coordinator.wake(NotificationWakeRetryMode.Automatic, scope)
            runCurrent()

            assertEquals(baseline + 1, repository.drainCalls)
            coordinator.close()
        }

    @Test
    fun compositeInvalidationCancelsAPreviouslyScheduledWake() =
        runTest {
            val tracker = ViewerSessionScopeTracker()
            val scope =
                requireNotNull(
                    tracker.update(
                        accountId = "account-a",
                        accountSetupComplete = true,
                    ).toNotificationAccountScopeOrNull(),
                )
            val repository = RecordingNotificationSyncRepository(nextAttemptAt = TEST_NOW + 100L)
            val coordinator = NotificationSyncCoordinator(repository, tracker, FixedCoordinatorClock(TEST_NOW), this)

            coordinator.wake(NotificationWakeRetryMode.Automatic)
            runCurrent()
            assertEquals(1, repository.drainCalls)
            coordinator.invalidateAfterCompositePurge(scope.accountId)
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, repository.drainCalls)
            coordinator.close()
        }
}

private class RecordingNotificationSyncRepository(
    private val nextAttemptAt: Long?,
) : NotificationSyncRepository {
    var drainCalls: Int = 0
        private set
    private var operationId = 0L
    private val pending = mutableListOf<PendingNotificationSync>()

    override suspend fun submit(command: NotificationSyncCommand): DomainResult<NotificationSubmitOutcome> {
        operationId += 1L
        val operation =
            PendingNotificationSync(
                operationId = operationId,
                command = command,
                enqueuedAtEpochMilliseconds = TEST_NOW,
                attemptCount = 0,
                status = NotificationPendingSyncStatus.Scheduled(TEST_NOW),
            )
        pending += operation
        return DomainResult.Success(NotificationSubmitOutcome.Queued(command, operation))
    }

    override suspend fun loadPending(
        expectedScope: NotificationAccountScope,
    ): DomainResult<List<PendingNotificationSync>> =
        DomainResult.Success(
            pending.filter { operation -> operation.command.scope == expectedScope },
        )

    override suspend fun drainDue(expectedScope: NotificationAccountScope): DomainResult<NotificationDrainOutcome> {
        drainCalls += 1
        return DomainResult.Success(NotificationDrainOutcome(expectedScope, emptyList()))
    }

    override suspend fun nextAttemptAt(expectedScope: NotificationAccountScope): DomainResult<Long?> =
        DomainResult.Success(nextAttemptAt)

    override suspend fun retryAccount(
        expectedScope: NotificationAccountScope,
        includeManualFailures: Boolean,
    ): DomainResult<Int> = DomainResult.Success(0)

}

private class FixedCoordinatorClock(private val now: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = now
}

private const val TEST_NOW = 1_000L
