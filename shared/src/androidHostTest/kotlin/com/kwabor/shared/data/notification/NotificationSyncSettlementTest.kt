package com.kwabor.shared.data.notification

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.data.local.KwaborDatabase
import com.kwabor.shared.data.local.KwaborDatabaseConstructor
import com.kwabor.shared.data.local.buildKwaborDatabase
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.ActiveNotificationAccountProvider
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationDrainOutcome
import com.kwabor.shared.domain.notification.NotificationFamilyPreference
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxRepository
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationItemMutation
import com.kwabor.shared.domain.notification.NotificationMarkAllReadConfirmation
import com.kwabor.shared.domain.notification.NotificationPageRequest
import com.kwabor.shared.domain.notification.NotificationPendingSyncStatus
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences
import com.kwabor.shared.domain.notification.NotificationPreferencesRepository
import com.kwabor.shared.domain.notification.NotificationSubmitOutcome
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.NotificationSyncOperationOutcome
import com.kwabor.shared.domain.notification.PendingNotificationSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NotificationSyncSettlementTest {
    @Test
    fun sameMillisecondConfirmedPreferenceWinsCachedValue() = runTest {
        withSettlementDatabase(coroutineContext) { database ->
            val preferencesRepository = SettlementPreferencesRepository { _, family, enabled ->
                DomainResult.Success(
                    NotificationFamilyPreference(
                        family = family,
                        enabled = enabled,
                        updatedAtEpochMilliseconds = SETTLEMENT_TIME,
                    ),
                )
            }
            val fixture = settlementFixture(
                database = database,
                workerScope = backgroundScope,
                preferencesRepository = preferencesRepository,
            )
            val cachedPreferences = NotificationPreferences(
                NotificationPreferences.disabled().entries.map { preference ->
                    if (preference.family == NotificationPreferenceFamily.EventAlert) {
                        preference.copy(enabled = true, updatedAtEpochMilliseconds = SETTLEMENT_TIME)
                    } else {
                        preference
                    }
                },
            )
            fixture.preferences.replacePreferences(
                SETTLEMENT_ACCOUNT_ID,
                cachedPreferences,
                SETTLEMENT_TIME,
            )
            val command = NotificationSyncCommand.SetFamilyEnabled(
                SETTLEMENT_SCOPE,
                NotificationPreferenceFamily.EventAlert,
                enabled = false,
            )
            assertIs<NotificationSubmitOutcome.Queued>(
                assertIs<DomainResult.Success<NotificationSubmitOutcome>>(fixture.sync.submit(command)).value,
            )

            val drain = assertIs<DomainResult.Success<NotificationDrainOutcome>>(
                fixture.sync.drainDue(SETTLEMENT_SCOPE),
            ).value

            assertIs<NotificationSyncOperationOutcome.Confirmed>(drain.operations.single())
            val persisted = fixture.preferences.readPreferences(SETTLEMENT_ACCOUNT_ID).preferences
                .preferenceFor(NotificationPreferenceFamily.EventAlert)
            assertFalse(persisted.enabled)
            assertEquals(SETTLEMENT_TIME, persisted.updatedAtEpochMilliseconds)
            assertTrue(fixture.outbox.listOperations(SETTLEMENT_ACCOUNT_ID).isEmpty())
        }
    }

    @Test
    fun supersededTruePreferenceConfirmationCannotOverwriteFalseReplacement() = runTest {
        withSettlementDatabase(coroutineContext) { database ->
            val trueRequestStarted = CompletableDeferred<Unit>()
            val releaseTrueRequest = CompletableDeferred<Unit>()
            val preferencesRepository = SettlementPreferencesRepository { _, family, enabled ->
                if (enabled) {
                    trueRequestStarted.complete(Unit)
                    releaseTrueRequest.await()
                }
                DomainResult.Success(
                    NotificationFamilyPreference(
                        family = family,
                        enabled = enabled,
                        updatedAtEpochMilliseconds = if (enabled) SETTLEMENT_TIME else SETTLEMENT_TIME + 1,
                    ),
                )
            }
            val fixture = settlementFixture(
                database = database,
                workerScope = backgroundScope,
                preferencesRepository = preferencesRepository,
            )
            val enable = NotificationSyncCommand.SetFamilyEnabled(
                SETTLEMENT_SCOPE,
                NotificationPreferenceFamily.EventAlert,
                enabled = true,
            )
            assertIs<NotificationSubmitOutcome.Queued>(
                assertIs<DomainResult.Success<NotificationSubmitOutcome>>(fixture.sync.submit(enable)).value,
            )
            val staleDrain = async { fixture.sync.drainDue(SETTLEMENT_SCOPE) }
            trueRequestStarted.await()

            val disable = enable.copy(enabled = false)
            assertIs<NotificationSubmitOutcome.Queued>(
                assertIs<DomainResult.Success<NotificationSubmitOutcome>>(fixture.sync.submit(disable)).value,
            )
            releaseTrueRequest.complete(Unit)

            val staleOutcome = assertIs<DomainResult.Success<NotificationDrainOutcome>>(staleDrain.await()).value
            assertIs<NotificationSyncOperationOutcome.Superseded>(staleOutcome.operations.single())
            assertFalse(
                fixture.preferences.readPreferences(SETTLEMENT_ACCOUNT_ID).preferences
                    .preferenceFor(NotificationPreferenceFamily.EventAlert).enabled,
            )
            val pending = assertIs<DomainResult.Success<List<PendingNotificationSync>>>(
                fixture.sync.loadPending(SETTLEMENT_SCOPE),
            ).value.single()
            assertFalse(assertIs<NotificationSyncCommand.SetFamilyEnabled>(pending.command).enabled)

            val confirmed = assertIs<DomainResult.Success<NotificationDrainOutcome>>(
                fixture.sync.drainDue(SETTLEMENT_SCOPE),
            ).value

            assertIs<NotificationSyncOperationOutcome.Confirmed>(confirmed.operations.single())
            assertFalse(
                fixture.preferences.readPreferences(SETTLEMENT_ACCOUNT_ID).preferences
                    .preferenceFor(NotificationPreferenceFamily.EventAlert).enabled,
            )
            assertTrue(fixture.outbox.listOperations(SETTLEMENT_ACCOUNT_ID).isEmpty())
        }
    }

    @Test
    fun uncachedItemPersistsAuthoritativeStatusBeforeDebtIsAcknowledged() = runTest {
        withSettlementDatabase(coroutineContext) { database ->
            val authoritativeStatus = settlementStatus(latest = 7, seenThrough = 0, unseen = 0, unread = 0)
            val inboxRepository = SettlementInboxRepository(
                onMarkRead = { _, notificationId ->
                    DomainResult.Success(
                        NotificationItemMutation(
                            notificationId = notificationId,
                            sequence = 7,
                            seenAtEpochMilliseconds = SETTLEMENT_TIME,
                            readAtEpochMilliseconds = SETTLEMENT_TIME,
                            hiddenAtEpochMilliseconds = null,
                        ),
                    )
                },
                onGetStatus = { DomainResult.Success(authoritativeStatus) },
            )
            val fixture = settlementFixture(database, backgroundScope, inboxRepository = inboxRepository)
            val command = NotificationSyncCommand.MarkRead(SETTLEMENT_SCOPE, SETTLEMENT_NOTIFICATION_ID)
            assertIs<DomainResult.Success<NotificationSubmitOutcome>>(fixture.sync.submit(command))

            val drain = assertIs<DomainResult.Success<NotificationDrainOutcome>>(
                fixture.sync.drainDue(SETTLEMENT_SCOPE),
            ).value

            assertIs<NotificationSyncOperationOutcome.Confirmed>(drain.operations.single())
            val cached = assertNotNull(fixture.inbox.readInbox(SETTLEMENT_ACCOUNT_ID))
            assertEquals(authoritativeStatus, cached.status)
            assertTrue(cached.items.isEmpty())
            assertTrue(fixture.outbox.listOperations(SETTLEMENT_ACCOUNT_ID).isEmpty())
        }
    }

    @Test
    fun persistenceFailureKeepsItemMutationDebt() = runTest {
        withSettlementDatabase(coroutineContext) { database ->
            val inboxRepository = SettlementInboxRepository(
                onMarkRead = { _, notificationId ->
                    DomainResult.Success(
                        NotificationItemMutation(
                            notificationId = notificationId,
                            sequence = 9,
                            seenAtEpochMilliseconds = SETTLEMENT_TIME,
                            readAtEpochMilliseconds = SETTLEMENT_TIME,
                            hiddenAtEpochMilliseconds = null,
                        ),
                    )
                },
                onGetStatus = {
                    DomainResult.Success(settlementStatus(latest = 9, seenThrough = 0, unseen = 0, unread = 0))
                },
            )
            val fixture = settlementFixture(database, backgroundScope, inboxRepository = inboxRepository)
            fixture.inbox.storeStatus(
                SETTLEMENT_ACCOUNT_ID,
                settlementStatus(latest = 10, seenThrough = 10, unseen = 0, unread = 0),
                SETTLEMENT_TIME,
            )
            val command = NotificationSyncCommand.MarkRead(SETTLEMENT_SCOPE, SETTLEMENT_NOTIFICATION_ID)
            assertIs<DomainResult.Success<NotificationSubmitOutcome>>(fixture.sync.submit(command))

            val failure = assertIs<DomainResult.Failure>(fixture.sync.drainDue(SETTLEMENT_SCOPE))

            assertIs<DomainError.LocalStorageUnavailable>(failure.error)
            assertEquals(1, fixture.outbox.listOperations(SETTLEMENT_ACCOUNT_ID).size)
            assertEquals(10L, assertNotNull(fixture.inbox.readInbox(SETTLEMENT_ACCOUNT_ID)).status.latestSequence)
        }
    }

    @Test
    fun loadPendingAndRetryAccountCoverTheFullOutboxCapacity() = runTest {
        withSettlementDatabase(coroutineContext) { database ->
            val fixture = settlementFixture(database, backgroundScope)
            repeat(DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS) { index ->
                fixture.outbox.enqueueMarkRead(
                    accountId = SETTLEMENT_ACCOUNT_ID,
                    notificationId = settlementNotificationId(index + 1),
                    enqueuedAtEpochMilliseconds = index.toLong(),
                )
            }

            val loaded = assertIs<DomainResult.Success<List<PendingNotificationSync>>>(
                fixture.sync.loadPending(SETTLEMENT_SCOPE),
            ).value
            assertEquals(DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS, loaded.size)
            fixture.outbox.listOperations(
                SETTLEMENT_ACCOUNT_ID,
                DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS,
            ).forEach { operation ->
                assertTrue(
                    fixture.settlement.recordOperationTerminalFailure(
                        accountId = SETTLEMENT_ACCOUNT_ID,
                        operationId = operation.operationId,
                        expectedAttemptCount = operation.attemptCount,
                        terminalErrorCode = NOTIFICATION_TERMINAL_SESSION,
                    ),
                )
            }

            val rearmed = assertIs<DomainResult.Success<Int>>(
                fixture.sync.retryAccount(SETTLEMENT_SCOPE, includeManualFailures = false),
            ).value

            assertEquals(DEFAULT_MAX_NOTIFICATION_SYNC_OPERATIONS, rearmed)
            assertTrue(
                assertIs<DomainResult.Success<List<PendingNotificationSync>>>(
                    fixture.sync.loadPending(SETTLEMENT_SCOPE),
                ).value.all { pending ->
                    pending.attemptCount == 0 && pending.status is NotificationPendingSyncStatus.Scheduled
                },
            )
        }
    }

    @Test
    fun sameHeadReplacementCannotRegressSeenThroughWatermark() = runTest {
        withSettlementDatabase(coroutineContext) { database ->
            val store = NotificationInboxStore(database.notificationInboxDao())
            store.replaceInbox(
                accountId = SETTLEMENT_ACCOUNT_ID,
                page = NotificationInboxPage(emptyList(), snapshotSequence = null, nextCursor = null),
                status = settlementStatus(latest = 10, seenThrough = 10, unseen = 0, unread = 0),
                cachedAtEpochMilliseconds = SETTLEMENT_TIME,
            )

            val replaced = store.replaceInbox(
                accountId = SETTLEMENT_ACCOUNT_ID,
                page = NotificationInboxPage(emptyList(), snapshotSequence = null, nextCursor = null),
                status = settlementStatus(latest = 10, seenThrough = 0, unseen = 10, unread = 10),
                cachedAtEpochMilliseconds = SETTLEMENT_TIME + 1,
            )

            assertEquals(10L, replaced.status.seenThroughSequence)
            assertEquals(0, replaced.status.unseenCount)
            assertEquals(0, replaced.status.unreadCount)
        }
    }

    @Test
    fun itemAndStatusDaoMutationRollsBackTogether() = runTest {
        withSettlementDatabase(coroutineContext) { database ->
            val store = NotificationInboxStore(database.notificationInboxDao())
            val item = notificationRow(sequence = 10).toDomain()
            store.replaceInbox(
                accountId = SETTLEMENT_ACCOUNT_ID,
                page = NotificationInboxPage(listOf(item), snapshotSequence = 10, nextCursor = null),
                status = settlementStatus(latest = 10, seenThrough = 0, unseen = 1, unread = 1),
                cachedAtEpochMilliseconds = SETTLEMENT_TIME,
            )
            val updatedItem = item.copy(
                seenAtEpochMilliseconds = SETTLEMENT_TIME,
                readAtEpochMilliseconds = SETTLEMENT_TIME,
            )

            assertFailsWith<IllegalStateException> {
                database.notificationInboxDao().applyItemMutation(
                    item = updatedItem.toNotificationEntity(SETTLEMENT_ACCOUNT_ID),
                    status = settlementStatus(10, 0, 0, 0).toStatusUpdate(
                        SETTLEMENT_OTHER_ACCOUNT_ID,
                        SETTLEMENT_TIME + 1,
                    ),
                )
            }

            val persisted = assertNotNull(store.readInbox(SETTLEMENT_ACCOUNT_ID)).items.single()
            assertNull(persisted.seenAtEpochMilliseconds)
            assertNull(persisted.readAtEpochMilliseconds)
        }
    }

    @Test
    fun confirmationTransactionRollsBackItemStatusAndKeepsDebtWhenPostWriteCheckFails() = runTest {
        withSettlementDatabase(coroutineContext) { database ->
            val lock = NotificationStoreLock()
            val inbox = NotificationInboxStore(database.notificationInboxDao(), lock)
            val outbox = NotificationOutboxStore(database.notificationOutboxDao(), lock)
            val item = notificationRow(sequence = 10).toDomain()
            inbox.replaceInbox(
                accountId = SETTLEMENT_ACCOUNT_ID,
                page = NotificationInboxPage(listOf(item), snapshotSequence = 10, nextCursor = null),
                status = settlementStatus(10, 0, 1, 1),
                cachedAtEpochMilliseconds = SETTLEMENT_TIME,
            )
            val operation = outbox.enqueueMarkRead(SETTLEMENT_ACCOUNT_ID, item.id, SETTLEMENT_TIME)
            val before = assertNotNull(inbox.readInbox(SETTLEMENT_ACCOUNT_ID))
            val updatedItem = item.copy(
                seenAtEpochMilliseconds = SETTLEMENT_TIME,
                readAtEpochMilliseconds = SETTLEMENT_TIME,
            )
            val forbiddenSnapshotWhenPresent = settlementStatus(10, 0, 0, 0).toSnapshotEntity(
                accountId = SETTLEMENT_ACCOUNT_ID,
                snapshotSequence = 10,
                nextCursor = null,
                cachedAtEpochMilliseconds = SETTLEMENT_TIME + 1,
                itemCount = 1,
            )

            assertFailsWith<IllegalStateException> {
                database.notificationConfirmationSettlementDao().settleItemAndStatus(
                    accountId = SETTLEMENT_ACCOUNT_ID,
                    operationId = operation.operationId,
                    expectedAttemptCount = operation.attemptCount.toLong(),
                    item = updatedItem.toNotificationEntity(SETTLEMENT_ACCOUNT_ID),
                    status = settlementStatus(10, 0, 0, 0).toStatusUpdate(
                        SETTLEMENT_ACCOUNT_ID,
                        SETTLEMENT_TIME + 1,
                    ),
                    snapshotWhenAbsent = forbiddenSnapshotWhenPresent,
                )
            }

            assertEquals(before, inbox.readInbox(SETTLEMENT_ACCOUNT_ID))
            assertEquals(operation, outbox.listOperations(SETTLEMENT_ACCOUNT_ID).single())
        }
    }

    @Test
    fun supersededTransactionDoesNotMutatePreferences() = runTest {
        withSettlementDatabase(coroutineContext) { database ->
            val lock = NotificationStoreLock()
            val preferences = NotificationPreferencesStore(database.notificationPreferencesDao(), lock)
            val outbox = NotificationOutboxStore(database.notificationOutboxDao(), lock)
            val stale = outbox.enqueueSetFamilyEnabled(
                SETTLEMENT_ACCOUNT_ID,
                NotificationPreferenceFamily.EventAlert,
                enabled = true,
                enqueuedAtEpochMilliseconds = SETTLEMENT_TIME,
            )
            val replacement = outbox.enqueueSetFamilyEnabled(
                SETTLEMENT_ACCOUNT_ID,
                NotificationPreferenceFamily.EventAlert,
                enabled = false,
                enqueuedAtEpochMilliseconds = SETTLEMENT_TIME + 1,
            )
            assertTrue(stale.operationId != replacement.operationId)
            val before = preferences.readPreferences(SETTLEMENT_ACCOUNT_ID)
            val enabled = before.preferences.copy(
                entries = before.preferences.entries.map { preference ->
                    if (preference.family == NotificationPreferenceFamily.EventAlert) {
                        preference.copy(enabled = true, updatedAtEpochMilliseconds = SETTLEMENT_TIME)
                    } else {
                        preference
                    }
                },
            ).toNotificationPreferenceEntities(SETTLEMENT_ACCOUNT_ID, SETTLEMENT_TIME)

            val settled = database.notificationConfirmationSettlementDao().settlePreferences(
                accountId = SETTLEMENT_ACCOUNT_ID,
                operationId = stale.operationId,
                expectedAttemptCount = stale.attemptCount.toLong(),
                preferences = enabled,
            )

            assertFalse(settled)
            assertEquals(before, preferences.readPreferences(SETTLEMENT_ACCOUNT_ID))
            val remaining = outbox.listOperations(SETTLEMENT_ACCOUNT_ID).single()
            assertEquals(replacement.operationId, remaining.operationId)
            assertEquals(replacement, remaining)
            assertFalse(outbox.listOperations(SETTLEMENT_ACCOUNT_ID).any { operation ->
                operation.operationId == stale.operationId
            })
        }
    }

}

