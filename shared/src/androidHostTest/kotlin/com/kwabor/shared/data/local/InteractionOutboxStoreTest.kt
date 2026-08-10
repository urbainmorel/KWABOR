package com.kwabor.shared.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class InteractionOutboxStoreTest {
    @Test
    fun enqueueCoalescesSameDesiredStateAndRenewsIdentityOnlyWhenDesiredStateChanges() = runTest {
        withOutboxDatabase(coroutineContext) { database ->
            val dao = database.interactionOutboxDao()
            val store = InteractionOutboxStore(dao)
            val initial = store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, true, OUTBOX_TIME_10)
            assertEquals(
                1L,
                dao.findByKey(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like.storedValue)?.desiredSelectedRaw,
            )
            assertTrue(
                store.recordRetry(initial.operationId, expectedAttemptCount = 0, nextAttemptAtEpochMilliseconds = 30),
            )

            val sameDesired = store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, true, OUTBOX_TIME_20)
            val changedDesired = store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, false, OUTBOX_TIME_40)

            assertEquals(initial.operationId, sameDesired.operationId)
            assertEquals(1, sameDesired.attemptCount)
            assertEquals(OUTBOX_TIME_10, sameDesired.enqueuedAtEpochMilliseconds)
            assertNotEquals(initial.operationId, changedDesired.operationId)
            assertFalse(changedDesired.desiredSelected)
            assertEquals(
                0L,
                dao.findByKey(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like.storedValue)?.desiredSelectedRaw,
            )
            assertEquals(0, changedDesired.attemptCount)
            assertEquals(OUTBOX_TIME_40, changedDesired.nextAttemptAtEpochMilliseconds)
            assertEquals(listOf(changedDesired), store.listForAccount(ACCOUNT_A))
        }
    }

    @Test
    fun retryTerminalRearmAndSuccessUseCompareAndSwapSemantics() = runTest {
        withOutboxDatabase(coroutineContext) { database ->
            val store = InteractionOutboxStore(database.interactionOutboxDao())
            val operation = store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Favorite, true, OUTBOX_TIME_10)

            assertTrue(store.recordRetry(operation.operationId, 0, OUTBOX_TIME_20))
            assertFalse(store.recordRetry(operation.operationId, 0, OUTBOX_TIME_30))
            assertEquals(OUTBOX_TIME_20, store.nextAttemptAtForAccount(ACCOUNT_A))
            assertTrue(store.recordTerminalFailure(operation.operationId, 1, "permission_denied"))
            assertFalse(store.recordTerminalFailure(operation.operationId, 1, "permission_denied"))
            assertTrue(store.listReadyForAccount(ACCOUNT_A, readyAtEpochMilliseconds = 100).isEmpty())
            assertEquals(null, store.nextAttemptAtForAccount(ACCOUNT_A))
            assertEquals(operation.operationId, store.listPausedForAccount(ACCOUNT_A).single().operationId)
            assertEquals(
                operation.operationId,
                store.listPausedForAccount(ACCOUNT_A, terminalErrorCode = "permission_denied").single().operationId,
            )
            assertTrue(store.listPausedForAccount(ACCOUNT_A, terminalErrorCode = "session").isEmpty())
            assertFalse(
                store.rearm(operation.operationId, expectedDesiredSelected = false, rearmedAtEpochMilliseconds = 40),
            )
            assertTrue(
                store.rearm(operation.operationId, expectedDesiredSelected = true, rearmedAtEpochMilliseconds = 40),
            )
            assertFalse(
                store.deleteIfOperationMatches(
                    operationId = operation.operationId,
                    expectedAttemptCount = 2,
                    expectedTerminalErrorCode = "permission_denied",
                ),
            )

            val rearmed = store.listReadyForAccount(ACCOUNT_A, readyAtEpochMilliseconds = 40).single()
            assertEquals(operation.operationId, rearmed.operationId)
            assertEquals(0, rearmed.attemptCount)
            assertEquals(null, rearmed.terminalErrorCode)
            assertTrue(store.listPausedForAccount(ACCOUNT_A).isEmpty())
            assertTrue(store.deleteIfOperationMatches(operation.operationId))
            assertFalse(store.deleteIfOperationMatches(operation.operationId))
        }
    }

    @Test
    fun accountScopedReadsAreDeterministicAndNeverExposeAnotherAccount() = runTest {
        withOutboxDatabase(coroutineContext) { database ->
            val store = InteractionOutboxStore(database.interactionOutboxDao())
            val laterEnqueued = store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, true, OUTBOX_TIME_20)
            val sameDeadline = store.enqueue(ACCOUNT_A, LISTING_D, InteractionOutboxKind.Like, true, OUTBOX_TIME_20)
            val delayed = store.enqueue(ACCOUNT_A, LISTING_B, InteractionOutboxKind.Favorite, true, OUTBOX_TIME_10)
            store.enqueue(ACCOUNT_B, LISTING_C, InteractionOutboxKind.Like, true, OUTBOX_TIME_5)
            assertTrue(store.recordRetry(delayed.operationId, 0, OUTBOX_TIME_30))

            assertEquals(
                listOf(laterEnqueued.operationId, sameDeadline.operationId),
                store.listReadyForAccount(ACCOUNT_A, readyAtEpochMilliseconds = 25).map { it.operationId },
            )
            assertEquals(
                listOf(laterEnqueued.operationId, sameDeadline.operationId, delayed.operationId),
                store.listReadyForAccount(ACCOUNT_A, readyAtEpochMilliseconds = 30).map { it.operationId },
            )
            assertEquals(
                listOf(delayed.operationId),
                store.listForAccountAndListingIds(ACCOUNT_A, listOf(LISTING_B)).map { it.operationId },
            )
            assertEquals(ACCOUNT_A_OPERATION_COUNT, store.purgeAccount(ACCOUNT_A))
            assertEquals(1, store.listForAccount(ACCOUNT_B).size)
        }
    }

    @Test
    fun visibleListingReadReturnsBothKindsWithoutAnUnrelatedOrCrossAccountRow() = runTest {
        withOutboxDatabase(coroutineContext) { database ->
            val store = InteractionOutboxStore(database.interactionOutboxDao())
            store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, true, OUTBOX_TIME_30)
            store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Favorite, false, OUTBOX_TIME_20)
            store.enqueue(ACCOUNT_A, LISTING_B, InteractionOutboxKind.Like, true, OUTBOX_TIME_10)
            store.enqueue(ACCOUNT_B, LISTING_A, InteractionOutboxKind.Like, false, OUTBOX_TIME_5)

            val visible = store.listForAccountAndListingIds(ACCOUNT_A, listOf(LISTING_A, LISTING_A))

            assertEquals(
                listOf(InteractionOutboxKind.Favorite, InteractionOutboxKind.Like),
                visible.map { operation -> operation.kind },
            )
            assertTrue(
                visible.all { operation -> operation.accountId == ACCOUNT_A && operation.listingId == LISTING_A },
            )
        }
    }

    @Test
    fun perAccountCapacityRejectsOnlyNewKeysAndNeverLosesAnExistingKeyUpdate() = runTest {
        withOutboxDatabase(coroutineContext) { database ->
            val store = InteractionOutboxStore(database.interactionOutboxDao(), maxOperationCount = 2)
            val first = store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, true, OUTBOX_TIME_10)
            store.enqueue(ACCOUNT_A, LISTING_B, InteractionOutboxKind.Like, true, OUTBOX_TIME_20)

            val updated = store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, false, OUTBOX_TIME_30)
            assertNotEquals(first.operationId, updated.operationId)
            assertFailsWith<InteractionOutboxCapacityExceededException> {
                store.enqueue(ACCOUNT_A, LISTING_C, InteractionOutboxKind.Like, true, OUTBOX_TIME_40)
            }
            store.enqueue(ACCOUNT_B, LISTING_A, InteractionOutboxKind.Like, true, OUTBOX_TIME_50)
            store.enqueue(ACCOUNT_B, LISTING_B, InteractionOutboxKind.Like, true, OUTBOX_TIME_60)

            assertEquals(false, store.listForAccount(ACCOUNT_A).first { it.listingId == LISTING_A }.desiredSelected)
            assertEquals(2, store.listForAccount(ACCOUNT_A).size)
            assertEquals(2, store.listForAccount(ACCOUNT_B).size)
        }
    }

    @Test
    fun concurrentSameDesiredEnqueuesCoalesceToOneStableOperation() = runTest {
        withOutboxDatabase(coroutineContext) { database ->
            val store = InteractionOutboxStore(database.interactionOutboxDao())
            val initial = store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, false, 1)
            val start = CompletableDeferred<Unit>()
            val operations = (1..CONCURRENT_ENQUEUE_COUNT).map { sequence ->
                async {
                    start.await()
                    store.enqueue(
                        accountId = ACCOUNT_A,
                        listingId = LISTING_A,
                        kind = InteractionOutboxKind.Like,
                        desiredSelected = true,
                        enqueuedAtEpochMilliseconds = sequence.toLong() + 10,
                    )
                }
            }
            start.complete(Unit)

            val operationIds = operations.awaitAll().map { operation -> operation.operationId }.distinct()

            assertEquals(1, operationIds.size)
            assertNotEquals(initial.operationId, operationIds.single())
            assertEquals(operationIds.single(), store.listForAccount(ACCOUNT_A).single().operationId)
        }
    }

    @Test
    fun invalidArgumentsAreRejectedBeforeRoomIsCalled() = runTest {
        withOutboxDatabase(coroutineContext) { database ->
            val store = InteractionOutboxStore(database.interactionOutboxDao())

            assertFailsWith<IllegalArgumentException> {
                InteractionOutboxStore(
                    database.interactionOutboxDao(),
                    maxOperationCount = DEFAULT_MAX_INTERACTION_OUTBOX_OPERATIONS + 1,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                store.enqueue(ACCOUNT_A.uppercase(), LISTING_A, InteractionOutboxKind.Like, true, 0)
            }
            assertFailsWith<IllegalArgumentException> {
                store.enqueue(ACCOUNT_A, "listing-a", InteractionOutboxKind.Like, true, 0)
            }
            assertFailsWith<IllegalArgumentException> {
                store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, true, -1)
            }
            assertFailsWith<IllegalArgumentException> { store.listForAccount(ACCOUNT_A, limit = 0) }
            assertFailsWith<IllegalArgumentException> {
                store.listForAccountAndListingIds(
                    ACCOUNT_A,
                    List(MAX_INTERACTION_OUTBOX_VISIBLE_LISTING_IDS + 1) { LISTING_A },
                )
            }
            assertFailsWith<IllegalArgumentException> { store.recordRetry(0, 0, 1) }
            assertFailsWith<IllegalArgumentException> { store.recordRetry(1, -1, 1) }
            assertFailsWith<IllegalArgumentException> { store.recordTerminalFailure(1, 0, "Bad-Code") }
        }
    }

    @Test
    fun logicalCorruptionIsEvictedWhileStorageFailuresPropagate() = runTest {
        withOutboxDatabase(coroutineContext) { database ->
            val dao = database.interactionOutboxDao()
            val store = InteractionOutboxStore(dao)
            val healthy = store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, true, 1)
            listOf(
                corruptEntity(listingId = LISTING_B, kind = "invalid"),
                corruptEntity(listingId = LISTING_C, enqueuedAtEpochMilliseconds = -1),
                corruptEntity(listingId = LISTING_D, attemptCount = -1),
                corruptEntity(listingId = LISTING_E, terminalErrorCode = "Bad-Code"),
                corruptEntity(listingId = LISTING_F, attemptCount = Int.MAX_VALUE.toLong() + 1),
                corruptEntity(listingId = LISTING_G).copy(desiredSelectedRaw = 2L),
            ).forEach { entity -> dao.insert(entity) }

            assertEquals(listOf(healthy), store.listForAccount(ACCOUNT_A, limit = 10))
            assertEquals(1, dao.countForAccount(ACCOUNT_A))

            store.purgeAccount(ACCOUNT_A)
            dao.insert(corruptEntity(listingId = LISTING_A, attemptCount = -1))
            assertFailsWith<IllegalStateException> {
                store.enqueue(ACCOUNT_A, LISTING_A, InteractionOutboxKind.Like, true, 2)
            }
            assertEquals(0, dao.countForAccount(ACCOUNT_A))

            val failingStore = InteractionOutboxStore(
                dao = dao,
                reader = FailingReadInteractionOutboxReader(dao),
            )
            assertFailsWith<IOException> { failingStore.listForAccount(ACCOUNT_A) }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class InteractionOutboxRestartTest {
    @Test
    fun operationAndRetryStateSurviveDatabaseCloseAndReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RESTART_DATABASE_NAME)
        try {
            val firstDatabase = namedOutboxDatabase(context, coroutineContext)
            val initial = try {
                val store = InteractionOutboxStore(firstDatabase.interactionOutboxDao())
                store.enqueue(
                    ACCOUNT_A,
                    LISTING_A,
                    InteractionOutboxKind.Favorite,
                    true,
                    OUTBOX_TIME_10,
                ).also { operation ->
                    assertTrue(store.recordRetry(operation.operationId, 0, OUTBOX_TIME_50))
                }
            } finally {
                firstDatabase.close()
            }

            val reopenedDatabase = namedOutboxDatabase(context, coroutineContext)
            try {
                val restored = InteractionOutboxStore(reopenedDatabase.interactionOutboxDao())
                    .listForAccount(ACCOUNT_A)
                    .single()
                assertEquals(initial.operationId, restored.operationId)
                assertEquals(1, restored.attemptCount)
                assertEquals(OUTBOX_TIME_50, restored.nextAttemptAtEpochMilliseconds)
                assertTrue(
                    InteractionOutboxStore(reopenedDatabase.interactionOutboxDao())
                        .listReadyForAccount(ACCOUNT_A, readyAtEpochMilliseconds = 49)
                        .isEmpty(),
                )
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            context.deleteDatabase(RESTART_DATABASE_NAME)
        }
    }

    @Test
    fun rejectedCrashResidueIsCollectedAndCapacityIsRecoveredAfterReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(RESTART_DATABASE_NAME)
        try {
            val firstDatabase = namedOutboxDatabase(context, coroutineContext)
            val residue = seedRejectedCrashResidue(firstDatabase)

            val reopenedDatabase = namedOutboxDatabase(context, coroutineContext)
            try {
                assertRejectedResidueCollected(reopenedDatabase, residue)
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            context.deleteDatabase(RESTART_DATABASE_NAME)
        }
    }
}

