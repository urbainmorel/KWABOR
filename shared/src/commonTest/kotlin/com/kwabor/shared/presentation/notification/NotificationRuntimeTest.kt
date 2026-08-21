package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationCachedInbox
import com.kwabor.shared.domain.notification.NotificationCachedPreferences
import com.kwabor.shared.domain.notification.NotificationContent
import com.kwabor.shared.domain.notification.NotificationDrainOutcome
import com.kwabor.shared.domain.notification.NotificationFamilyPreference
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxRepository
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationItemMutation
import com.kwabor.shared.domain.notification.NotificationKind
import com.kwabor.shared.domain.notification.NotificationListingTarget
import com.kwabor.shared.domain.notification.NotificationMarkAllReadConfirmation
import com.kwabor.shared.domain.notification.NotificationOfflineRepository
import com.kwabor.shared.domain.notification.NotificationPageRequest
import com.kwabor.shared.domain.notification.NotificationPendingSyncStatus
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences
import com.kwabor.shared.domain.notification.NotificationPreferencesRepository
import com.kwabor.shared.domain.notification.NotificationSubmitOutcome
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.NotificationSyncRepository
import com.kwabor.shared.domain.notification.PendingNotificationSync
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRuntimeTest {
    @Test
    fun snapshotBecomesSeenOnlyAfterTheExactRenderedSnapshotIntent() =
        runTest {
            val harness = runtimeHarness()
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            val before = harness.runtime.state.value
            assertEquals(1, before.badge.unseenCount)
            assertEquals(1, before.badge.unreadCount)

            harness.runtime.dispatch(
                NotificationIntent.SnapshotPresented(
                    scope = requireNotNull(before.accountScope),
                    snapshotSequence = requireNotNull(before.page.window.snapshotSequence),
                    presentationGeneration = before.presentationGeneration,
                ),
            )
            advanceUntilIdle()

            assertEquals(0, harness.runtime.state.value.badge.unseenCount)
            assertEquals(1, harness.runtime.state.value.badge.unreadCount)
            assertTrue(harness.sync.submitted.any { command -> command is NotificationSyncCommand.AdvanceSeenThrough })
            harness.close()
        }

    @Test
    fun detailConfirmationSurvivesRefreshButNotCompositeInvalidation() =
        runTest {
            val harness = runtimeHarness()
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            val open = openDetail(harness)

            harness.runtime.dispatch(NotificationIntent.Refresh)
            advanceUntilIdle()
            confirmDetail(harness, open)

            val secondOpen = openDetail(harness)

            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    harness.runtime.registerAccountDeletionBlock(open.scope.accountId),
                )
            owner.idle?.await()
            harness.runtime.invalidateAfterCompositePurge(open.scope.accountId)
            assertTrue(harness.runtime.finishAccountDeletionBlock(owner.token, committed = true))
            val staleAnalytics = async { harness.runtime.effects.first() }
            harness.runtime.dispatch(
                NotificationIntent.DetailSheetPresentationConfirmed(
                    ticket = secondOpen.ticket,
                    listingId = secondOpen.target.listingId,
                    scope = secondOpen.scope,
                    presentationGeneration = secondOpen.presentationGeneration,
                ),
            )
            runCurrent()
            assertFalse(staleAnalytics.isCompleted)
            staleAnalytics.cancel()
            harness.close()
        }

    @Test
    fun preferencesOpenDirectlyAndStorageUnavailableRemainsExplicit() =
        runTest {
            val harness = runtimeHarness(offlineRepository = null)
            advanceUntilIdle()
            val navigation = async { harness.runtime.effects.first() }

            harness.runtime.dispatch(NotificationIntent.OpenPreferences)
            advanceUntilIdle()
            assertIs<NotificationEffect.OpenNotificationPreferences>(navigation.await())

            harness.runtime.dispatch(NotificationIntent.PreferencesScreenAppeared)
            advanceUntilIdle()
            val preferences = harness.runtime.state.value.preferences
            assertEquals(NotificationPreferenceFamily.entries.size, preferences.entries.size)
            assertTrue(preferences.isLocalCacheUnavailable)
            harness.close()
        }

    @Test
    fun committedInvalidationThenExplicitResumeReactivatesTheSameViewer() =
        runTest {
            val harness = runtimeHarness()
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            val baseline = harness.inbox.listCalls
            val scope = requireNotNull(harness.runtime.state.value.accountScope)
            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    harness.runtime.registerAccountDeletionBlock(scope.accountId),
                )
            owner.idle?.await()
            harness.runtime.invalidateAfterCompositePurge(scope.accountId)
            assertTrue(harness.runtime.finishAccountDeletionBlock(owner.token, committed = true))

            assertTrue(harness.runtime.resumeAfterAccountDeletionFailure(scope.accountId))
            advanceUntilIdle()

            assertEquals(scope, harness.runtime.state.value.accountScope)
            assertTrue(harness.inbox.listCalls > baseline)
            harness.close()
        }

    @Test
    fun viewerBQueuedBeforeResumeWinsOverDeletedViewerA() =
        runTest {
            val harness = runtimeHarness()
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            val scopeA = requireNotNull(harness.runtime.state.value.accountScope)
            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    harness.runtime.registerAccountDeletionBlock(scopeA.accountId),
                )
            owner.idle?.await()
            harness.runtime.invalidateAfterCompositePurge(scopeA.accountId)
            assertTrue(harness.runtime.finishAccountDeletionBlock(owner.token, committed = true))
            val baselineAListCalls = harness.inbox.listedScopes.count { scope -> scope == scopeA }
            val viewerB = harness.tracker.update("account-b", accountSetupComplete = true)
            harness.runtime.dispatch(NotificationIntent.ViewerContextChanged(viewerB))

            assertTrue(harness.runtime.resumeAfterAccountDeletionFailure(scopeA.accountId))
            advanceUntilIdle()

            val scopeB = requireNotNull(viewerB.toNotificationAccountScopeOrNull())
            assertEquals(scopeB, harness.runtime.state.value.accountScope)
            assertTrue(scopeB in harness.inbox.listedScopes)
            assertEquals(baselineAListCalls, harness.inbox.listedScopes.count { scope -> scope == scopeA })
            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            advanceUntilIdle()
            assertEquals(
                1,
                harness.sync.submitted.count {
                        command ->
                    command is NotificationSyncCommand.Hide && command.scope == scopeB
                },
            )
            harness.close()
        }

    @Test
    fun queuedMutationFromBeforeInvalidationCannotRecreateTheOutbox() =
        runTest {
            val submitGate = CompletableDeferred<Unit>()
            val harness = runtimeHarness(submitGate = submitGate)
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            runCurrent()
            harness.sync.submitStarted.await()
            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            val scope = requireNotNull(harness.runtime.state.value.accountScope)
            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    harness.runtime.registerAccountDeletionBlock(scope.accountId),
                )
            submitGate.complete(Unit)
            requireNotNull(owner.idle).await()
            harness.runtime.invalidateAfterCompositePurge(scope.accountId)
            assertTrue(harness.runtime.finishAccountDeletionBlock(owner.token, committed = true))
            assertTrue(harness.runtime.resumeAfterAccountDeletionFailure(scope.accountId))
            advanceUntilIdle()

            assertEquals(1, harness.sync.submitted.count { command -> command is NotificationSyncCommand.Hide })
            harness.close()
        }

    @Test
    fun queuedViewerChangeToBSurvivesInvalidationOfA() =
        runTest {
            val harness = runtimeHarness()
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            val scopeA = requireNotNull(harness.runtime.state.value.accountScope)
            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    harness.runtime.registerAccountDeletionBlock(scopeA.accountId),
                )
            val viewerB = harness.tracker.update("account-b", accountSetupComplete = true)

            harness.runtime.dispatch(NotificationIntent.ViewerContextChanged(viewerB))
            harness.runtime.invalidateAfterCompositePurge(scopeA.accountId)
            assertTrue(harness.runtime.finishAccountDeletionBlock(owner.token, committed = true))
            advanceUntilIdle()

            val scopeB = requireNotNull(viewerB.toNotificationAccountScopeOrNull())
            assertEquals(scopeB, harness.runtime.state.value.accountScope)
            assertTrue(scopeB in harness.inbox.listedScopes)
            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            advanceUntilIdle()
            assertEquals(
                1,
                harness.sync.submitted.count {
                        command ->
                    command is NotificationSyncCommand.Hide && command.scope == scopeB
                },
            )
            harness.close()
        }

    @Test
    fun invalidatingAWhileBIsActivePreservesQueuedBMutationAndBufferedEffect() =
        runTest {
            val submitGate = CompletableDeferred<Unit>()
            val harness = runtimeHarness(submitGate = submitGate)
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            val scopeA = requireNotNull(harness.runtime.state.value.accountScope)
            val viewerB = harness.tracker.update("account-b", accountSetupComplete = true)
            harness.runtime.dispatch(NotificationIntent.ViewerContextChanged(viewerB))
            advanceUntilIdle()
            val scopeB = requireNotNull(viewerB.toNotificationAccountScopeOrNull())
            harness.runtime.dispatch(NotificationIntent.OpenPreferences)
            advanceUntilIdle()
            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            runCurrent()
            harness.sync.submitStarted.await()
            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    harness.runtime.registerAccountDeletionBlock(scopeA.accountId),
                )

            harness.runtime.invalidateAfterCompositePurge(scopeA.accountId)
            assertTrue(harness.runtime.finishAccountDeletionBlock(owner.token, committed = true))
            assertTrue(harness.runtime.resumeAfterAccountDeletionFailure(scopeA.accountId))
            submitGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                2,
                harness.sync.submitted.count {
                        command ->
                    command is NotificationSyncCommand.Hide && command.scope == scopeB
                },
            )
            val effect = assertIs<NotificationEffect.OpenNotificationPreferences>(harness.runtime.effects.first())
            assertEquals(scopeB, effect.scope)
            harness.close()
        }

    @Test
    fun staleViewerBAfterAuthoritativeViewerCCannotRollBackTheRuntime() =
        runTest {
            val harness = runtimeHarness()
            val viewerB = harness.tracker.update("account-b", accountSetupComplete = true)
            harness.runtime.dispatch(NotificationIntent.ViewerContextChanged(viewerB))
            val viewerC = harness.tracker.update("account-c", accountSetupComplete = true)
            harness.runtime.dispatch(NotificationIntent.ViewerContextChanged(viewerC))
            harness.runtime.dispatch(NotificationIntent.ViewerContextChanged(viewerB))

            advanceUntilIdle()

            val scopeC = requireNotNull(viewerC.toNotificationAccountScopeOrNull())
            assertEquals(scopeC, harness.runtime.state.value.accountScope)
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            assertTrue(scopeC in harness.inbox.listedScopes)
            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            advanceUntilIdle()
            assertEquals(
                1,
                harness.sync.submitted.count {
                        command ->
                    command is NotificationSyncCommand.Hide && command.scope == scopeC
                },
            )
            harness.close()
        }

    @Test
    fun uppercaseViewerUsesLowercaseScopeAndLowercaseBlockCapturesInflightIo() =
        runTest {
            val harness = runtimeHarness(initialAccountId = "ACCOUNT-A")
            advanceUntilIdle()
            val request = harness.inbox.blockNextList()
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            runCurrent()
            request.started.await()
            val canonicalScope = requireNotNull(harness.runtime.state.value.accountScope)

            val owner =
                assertIs<NotificationDeletionBlockRegistration.Owner>(
                    harness.runtime.registerAccountDeletionBlock("account-a"),
                )
            val idle = requireNotNull(owner.idle)
            assertEquals("account-a", canonicalScope.accountId)
            assertFalse(idle.isCompleted)
            assertTrue(harness.coordinator.beginOperation(canonicalScope) == null)

            request.release.complete(Unit)
            idle.await()
            assertTrue(harness.runtime.finishAccountDeletionBlock(owner.token, committed = false))
            harness.close()
        }

    @Test
    fun sameScopeRefreshDoesNotInvalidateAMutationQueuedBehindABlockedSubmit() =
        runTest {
            val submitGate = CompletableDeferred<Unit>()
            val harness = runtimeHarness(submitGate = submitGate)
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            val refresh = harness.inbox.blockNextList()
            harness.runtime.dispatch(NotificationIntent.Refresh)
            runCurrent()
            refresh.started.await()

            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            runCurrent()
            harness.sync.submitStarted.await()
            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            val generationBeforeNetworkCommit = harness.runtime.state.value.presentationGeneration

            refresh.release.complete(Unit)
            runCurrent()
            assertTrue(harness.runtime.state.value.presentationGeneration > generationBeforeNetworkCommit)
            submitGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, harness.sync.submitted.count { command -> command is NotificationSyncCommand.Hide })
            harness.close()
        }

    @Test
    fun saturatedMutationQueuePublishesFeedbackAndRequestsReconciliation() =
        runTest {
            val submitGate = CompletableDeferred<Unit>()
            val harness = runtimeHarness(submitGate = submitGate)
            harness.runtime.dispatch(NotificationIntent.ScreenAppeared)
            advanceUntilIdle()
            val baselineRetryCalls = harness.sync.retryCalls
            harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            runCurrent()
            harness.sync.submitStarted.await()

            repeat(MAXIMUM_NOTIFICATION_RUNTIME_COMMANDS + 1) {
                harness.runtime.dispatch(NotificationIntent.HideNotification(TEST_NOTIFICATION_ID))
            }
            runCurrent()

            assertEquals(
                NotificationMessagePlacement.Mutation,
                requireNotNull(harness.runtime.state.value.page.message).placement,
            )
            assertTrue(harness.sync.retryCalls > baselineRetryCalls)
            submitGate.complete(Unit)
            advanceUntilIdle()
            harness.close()
        }

    private fun TestScope.runtimeHarness(
        offlineRepository: NotificationOfflineRepository? = InMemoryNotificationOfflineRepository(),
        submitGate: CompletableDeferred<Unit>? = null,
        initialAccountId: String = TEST_ACCOUNT_ID,
    ): NotificationRuntimeHarness {
        val tracker = ViewerSessionScopeTracker()
        tracker.update(initialAccountId, accountSetupComplete = true)
        val inbox = FakeNotificationInboxRepository()
        val preferences = FakeNotificationPreferencesRepository()
        val sync = FakeNotificationSyncRepository(submitGate)
        val coordinator = NotificationSyncCoordinator(sync, tracker, FixedRuntimeClock(TEST_NOW), this)
        val runtime =
            NotificationRuntime(
                NotificationRuntimeRepositories(inbox, preferences, offlineRepository),
                NotificationPresenter(FixedRuntimeClock(TEST_NOW)),
                FixedRuntimeClock(TEST_NOW),
                tracker,
                coordinator,
                this,
            )
        return NotificationRuntimeHarness(runtime, coordinator, inbox, sync, tracker)
    }

    private suspend fun TestScope.openDetail(
        harness: NotificationRuntimeHarness,
    ): NotificationEffect.OpenCatalogDetail {
        val openEffect = async { harness.runtime.effects.first() }
        harness.runtime.dispatch(NotificationIntent.OpenNotification(TEST_NOTIFICATION_ID))
        advanceUntilIdle()
        return assertIs(openEffect.await())
    }

    private suspend fun TestScope.confirmDetail(
        harness: NotificationRuntimeHarness,
        open: NotificationEffect.OpenCatalogDetail,
    ) {
        val analyticsEffect = async { harness.runtime.effects.first() }
        harness.runtime.dispatch(
            NotificationIntent.DetailSheetPresentationConfirmed(
                ticket = open.ticket,
                listingId = open.target.listingId,
                scope = open.scope,
                presentationGeneration = open.presentationGeneration,
            ),
        )
        advanceUntilIdle()
        assertIs<NotificationEffect.RecordOpenedAnalytics>(analyticsEffect.await())
    }
}