private class SettlementFixture(
    database: KwaborDatabase,
    workerScope: CoroutineScope,
    inboxRepository: NotificationInboxRepository,
    preferencesRepository: NotificationPreferencesRepository,
) {
    private val lock = NotificationStoreLock()
    val inbox = NotificationInboxStore(database.notificationInboxDao(), lock)
    val preferences = NotificationPreferencesStore(database.notificationPreferencesDao(), lock)
    val outbox = NotificationOutboxStore(database.notificationOutboxDao(), lock)
    val settlement = NotificationOutboxSettlementStore(
        database.notificationOutboxDao(),
        database.notificationConfirmationSettlementDao(),
        lock,
    )
    private val provider = SettlementAccountProvider(SETTLEMENT_SCOPE)
    val sync = DataNotificationSyncRepository(
        outboxStore = outbox,
        settlementStore = settlement,
        drainSingleFlight = NotificationDrainSingleFlight(workerScope),
        dependencies = NotificationSyncDependencies(
            inboxRepository = inboxRepository,
            preferencesRepository = preferencesRepository,
            inboxStore = inbox,
            preferencesStore = preferences,
            activeAccountProvider = provider,
        ),
        clockProvider = object : ClockProvider {
            override fun nowEpochMilliseconds(): Long = SETTLEMENT_TIME
        },
    )
}

