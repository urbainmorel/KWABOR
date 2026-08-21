package com.kwabor.shared.presentation.notification

import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NotificationRuntimeCommandQueueTest {
    private val scope = NotificationAccountScope(accountId = "account-a", epoch = 2L)

    @Test
    fun lifecyclePageSyncAndPreferenceBurstsStayConflated() {
        val queue = NotificationRuntimeCommandQueue()

        repeat(10_000) { index ->
            queue.offer(intent(NotificationIntent.Foregrounded))
            queue.offer(intent(NotificationIntent.Refresh))
            queue.offer(
                intent(
                    NotificationIntent.SetPreference(
                        NotificationPreferenceFamily.Suggestion,
                        enabled = index % 2 == 0,
                    ),
                ),
            )
            queue.offer(
                NotificationRuntimeCommand.SyncChanged(
                    NotificationSyncSignal.Reconcile(
                        scope,
                        revision = index + 1L,
                        outcome = com.kwabor.shared.domain.notification.NotificationDrainOutcome(scope, emptyList()),
                    ),
                    runtimeGeneration = 4L,
                ),
            )
        }

        assertEquals(4, queue.pendingCount)
    }

    @Test
    fun nonConflatableMutationBacklogIsHardBoundedAndRejectsExplicitly() {
        val queue = NotificationRuntimeCommandQueue()
        repeat(MAXIMUM_NOTIFICATION_RUNTIME_COMMANDS) { index ->
            assertIs<NotificationCommandOfferResult.Accepted>(
                queue.offer(intent(NotificationIntent.HideNotification("notification-$index"))),
            )
        }

        val rejected = queue.offer(intent(NotificationIntent.MarkAllRead))

        assertIs<NotificationCommandOfferResult.Rejected>(rejected)
        assertEquals(MAXIMUM_NOTIFICATION_RUNTIME_COMMANDS, queue.pendingCount)
    }

    @Test
    fun navigationAndManualRetryAreNeverOverwrittenByLifecycleOrAppend() {
        val queue = NotificationRuntimeCommandQueue()
        queue.offer(intent(NotificationIntent.OpenPreferences))
        queue.offer(intent(NotificationIntent.PreferencesScreenAppeared))
        queue.offer(intent(NotificationIntent.PreferencesScreenDisappeared))
        queue.offer(intent(NotificationIntent.LoadNext))
        queue.offer(intent(NotificationIntent.Refresh))
        queue.offer(intent(NotificationIntent.Retry))

        val queued =
            buildList {
                var command = queue.take()
                while (command != null) {
                    add(command)
                    command = queue.take()
                }
            }.filterIsInstance<NotificationRuntimeCommand.Intent>().map { command -> command.intent }

        assertEquals(5, queued.size)
        assertEquals(1, queued.count { intent -> intent == NotificationIntent.OpenPreferences })
        assertEquals(1, queued.count { intent -> intent == NotificationIntent.PreferencesScreenDisappeared })
        assertEquals(1, queued.count { intent -> intent == NotificationIntent.LoadNext })
        assertEquals(1, queued.count { intent -> intent == NotificationIntent.Refresh })
        assertEquals(1, queued.count { intent -> intent == NotificationIntent.Retry })
    }

    @Test
    fun distinctSyncScopesCannotBypassTheHardCap() {
        val queue = NotificationRuntimeCommandQueue()
        var rejections = 0
        repeat(10_000) { index ->
            val distinctScope = NotificationAccountScope("account-$index", index.toLong())
            val result =
                queue.offer(
                    NotificationRuntimeCommand.SyncChanged(
                        NotificationSyncSignal.Reconcile(
                            distinctScope,
                            index + 1L,
                            com.kwabor.shared.domain.notification.NotificationDrainOutcome(distinctScope, emptyList()),
                        ),
                        runtimeGeneration = 4L,
                    ),
                )
            if (result is NotificationCommandOfferResult.Rejected) rejections += 1
        }

        assertEquals(MAXIMUM_NOTIFICATION_RUNTIME_COMMANDS, queue.pendingCount)
        assertEquals(10_000 - MAXIMUM_NOTIFICATION_RUNTIME_COMMANDS, rejections)
    }

    @Test
    fun viewerChangeEvictsOldGenerationAndIsAlwaysFirst() {
        val queue = NotificationRuntimeCommandQueue()
        repeat(MAXIMUM_NOTIFICATION_RUNTIME_COMMANDS) { index ->
            queue.offer(intent(NotificationIntent.HideNotification("notification-$index")))
        }
        val viewerScope = com.kwabor.shared.presentation.session.ViewerSessionScope("account-b", epoch = 9L)

        val offered = queue.offer(NotificationRuntimeCommand.ViewerChanged(viewerScope, runtimeGeneration = 5L))

        assertIs<NotificationCommandOfferResult.Accepted>(offered)
        val first = assertIs<NotificationRuntimeCommand.ViewerChanged>(queue.take())
        assertEquals(viewerScope, first.scope)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun accountClearPreservesAnotherAccountsViewerTransition() {
        val queue = NotificationRuntimeCommandQueue()
        val viewerB = ViewerSessionScope("account-b", epoch = 9L)
        queue.offer(NotificationRuntimeCommand.ViewerChanged(viewerB, runtimeGeneration = 5L))
        queue.offer(intent(NotificationIntent.HideNotification("notification-a"), generation = 5L))

        queue.clearAccount(scope.accountId)

        val retained = assertIs<NotificationRuntimeCommand.ViewerChanged>(queue.take())
        assertEquals(viewerB, retained.scope)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun olderViewerEpochCannotReplaceANewerQueuedViewer() {
        val queue = NotificationRuntimeCommandQueue()
        val viewerC = ViewerSessionScope("account-c", epoch = 10L)
        val viewerB = ViewerSessionScope("account-b", epoch = 9L)
        queue.offer(NotificationRuntimeCommand.ViewerChanged(viewerC, runtimeGeneration = 6L))

        queue.offer(NotificationRuntimeCommand.ViewerChanged(viewerB, runtimeGeneration = 7L))

        val retained = assertIs<NotificationRuntimeCommand.ViewerChanged>(queue.take())
        assertEquals(viewerC, retained.scope)
        assertEquals(0, queue.pendingCount)
    }

    private fun intent(
        intent: NotificationIntent,
        generation: Long = 4L,
    ): NotificationRuntimeCommand.Intent = NotificationRuntimeCommand.Intent(intent, scope, generation)
}