private data class NotificationRuntimeHarness(
    val runtime: NotificationRuntime,
    val coordinator: NotificationSyncCoordinator,
    val inbox: FakeNotificationInboxRepository,
    val sync: FakeNotificationSyncRepository,
    val tracker: ViewerSessionScopeTracker,
) {
    fun close() {
        runtime.close()
        coordinator.close()
    }
}

private class FakeNotificationInboxRepository : NotificationInboxRepository {
    var listCalls = 0
        private set
    val listedScopes = mutableListOf<NotificationAccountScope>()
    private val status = NotificationInboxStatus(1L, 0L, 1, 1)
    private var nextListBlock: NotificationRequestBlock? = null

    fun blockNextList(): NotificationRequestBlock =
        NotificationRequestBlock().also { block ->
            check(nextListBlock == null)
            nextListBlock = block
        }

    override suspend fun getStatus(expectedScope: NotificationAccountScope) = DomainResult.Success(status)

    override suspend fun listInbox(
        expectedScope: NotificationAccountScope,
        page: NotificationPageRequest,
    ) = nextListBlock.let { block ->
        nextListBlock = null
        block?.started?.complete(Unit)
        block?.release?.await()
    }.let {
        DomainResult.Success(
            NotificationInboxPage(
                items = listOf(notificationRuntimeItem()),
                snapshotSequence = 1L,
                nextCursor = null,
            ),
        ).also {
            listCalls += 1
            listedScopes += expectedScope
        }
    }