private fun settlementFixture(
    database: KwaborDatabase,
    workerScope: CoroutineScope,
    inboxRepository: NotificationInboxRepository = SettlementInboxRepository(),
    preferencesRepository: NotificationPreferencesRepository = SettlementPreferencesRepository(),
): SettlementFixture = SettlementFixture(database, workerScope, inboxRepository, preferencesRepository)

private class SettlementAccountProvider(
    private val scope: NotificationAccountScope,
) : ActiveNotificationAccountProvider {
    override fun currentScope(): NotificationAccountScope = scope
}

private class SettlementInboxRepository(
    private val onMarkRead: suspend (NotificationAccountScope, String) -> DomainResult<NotificationItemMutation> =
        { _, _ -> unusedSettlementCall() },
    private val onGetStatus: suspend (NotificationAccountScope) -> DomainResult<NotificationInboxStatus> =
        { unusedSettlementCall() },
) : NotificationInboxRepository {
    override suspend fun getStatus(expectedScope: NotificationAccountScope): DomainResult<NotificationInboxStatus> =
        onGetStatus(expectedScope)

    override suspend fun listInbox(
        expectedScope: NotificationAccountScope,
        page: NotificationPageRequest,
    ): DomainResult<NotificationInboxPage> = unusedSettlementCall()

    override suspend fun markSeenThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ): DomainResult<NotificationInboxStatus> = unusedSettlementCall()

    override suspend fun markRead(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ): DomainResult<NotificationItemMutation> = onMarkRead(expectedScope, notificationId)

    override suspend fun markAllReadThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ): DomainResult<NotificationMarkAllReadConfirmation> = unusedSettlementCall()

    override suspend fun hide(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ): DomainResult<NotificationItemMutation> = unusedSettlementCall()
}