private fun corruptEntity(
    listingId: String,
    kind: String = InteractionOutboxKind.Like.storedValue,
    enqueuedAtEpochMilliseconds: Long = 1,
    attemptCount: Long = 0,
    terminalErrorCode: String? = null,
): InteractionOutboxEntity = InteractionOutboxEntity(
    accountId = ACCOUNT_A,
    listingId = listingId,
    kind = kind,
    desiredSelectedRaw = 1L,
    enqueuedAtEpochMilliseconds = enqueuedAtEpochMilliseconds,
    attemptCount = attemptCount,
    nextAttemptAtEpochMilliseconds = 1,
    terminalErrorCode = terminalErrorCode,
)

private suspend fun seedRejectedCrashResidue(database: KwaborDatabase): RejectedCrashResidue = try {
    val store = InteractionOutboxStore(database.interactionOutboxDao(), maxOperationCount = 2)
    val rejected = store.enqueue(
        ACCOUNT_A,
        LISTING_A,
        InteractionOutboxKind.Like,
        true,
        OUTBOX_TIME_10,
    )
    val manual = store.enqueue(
        ACCOUNT_A,
        LISTING_B,
        InteractionOutboxKind.Favorite,
        true,
        OUTBOX_TIME_20,
    )
    assertTrue(store.recordTerminalFailure(rejected.operationId, 0, "validation"))
    assertTrue(store.recordTerminalFailure(manual.operationId, 0, "manual"))
    RejectedCrashResidue(rejected.operationId, manual.operationId)
} finally {
    database.close()
}

