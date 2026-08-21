package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationDrainOutcome
import com.kwabor.shared.domain.notification.NotificationKind
import com.kwabor.shared.domain.notification.NotificationSubmitOutcome
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.NotificationSyncRepository
import com.kwabor.shared.domain.notification.PendingNotificationSync
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRuntimeActionControllerTest {
    @Test
    fun saturatedEffectBufferNeverHoldsDeletionIdleAndClearsPendingTicket() =
        runTest {
            val harness = actionControllerHarness()
            val pendingDetail = requireNotNull(harness.session.pendingDetail)
            assertTrue(
                harness.effects.offer(
                    NotificationEffect.OpenNotificationPreferences(
                        pendingDetail.scope,
                        pendingDetail.lifecycleGeneration,
                    ),
                ),
            )
            val lease = requireNotNull(harness.coordinator.beginOperation(pendingDetail.scope))

            val published =
                harness.controller.tryPublishNavigationEffect(
                    NotificationEffect.OpenCatalogDetail(
                        "notification-a",
                        requireNotNull(pendingDetail.target),
                        pendingDetail.ticket,
                        pendingDetail.scope,
                        pendingDetail.lifecycleGeneration,
                    ),
                    pendingDetail.scope,
                    lease,
                )
            harness.coordinator.endOperation(lease)

            assertFalse(published)
            assertNull(harness.session.pendingDetail)
            val owner =
                kotlin.test.assertIs<NotificationDeletionBlockRegistration.Owner>(
                    harness.coordinator.registerAccountDeletionBlock(pendingDetail.scope.accountId),
                )
            assertTrue(owner.idle == null || owner.idle.isCompleted)
            assertTrue(harness.coordinator.finishAccountDeletionBlock(owner.token, committed = false))
            harness.coordinator.close()
        }
}

private fun TestScope.actionControllerHarness(): NotificationActionControllerHarness {
    val tracker = ViewerSessionScopeTracker()
    val viewer = tracker.update("account-a", accountSetupComplete = true)
    val scope = requireNotNull(viewer.toNotificationAccountScopeOrNull())
    val coordinator = NotificationSyncCoordinator(NoopNotificationSyncRepository, tracker, ActionClock, this)
    val stateStore = NotificationStateStore().also { store -> store.publishViewerScope(viewer) }
    val session =
        NotificationRuntimeSession(backgroundScope).also { runtimeSession ->
            runtimeSession.switchViewer(viewer, scope)
        }
    val context =
        NotificationRuntimeContext(
            Mutex(),
            stateStore,
            session,
            coordinator,
            ActionClock,
            NotificationPresenter(ActionClock),
        ).also { runtimeContext -> runtimeContext.advanceLifecycleGeneration() }
    val effects = NotificationRuntimeEffectQueue(capacity = 1)
    val target = NotificationTargetUiModel("listing-a", ListingType.Place, "Ganvié", "so_ava")
    val ticket = NotificationDetailTicket(1L)
    session.pendingDetail =
        NotificationPendingDetail(
            "notification-a",
            NotificationKind.Suggestion,
            "so_ava",
            target,
            ticket,
            scope,
            context.currentLifecycleGeneration(),
        )
    return NotificationActionControllerHarness(
        NotificationRuntimeActionController(context, NotificationRuntimePublisher(context), effects),
        coordinator,
        session,
        effects,
    )
}

private data class NotificationActionControllerHarness(
    val controller: NotificationRuntimeActionController,
    val coordinator: NotificationSyncCoordinator,
    val session: NotificationRuntimeSession,
    val effects: NotificationRuntimeEffectQueue,
)

private data object NoopNotificationSyncRepository : NotificationSyncRepository {
    override suspend fun submit(command: NotificationSyncCommand): DomainResult<NotificationSubmitOutcome> =
        error("No submit expected in the effect publication test.")

    override suspend fun loadPending(
        expectedScope: NotificationAccountScope,
    ): DomainResult<List<PendingNotificationSync>> = DomainResult.Success(emptyList())

    override suspend fun drainDue(expectedScope: NotificationAccountScope) =
        DomainResult.Success(NotificationDrainOutcome(expectedScope, emptyList()))

    override suspend fun nextAttemptAt(expectedScope: NotificationAccountScope) = DomainResult.Success<Long?>(null)

    override suspend fun retryAccount(
        expectedScope: NotificationAccountScope,
        includeManualFailures: Boolean,
    ) = DomainResult.Success(0)

}

private data object ActionClock : ClockProvider {
    override fun nowEpochMilliseconds(): Long = 2_000L
}