    override suspend fun markSeenThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ) = error("Runtime mutations must use the durable sync repository.")

    override suspend fun markRead(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ) = error("Runtime mutations must use the durable sync repository.")

    override suspend fun markAllReadThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ): DomainResult<NotificationMarkAllReadConfirmation> = error("Runtime mutations must use durable sync.")

    override suspend fun hide(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ): DomainResult<NotificationItemMutation> = error("Runtime mutations must use durable sync.")
}

private class NotificationRequestBlock {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

private class FakeNotificationPreferencesRepository : NotificationPreferencesRepository {
    override suspend fun getPreferences(expectedScope: NotificationAccountScope) =
        DomainResult.Success(enabledNotificationPreferences())

    override suspend fun setPreference(
        expectedScope: NotificationAccountScope,
        family: NotificationPreferenceFamily,
        enabled: Boolean,
    ): DomainResult<NotificationFamilyPreference> = error("Runtime preferences must use durable sync.")
}

private class FakeNotificationSyncRepository(
    private val submitGate: CompletableDeferred<Unit>?,
) : NotificationSyncRepository {
    val submitted = mutableListOf<NotificationSyncCommand>()
    val submitStarted = CompletableDeferred<Unit>()
    var retryCalls = 0
        private set
    private val pending = mutableListOf<PendingNotificationSync>()

    override suspend fun submit(command: NotificationSyncCommand): DomainResult<NotificationSubmitOutcome> {
        submitted += command
        submitStarted.complete(Unit)
        submitGate?.await()
        val operation =
            PendingNotificationSync(
                operationId = submitted.size.toLong(),
                command = command,
                enqueuedAtEpochMilliseconds = TEST_NOW,
                attemptCount = 0,
                status = NotificationPendingSyncStatus.Scheduled(TEST_NOW),
            )
        pending += operation
        return DomainResult.Success(NotificationSubmitOutcome.Queued(command, operation))
    }

    override suspend fun loadPending(expectedScope: NotificationAccountScope) =
        DomainResult.Success(pending.filter { operation -> operation.command.scope == expectedScope })

    override suspend fun drainDue(expectedScope: NotificationAccountScope) =
        DomainResult.Success(NotificationDrainOutcome(expectedScope, emptyList()))

    override suspend fun nextAttemptAt(expectedScope: NotificationAccountScope) = DomainResult.Success<Long?>(null)

    override suspend fun retryAccount(
        expectedScope: NotificationAccountScope,
        includeManualFailures: Boolean,
    ) = DomainResult.Success(0).also { retryCalls += 1 }

}

private class InMemoryNotificationOfflineRepository : NotificationOfflineRepository {
    private val inboxes = mutableMapOf<String, NotificationCachedInbox>()
    private val preferences = mutableMapOf<String, NotificationCachedPreferences>()