private suspend fun assertRejectedResidueCollected(database: KwaborDatabase, residue: RejectedCrashResidue) {
    val store = InteractionOutboxStore(database.interactionOutboxDao(), maxOperationCount = 2)
    val replacement = store.enqueue(
        ACCOUNT_A,
        LISTING_C,
        InteractionOutboxKind.Like,
        false,
        OUTBOX_TIME_30,
    )
    val operations = store.listForAccount(ACCOUNT_A)
    assertEquals(2, operations.size)
    assertTrue(operations.any { operation -> operation.operationId == residue.manualId })
    assertTrue(operations.any { operation -> operation.operationId == replacement.operationId })
    assertTrue(operations.none { operation -> operation.operationId == residue.rejectedId })
}

private data class RejectedCrashResidue(
    val rejectedId: Long,
    val manualId: Long,
)

private class FailingReadInteractionOutboxReader(delegate: InteractionOutboxReader) :
    InteractionOutboxReader by delegate {
    override suspend fun findForAccount(accountId: String, limit: Int): List<InteractionOutboxEntity> {
        throw IOException("Simulated local storage failure.")
    }
}

private suspend fun withOutboxDatabase(
    queryCoroutineContext: CoroutineContext,
    block: suspend (KwaborDatabase) -> Unit,
) {
    val database = buildKwaborDatabase(
        builder = Room.inMemoryDatabaseBuilder(
            context = ApplicationProvider.getApplicationContext<Context>(),
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

private fun namedOutboxDatabase(context: Context, queryCoroutineContext: CoroutineContext): KwaborDatabase =
    buildKwaborDatabase(
        builder = Room.databaseBuilder(
            context = context,
            name = RESTART_DATABASE_NAME,
            factory = KwaborDatabaseConstructor::initialize,
        ),
        queryCoroutineContext = queryCoroutineContext,
        driver = AndroidSQLiteDriver(),
    )

private const val CONCURRENT_ENQUEUE_COUNT = 20
private const val ACCOUNT_A_OPERATION_COUNT = 3
private const val OUTBOX_TIME_5 = 5L
private const val OUTBOX_TIME_10 = 10L
private const val OUTBOX_TIME_20 = 20L
private const val OUTBOX_TIME_30 = 30L
private const val OUTBOX_TIME_40 = 40L
private const val OUTBOX_TIME_50 = 50L
private const val OUTBOX_TIME_60 = 60L
private const val RESTART_DATABASE_NAME = "kwabor-outbox-restart-test"
private const val ACCOUNT_A = "a0000000-0000-4000-8000-000000000001"
private const val ACCOUNT_B = "b0000000-0000-4000-8000-000000000002"
private const val LISTING_A = "20000000-0000-4000-8000-000000000001"
private const val LISTING_B = "20000000-0000-4000-8000-000000000002"
private const val LISTING_C = "20000000-0000-4000-8000-000000000003"
private const val LISTING_D = "20000000-0000-4000-8000-000000000004"
private const val LISTING_E = "20000000-0000-4000-8000-000000000005"
private const val LISTING_F = "20000000-0000-4000-8000-000000000006"
private const val LISTING_G = "20000000-0000-4000-8000-000000000007"
