package com.kwabor.shared.data.notification

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.data.local.KwaborDatabase
import com.kwabor.shared.data.local.KwaborDatabaseConstructor
import com.kwabor.shared.data.local.NotificationInboxSnapshotEntity
import com.kwabor.shared.data.local.buildKwaborDatabase
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationItemMutation
import com.kwabor.shared.domain.notification.NotificationMarkAllReadConfirmation
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NotificationStoreTest {
    @Test
    fun individuallySeenAndReadItemDoesNotRecreateUnseenBadgeAboveZeroWatermark() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val store = NotificationInboxStore(database.notificationInboxDao())
            val readItem = cachedNotificationItem(sequence = 10).copy(
                seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                readAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
            )

            val cached = store.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(listOf(readItem), snapshotSequence = 10),
                status = notificationStatus(latest = 10, seenThrough = 0, unseen = 0, unread = 0),
                cachedAtEpochMilliseconds = CACHE_TIME,
            )

            assertEquals(0, cached.status.unseenCount)
            assertEquals(0, cached.status.unreadCount)
            assertEquals(cached, store.readInbox(ACCOUNT_ID))
        }
    }

    @Test
    fun hideAndReadMutationsProjectCountsWithoutConflatingTheirTimestamps() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val store = NotificationInboxStore(database.notificationInboxDao())
            val item = cachedNotificationItem(sequence = 10)
            store.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(listOf(item), snapshotSequence = 10),
                status = notificationStatus(latest = 10, seenThrough = 0, unseen = 1, unread = 1),
                cachedAtEpochMilliseconds = CACHE_TIME,
            )

            assertTrue(
                store.applyItemMutation(
                    accountId = ACCOUNT_ID,
                    mutation = NotificationItemMutation(
                        notificationId = item.id,
                        sequence = 10,
                        seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                        readAtEpochMilliseconds = null,
                        hiddenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                    ),
                ),
            )
            val hidden = assertNotNull(store.readInbox(ACCOUNT_ID))
            assertEquals(0, hidden.status.unseenCount)
            assertEquals(0, hidden.status.unreadCount)
            assertNull(hidden.items.single().readAtEpochMilliseconds)
            assertNotNull(hidden.items.single().hiddenAtEpochMilliseconds)

            assertTrue(
                store.applyItemMutation(
                    accountId = ACCOUNT_ID,
                    mutation = NotificationItemMutation(
                        notificationId = item.id,
                        sequence = 10,
                        seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                        readAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                        hiddenAtEpochMilliseconds = null,
                    ),
                ),
            )
            val readAndHidden = assertNotNull(store.readInbox(ACCOUNT_ID)).items.single()
            assertNotNull(readAndHidden.readAtEpochMilliseconds)
            assertNotNull(readAndHidden.hiddenAtEpochMilliseconds)
        }
    }

    @Test
    fun statusRefreshAtTheSameBoundaryPreservesOptimisticHiddenCounters() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val store = NotificationInboxStore(database.notificationInboxDao())
            val item = cachedNotificationItem(sequence = 10)
            store.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(listOf(item), snapshotSequence = 10),
                status = notificationStatus(latest = 10, seenThrough = 0, unseen = 1, unread = 1),
                cachedAtEpochMilliseconds = CACHE_TIME,
            )
            assertTrue(
                store.applyItemMutation(
                    accountId = ACCOUNT_ID,
                    mutation = NotificationItemMutation(
                        notificationId = item.id,
                        sequence = item.sequence,
                        seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                        readAtEpochMilliseconds = null,
                        hiddenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                    ),
                ),
            )

            val refreshed = store.storeStatus(
                accountId = ACCOUNT_ID,
                status = notificationStatus(latest = 10, seenThrough = 0, unseen = 1, unread = 1),
                cachedAtEpochMilliseconds = CACHE_TIME + 1,
            )

            assertEquals(0, refreshed.status.unseenCount)
            assertEquals(0, refreshed.status.unreadCount)
        }
    }

    @Test
    fun multiDeviceTimestampMergeKeepsTheEarliestSeenBeforeRead() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val store = NotificationInboxStore(database.notificationInboxDao())
            val item = cachedNotificationItem(sequence = 10)
            store.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(
                    items = listOf(
                        item.copy(seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS + 1_000),
                    ),
                    snapshotSequence = 10,
                ),
                status = notificationStatus(latest = 10, seenThrough = 0, unseen = 0, unread = 1),
                cachedAtEpochMilliseconds = CACHE_TIME,
            )

            val merged = store.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(
                    items = listOf(
                        item.copy(
                            seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                            readAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                        ),
                    ),
                    snapshotSequence = 10,
                ),
                status = notificationStatus(latest = 10, seenThrough = 0, unseen = 0, unread = 0),
                cachedAtEpochMilliseconds = CACHE_TIME + 1,
            )

            assertEquals(STATE_TIMESTAMP_EPOCH_MILLISECONDS, merged.items.single().seenAtEpochMilliseconds)
            assertEquals(STATE_TIMESTAMP_EPOCH_MILLISECONDS, merged.items.single().readAtEpochMilliseconds)
        }
    }

    @Test
    fun cacheStopsAtTwoHundredWithoutEvictingOrPersistingAContinuationCursor() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val store = NotificationInboxStore(database.notificationInboxDao())
            val firstPrefix = (2L..200L).reversed().map(::cachedNotificationItem)
            store.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(firstPrefix, snapshotSequence = 200, nextCursor = "cursor-2"),
                status = notificationStatus(latest = 200, seenThrough = 0, unseen = 200, unread = 200),
                cachedAtEpochMilliseconds = CACHE_TIME,
            )

            val capped = store.appendInbox(
                accountId = ACCOUNT_ID,
                expectedSnapshotSequence = 200,
                expectedNextCursor = "cursor-2",
                page = notificationPage(
                    items = listOf(cachedNotificationItem(sequence = 1)),
                    snapshotSequence = 200,
                    nextCursor = "server-has-more",
                ),
                cachedAtEpochMilliseconds = CACHE_TIME + 1,
            )

            assertEquals(DEFAULT_MAX_NOTIFICATION_CACHE_ITEMS, capped.items.size)
            assertNull(capped.nextCursor)
            val beforeRejectedWrite = store.readInbox(ACCOUNT_ID)
            assertFailsWith<NotificationCacheCapacityExceededException> {
                store.replaceInbox(
                    accountId = ACCOUNT_ID,
                    page = notificationPage(
                        items = (1L..201L).reversed().map(::cachedNotificationItem),
                        snapshotSequence = 201,
                        nextCursor = "overflow",
                    ),
                    status = notificationStatus(latest = 201, seenThrough = 0, unseen = 201, unread = 201),
                    cachedAtEpochMilliseconds = CACHE_TIME + 2,
                )
            }
            assertEquals(beforeRejectedWrite, store.readInbox(ACCOUNT_ID))
        }
    }

    @Test
    fun readAndHideRemainIndependentOutboxOperationsInBothOrders() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val stores = notificationStores(database)
            val read = stores.outbox.enqueueMarkRead(ACCOUNT_ID, NOTIFICATION_ID, enqueuedAtEpochMilliseconds = 1)
            val hide = stores.outbox.enqueueHide(ACCOUNT_ID, NOTIFICATION_ID, enqueuedAtEpochMilliseconds = 2)

            assertEquals(
                setOf(NotificationSyncOperationKind.MarkRead, NotificationSyncOperationKind.Hide),
                stores.outbox.listOperations(ACCOUNT_ID).map(NotificationSyncOperation::kind).toSet(),
            )
            assertEquals(
                1,
                database.notificationOutboxDao().deleteOperationIfMatches(
                    ACCOUNT_ID,
                    read.operationId,
                    expectedAttemptCount = 0,
                    expectedTerminalErrorCode = null,
                ),
            )
            assertEquals(hide.operationId, stores.outbox.listOperations(ACCOUNT_ID).single().operationId)

            database.accountPrivateDataPurgeDao().purgeAccount(ACCOUNT_ID)
            val hideFirst = stores.outbox.enqueueHide(ACCOUNT_ID, NOTIFICATION_ID, enqueuedAtEpochMilliseconds = 3)
            val readSecond = stores.outbox.enqueueMarkRead(ACCOUNT_ID, NOTIFICATION_ID, enqueuedAtEpochMilliseconds = 4)
            assertEquals(2, stores.outbox.listOperations(ACCOUNT_ID).size)
            assertEquals(
                1,
                database.notificationOutboxDao().deleteOperationIfMatches(
                    ACCOUNT_ID,
                    hideFirst.operationId,
                    expectedAttemptCount = 0,
                    expectedTerminalErrorCode = null,
                ),
            )
            assertEquals(readSecond.operationId, stores.outbox.listOperations(ACCOUNT_ID).single().operationId)
        }
    }

    @Test
    fun retryTerminalFailureAndSettlementUseCompareAndSwap() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val stores = notificationStores(database)
            val operation = stores.outbox.enqueueMarkRead(ACCOUNT_ID, NOTIFICATION_ID, 1)

            assertTrue(stores.settlement.recordOperationRetry(ACCOUNT_ID, operation.operationId, 0, 2))
            assertFalse(stores.settlement.recordOperationRetry(ACCOUNT_ID, operation.operationId, 0, 3))
            assertFalse(stores.settlement.recordOperationRetry(ACCOUNT_ID_2, operation.operationId, 1, 3))
            assertTrue(
                stores.settlement.recordOperationTerminalFailure(
                    ACCOUNT_ID,
                    operation.operationId,
                    1,
                    "permission_denied",
                ),
            )
            assertFalse(
                stores.settlement.recordOperationTerminalFailure(
                    ACCOUNT_ID,
                    operation.operationId,
                    1,
                    "permission_denied",
                ),
            )
            assertTrue(stores.outbox.listReadyOperations(ACCOUNT_ID, readyAtEpochMilliseconds = 10).isEmpty())
            assertEquals(
                0,
                database.notificationOutboxDao().deleteOperationIfMatches(
                    ACCOUNT_ID,
                    operation.operationId,
                    expectedAttemptCount = 1,
                    expectedTerminalErrorCode = null,
                ),
            )
            assertEquals(
                0,
                database.notificationOutboxDao().deleteOperationIfMatches(
                    ACCOUNT_ID_2,
                    operation.operationId,
                    expectedAttemptCount = 2,
                    expectedTerminalErrorCode = null,
                ),
            )
            assertEquals(
                1,
                database.notificationOutboxDao().deleteOperationIfMatches(
                    accountId = ACCOUNT_ID,
                    operationId = operation.operationId,
                    expectedAttemptCount = 2L,
                    expectedTerminalErrorCode = "permission_denied",
                ),
            )
        }
    }

    @Test
    fun outboxCoalescesMonotoneBoundariesAndPreferenceLastStateWithinEachAccount() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val store = NotificationOutboxStore(database.notificationOutboxDao(), maxOperationCount = 3)
            val seenTen = store.enqueueAdvanceSeenThrough(ACCOUNT_ID, throughSequence = 10, enqueuedAtEpochMilliseconds = 1)
            val seenFive = store.enqueueAdvanceSeenThrough(ACCOUNT_ID, throughSequence = 5, enqueuedAtEpochMilliseconds = 2)
            val seenTwenty = store.enqueueAdvanceSeenThrough(ACCOUNT_ID, throughSequence = 20, enqueuedAtEpochMilliseconds = 3)
            val enabled = store.enqueueSetFamilyEnabled(
                ACCOUNT_ID,
                NotificationPreferenceFamily.Sponsored,
                enabled = true,
                enqueuedAtEpochMilliseconds = 4,
            )
            val disabled = store.enqueueSetFamilyEnabled(
                ACCOUNT_ID,
                NotificationPreferenceFamily.Sponsored,
                enabled = false,
                enqueuedAtEpochMilliseconds = 5,
            )

            assertEquals(seenTen.operationId, seenFive.operationId)
            assertTrue(seenTwenty.operationId != seenTen.operationId)
            assertTrue(disabled.operationId != enabled.operationId)
            assertEquals(2, store.listOperations(ACCOUNT_ID).size)
            assertEquals(20L, store.listOperations(ACCOUNT_ID).first { it.kind == NotificationSyncOperationKind.AdvanceSeenThrough }.throughSequence)
            assertFalse(
                store.listOperations(ACCOUNT_ID).first { it.kind == NotificationSyncOperationKind.SetFamilyEnabled }
                    .desiredEnabled ?: true,
            )

            store.enqueueMarkRead(ACCOUNT_ID, NOTIFICATION_ID, enqueuedAtEpochMilliseconds = 6)
            assertFailsWith<com.kwabor.shared.data.local.NotificationOutboxCapacityExceededException> {
                store.enqueueHide(ACCOUNT_ID, NOTIFICATION_ID, enqueuedAtEpochMilliseconds = 7)
            }
            store.enqueueHide(ACCOUNT_ID_2, NOTIFICATION_ID, enqueuedAtEpochMilliseconds = 8)
            assertEquals(1, store.listOperations(ACCOUNT_ID_2).size)
        }
    }

    @Test
    fun preferencesDefaultDisabledAndCompositePurgeRemovesOnlyTheRequestedAccount() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val stores = notificationStores(database)
            val defaults = stores.preferences.readPreferences(ACCOUNT_ID).preferences
            assertTrue(NotificationPreferenceFamily.entries.all { family -> !defaults.preferenceFor(family).enabled })
            stores.preferences.replacePreferences(
                accountId = ACCOUNT_ID,
                preferences = NotificationPreferences(
                    defaults.entries.map { preference ->
                        if (preference.family == NotificationPreferenceFamily.EventAlert) {
                            preference.copy(enabled = true, updatedAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS)
                        } else {
                            preference
                        }
                    },
                ),
                cachedAtEpochMilliseconds = CACHE_TIME,
            )
            stores.inbox.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(listOf(cachedNotificationItem(1)), snapshotSequence = 1),
                status = notificationStatus(1, 0, 1, 1),
                cachedAtEpochMilliseconds = CACHE_TIME,
            )
            stores.outbox.enqueueMarkRead(ACCOUNT_ID, NOTIFICATION_ID, enqueuedAtEpochMilliseconds = 1)
            stores.outbox.enqueueMarkRead(ACCOUNT_ID_2, NOTIFICATION_ID, enqueuedAtEpochMilliseconds = 1)

            val purge = database.accountPrivateDataPurgeDao().purgeAccount(ACCOUNT_ID)

            assertEquals(1, purge.itemCount)
            assertEquals(1, purge.snapshotCount)
            assertEquals(1, purge.operationCount)
            assertEquals(4, purge.preferenceCount)
            assertNull(stores.inbox.readInbox(ACCOUNT_ID))
            assertTrue(NotificationPreferenceFamily.entries.all { family ->
                !stores.preferences.readPreferences(ACCOUNT_ID).preferences.preferenceFor(family).enabled
            })
            assertEquals(1, stores.outbox.listOperations(ACCOUNT_ID_2).size)
        }
    }

    @Test
    fun staleInboxAndPreferenceResponsesCannotRegressNewerAccountState() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val stores = notificationStores(database)
            val newerItem = cachedNotificationItem(20).copy(
                seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                readAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
            )
            val newerInbox = stores.inbox.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(listOf(newerItem), snapshotSequence = 20),
                status = notificationStatus(20, 0, 0, 0),
                cachedAtEpochMilliseconds = CACHE_TIME + 2,
            )
            val sameSnapshot = stores.inbox.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(listOf(cachedNotificationItem(20)), snapshotSequence = 20),
                status = notificationStatus(20, 0, 1, 1),
                cachedAtEpochMilliseconds = CACHE_TIME + 3,
            )

            val staleInbox = stores.inbox.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(listOf(cachedNotificationItem(10)), snapshotSequence = 10),
                status = notificationStatus(10, 0, 1, 1),
                cachedAtEpochMilliseconds = CACHE_TIME + 4,
            )

            assertEquals(newerInbox.items, sameSnapshot.items)
            assertEquals(sameSnapshot, staleInbox)
            val defaults = NotificationPreferences.disabled()
            val newerPreferences = NotificationPreferences(
                defaults.entries.map { preference ->
                    if (preference.family == NotificationPreferenceFamily.Sponsored) {
                        preference.copy(enabled = true, updatedAtEpochMilliseconds = CACHE_TIME + 2)
                    } else {
                        preference
                    }
                },
            )
            stores.preferences.replacePreferences(ACCOUNT_ID, newerPreferences, CACHE_TIME + 2)
            val stalePreferences = NotificationPreferences(
                defaults.entries.map { preference ->
                    if (preference.family == NotificationPreferenceFamily.Sponsored) {
                        preference.copy(enabled = false, updatedAtEpochMilliseconds = CACHE_TIME + 1)
                    } else {
                        preference
                    }
                },
            )

            val merged = stores.preferences.replacePreferences(ACCOUNT_ID, stalePreferences, CACHE_TIME + 3)

            assertTrue(merged.preferences.preferenceFor(NotificationPreferenceFamily.Sponsored).enabled)
            assertEquals(
                CACHE_TIME + 2,
                merged.preferences.preferenceFor(NotificationPreferenceFamily.Sponsored).updatedAtEpochMilliseconds,
            )
        }
    }

    @Test
    fun sameMillisecondPreferenceRefreshKeepsCachedValue() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val store = NotificationPreferencesStore(database.notificationPreferencesDao())
            val cachedPreferences = NotificationPreferences(
                NotificationPreferences.disabled().entries.map { preference ->
                    if (preference.family == NotificationPreferenceFamily.Sponsored) {
                        preference.copy(enabled = true, updatedAtEpochMilliseconds = CACHE_TIME)
                    } else {
                        preference
                    }
                },
            )
            store.replacePreferences(ACCOUNT_ID, cachedPreferences, CACHE_TIME)
            val refreshedPreferences = NotificationPreferences(
                cachedPreferences.entries.map { preference ->
                    if (preference.family == NotificationPreferenceFamily.Sponsored) {
                        preference.copy(enabled = false)
                    } else {
                        preference
                    }
                },
            )

            val merged = store.replacePreferences(ACCOUNT_ID, refreshedPreferences, CACHE_TIME + 1)

            val persisted = merged.preferences.preferenceFor(NotificationPreferenceFamily.Sponsored)
            assertTrue(persisted.enabled)
            assertEquals(CACHE_TIME, persisted.updatedAtEpochMilliseconds)
        }
    }

    @Test
    fun markAllReadPersistsServerTimestampForCachedItemsThroughBoundary() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val store = NotificationInboxStore(database.notificationInboxDao())
            val items = listOf(
                cachedNotificationItem(4),
                cachedNotificationItem(3).copy(
                    seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS + 1_000,
                    readAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS + 1_000,
                ),
                cachedNotificationItem(2),
                cachedNotificationItem(1).copy(
                    seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                    hiddenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                ),
            )
            store.replaceInbox(
                accountId = ACCOUNT_ID,
                page = notificationPage(items, snapshotSequence = 4),
                status = notificationStatus(4, 0, 3, 3),
                cachedAtEpochMilliseconds = CACHE_TIME,
            )

            val updated = store.applyMarkAllRead(
                accountId = ACCOUNT_ID,
                confirmation = NotificationMarkAllReadConfirmation(
                    status = notificationStatus(4, 3, 1, 1),
                    throughSequence = 3,
                    mutationAtEpochMilliseconds = 0,
                ),
                cachedAtEpochMilliseconds = CACHE_TIME + 1,
            )

            assertNull(updated.items.first().readAtEpochMilliseconds)
            assertEquals(
                STATE_TIMESTAMP_EPOCH_MILLISECONDS + 1_000,
                updated.items[1].readAtEpochMilliseconds,
            )
            assertEquals(updated.items[2].createdAtEpochMilliseconds, updated.items[2].seenAtEpochMilliseconds)
            assertEquals(updated.items[2].createdAtEpochMilliseconds, updated.items[2].readAtEpochMilliseconds)
            assertNull(updated.items[3].readAtEpochMilliseconds)
            assertNotNull(updated.items[3].hiddenAtEpochMilliseconds)
            assertEquals(updated, store.readInbox(ACCOUNT_ID))
        }
    }

    @Test
    fun corruptSnapshotIsEvictedBeforeItCanReachTheDomain() = runTest {
        withNotificationDatabase(coroutineContext) { database ->
            val dao = database.notificationInboxDao()
            val store = NotificationInboxStore(dao, maxCachedItemCount = 2)
            val corruptSnapshots = listOf(
                corruptSnapshot(unseenCount = 2, unreadCount = 1),
                corruptSnapshot(latestSequence = 1, unseenCount = 0, unreadCount = 2),
                corruptSnapshot(nextCursor = "cursor-without-items"),
                corruptSnapshot(nextCursor = "cursor-at-cap", itemCount = 2),
            )

            corruptSnapshots.forEach { snapshot ->
                dao.insertSnapshot(snapshot)
                assertNull(store.readInbox(ACCOUNT_ID))
                assertNull(dao.findSnapshot(ACCOUNT_ID))
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NotificationStoreRestartTest {
    @Test
    fun inboxPreferencesAndRetryableDebtSurviveDatabaseRestart() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RESTART_DATABASE_NAME)
        try {
            val firstDatabase = namedNotificationDatabase(context, coroutineContext)
            val operation = try {
                val stores = notificationStores(firstDatabase)
                stores.inbox.replaceInbox(
                    accountId = ACCOUNT_ID,
                    page = notificationPage(listOf(cachedNotificationItem(1)), snapshotSequence = 1),
                    status = notificationStatus(1, 0, 1, 1),
                    cachedAtEpochMilliseconds = CACHE_TIME,
                )
                stores.preferences.replacePreferences(
                    accountId = ACCOUNT_ID,
                    preferences = NotificationPreferences.disabled(),
                    cachedAtEpochMilliseconds = CACHE_TIME,
                )
                stores.outbox.enqueueMarkRead(ACCOUNT_ID, NOTIFICATION_ID, 1).also { debt ->
                    assertTrue(stores.settlement.recordOperationRetry(ACCOUNT_ID, debt.operationId, 0, 50))
                }
            } finally {
                firstDatabase.close()
            }

            val reopenedDatabase = namedNotificationDatabase(context, coroutineContext)
            try {
                val restored = notificationStores(reopenedDatabase)
                assertEquals(1, assertNotNull(restored.inbox.readInbox(ACCOUNT_ID)).items.size)
                assertEquals(4, restored.preferences.readPreferences(ACCOUNT_ID).preferences.entries.size)
                val restoredOperation = restored.outbox.listOperations(ACCOUNT_ID).single()
                assertEquals(operation.operationId, restoredOperation.operationId)
                assertEquals(1, restoredOperation.attemptCount)
                assertEquals(50L, restoredOperation.nextAttemptAtEpochMilliseconds)
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            context.deleteDatabase(RESTART_DATABASE_NAME)
        }
    }
}

private fun cachedNotificationItem(sequence: Long): NotificationInboxItem {
    val id = "20000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}"
    return notificationRow(
        sequence = sequence,
        notificationId = id,
        rowCursor = "cursor-$sequence",
    ).copy(snapshotSequence = maxOf(SNAPSHOT_SEQUENCE, sequence)).toDomain()
}

private fun notificationPage(
    items: List<NotificationInboxItem>,
    snapshotSequence: Long,
    nextCursor: String? = null,
): NotificationInboxPage = NotificationInboxPage(
    items = items,
    snapshotSequence = snapshotSequence,
    nextCursor = nextCursor,
)

private fun notificationStatus(
    latest: Long,
    seenThrough: Long,
    unseen: Int,
    unread: Int,
): NotificationInboxStatus = NotificationInboxStatus(
    latestSequence = latest,
    seenThroughSequence = seenThrough,
    unseenCount = unseen,
    unreadCount = unread,
)

private suspend fun withNotificationDatabase(
    queryCoroutineContext: CoroutineContext,
    block: suspend (KwaborDatabase) -> Unit,
) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = buildKwaborDatabase(
        builder = Room.inMemoryDatabaseBuilder(
            context = context,
            factory = KwaborDatabaseConstructor::initialize,
        ),
        queryCoroutineContext = queryCoroutineContext,
        driver = AndroidSQLiteDriver(),
    )
    try {
        block(database)
    } finally {
        database.close()
    }
}

private fun namedNotificationDatabase(
    context: Context,
    queryCoroutineContext: CoroutineContext,
): KwaborDatabase = buildKwaborDatabase(
    builder = Room.databaseBuilder(
        context = context,
        name = RESTART_DATABASE_NAME,
        factory = KwaborDatabaseConstructor::initialize,
    ),
    queryCoroutineContext = queryCoroutineContext,
    driver = AndroidSQLiteDriver(),
)

private data class NotificationStores(
    val inbox: NotificationInboxStore,
    val preferences: NotificationPreferencesStore,
    val outbox: NotificationOutboxStore,
    val settlement: NotificationOutboxSettlementStore,
)

private fun notificationStores(database: KwaborDatabase): NotificationStores {
    val lock = NotificationStoreLock()
    return NotificationStores(
        inbox = NotificationInboxStore(database.notificationInboxDao(), lock),
        preferences = NotificationPreferencesStore(database.notificationPreferencesDao(), lock),
        outbox = NotificationOutboxStore(database.notificationOutboxDao(), lock),
        settlement = NotificationOutboxSettlementStore(
            database.notificationOutboxDao(),
            database.notificationConfirmationSettlementDao(),
            lock,
        ),
    )
}

private fun corruptSnapshot(
    latestSequence: Long = 1,
    unseenCount: Long = 0,
    unreadCount: Long = 0,
    nextCursor: String? = null,
    itemCount: Long = 0,
): NotificationInboxSnapshotEntity = NotificationInboxSnapshotEntity(
    accountId = ACCOUNT_ID,
    snapshotSequence = latestSequence,
    nextCursor = nextCursor,
    latestSequence = latestSequence,
    confirmedSeenThroughSequence = 0,
    unseenCount = unseenCount,
    unreadCount = unreadCount,
    cachedAtEpochMilliseconds = CACHE_TIME,
    itemCount = itemCount,
)

private const val ACCOUNT_ID_2 = "10000000-0000-4000-8000-000000000002"
private const val CACHE_TIME = 1_786_356_100_000L
private const val RESTART_DATABASE_NAME = "kwabor-notification-restart-test"