    override suspend fun readInbox(expectedScope: NotificationAccountScope) =
        DomainResult.Success(inboxes[expectedScope.accountId])

    override suspend fun replaceInbox(
        expectedScope: NotificationAccountScope,
        page: NotificationInboxPage,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox> =
        DomainResult.Success(
            NotificationCachedInbox(
                expectedScope.accountId,
                page.snapshotSequence ?: status.latestSequence,
                page.nextCursor,
                status,
                cachedAtEpochMilliseconds,
                page.items,
            ).also { cached -> inboxes[expectedScope.accountId] = cached },
        )

    override suspend fun appendInbox(
        expectedScope: NotificationAccountScope,
        expectedSnapshotSequence: Long,
        expectedNextCursor: String,
        page: NotificationInboxPage,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox> = error("The runtime fixture has one page.")

    override suspend fun storeStatus(
        expectedScope: NotificationAccountScope,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox> {
        val current = inboxes[expectedScope.accountId]
        return DomainResult.Success(
            NotificationCachedInbox(
                accountId = expectedScope.accountId,
                snapshotSequence = current?.snapshotSequence ?: status.latestSequence,
                nextCursor = current?.nextCursor,
                status = status,
                cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
                items = current?.items.orEmpty(),
            ).also { cached -> inboxes[expectedScope.accountId] = cached },
        )
    }

    override suspend fun applyItemMutation(
        expectedScope: NotificationAccountScope,
        mutation: NotificationItemMutation,
    ) = DomainResult.Success(false)

    override suspend fun applyMarkAllRead(
        expectedScope: NotificationAccountScope,
        confirmation: NotificationMarkAllReadConfirmation,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox> = error("Sync confirmation is not used by this runtime fixture.")

    override suspend fun readPreferences(expectedScope: NotificationAccountScope) =
        DomainResult.Success(
            preferences.getOrPut(expectedScope.accountId) {
                NotificationCachedPreferences(
                    expectedScope.accountId,
                    enabledNotificationPreferences(),
                    null,
                )
            },
        )

    override suspend fun replacePreferences(
        expectedScope: NotificationAccountScope,
        preferences: NotificationPreferences,
        cachedAtEpochMilliseconds: Long,
    ) = DomainResult.Success(
        NotificationCachedPreferences(expectedScope.accountId, preferences, cachedAtEpochMilliseconds)
            .also { cached -> this.preferences[expectedScope.accountId] = cached },
    )

}

private fun notificationRuntimeItem(): NotificationInboxItem =
    NotificationInboxItem(
        id = TEST_NOTIFICATION_ID,
        sequence = 1L,
        kind = NotificationKind.Suggestion,
        content =
            NotificationContent.Suggestion(
                titleKey = "notification.suggestion.title",
                bodyKey = "notification.suggestion.body",
                listingName = "Ganvié",
            ),
        target =
            NotificationListingTarget(
                listingId = "listing-a",
                listingType = ListingType.Place,
                listingName = "Ganvié",
                cityId = "so_ava",
                cityName = "Sô-Ava",
                coverImage = null,
                eventStartAtEpochMilliseconds = null,
            ),
        seenAtEpochMilliseconds = null,
        readAtEpochMilliseconds = null,
        hiddenAtEpochMilliseconds = null,
        createdAtEpochMilliseconds = 1_000L,
    )

private fun enabledNotificationPreferences(): NotificationPreferences =
    NotificationPreferences(
        NotificationPreferenceFamily.entries.map { family -> NotificationFamilyPreference(family, true, TEST_NOW) },
    )

private class FixedRuntimeClock(private val now: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = now
}

private const val TEST_ACCOUNT_ID = "account-a"
private const val TEST_NOTIFICATION_ID = "notification-a"
private const val TEST_NOW = 2_000L