private class SettlementPreferencesRepository(
    private val onSetPreference: suspend (
        NotificationAccountScope,
        NotificationPreferenceFamily,
        Boolean,
    ) -> DomainResult<NotificationFamilyPreference> = { _, _, _ -> unusedSettlementCall() },
) : NotificationPreferencesRepository {
    override suspend fun getPreferences(
        expectedScope: NotificationAccountScope,
    ): DomainResult<NotificationPreferences> = unusedSettlementCall()

    override suspend fun setPreference(
        expectedScope: NotificationAccountScope,
        family: NotificationPreferenceFamily,
        enabled: Boolean,
    ): DomainResult<NotificationFamilyPreference> = onSetPreference(expectedScope, family, enabled)
}

private fun settlementStatus(
    latest: Long,
    seenThrough: Long,
    unseen: Int,
    unread: Int,
): NotificationInboxStatus = NotificationInboxStatus(latest, seenThrough, unseen, unread)

private fun settlementNotificationId(index: Int): String =
    "40000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

private fun <T> unusedSettlementCall(): DomainResult<T> = DomainResult.Failure(DomainError.Unexpected())

private suspend fun withSettlementDatabase(
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

private val SETTLEMENT_SCOPE = NotificationAccountScope(SETTLEMENT_ACCOUNT_ID, epoch = 1)
private const val SETTLEMENT_ACCOUNT_ID = "10000000-0000-4000-8000-000000000001"
private const val SETTLEMENT_OTHER_ACCOUNT_ID = "10000000-0000-4000-8000-000000000002"
private const val SETTLEMENT_NOTIFICATION_ID = "20000000-0000-4000-8000-000000000001"
private const val SETTLEMENT_TIME = 1_786_356_100_000L
