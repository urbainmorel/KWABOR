package com.kwabor.shared.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AccountPrivateDataPurgeDaoTest {
    @Test
    fun oneTransactionDeletesInteractionAndNotificationRowsOnlyForTheRequestedAccount() = runTest {
        withPrivateDataPurgeDatabase(coroutineContext) { database ->
            database.seedPrivateData(ACCOUNT_A, suffix = "1")
            database.seedPrivateData(ACCOUNT_B, suffix = "2")

            val result = database.accountPrivateDataPurgeDao().purgeAccount(ACCOUNT_A)

            assertEquals(
                AccountPrivateDataPurgeRecord(
                    interactionOperationCount = 1,
                    notificationItemCount = 1,
                    notificationSnapshotCount = 1,
                    notificationOperationCount = 1,
                    notificationPreferenceCount = 1,
                ),
                result,
            )
            database.assertPrivateDataAbsent(ACCOUNT_A)
            database.assertPrivateDataPresent(ACCOUNT_B)
        }
    }

    @Test
    fun failureAfterInteractionAndNotificationOutboxDeletesRollsBackEveryTable() = runTest {
        withPrivateDataPurgeDatabase(coroutineContext) { database ->
            database.seedPrivateData(ACCOUNT_A, suffix = "1")
            database.useWriterConnection { connection ->
                connection.execSQL(FAIL_PURGE_TRIGGER)
            }

            assertFails { database.accountPrivateDataPurgeDao().purgeAccount(ACCOUNT_A) }

            database.useWriterConnection { connection ->
                connection.execSQL("DROP TRIGGER fail_account_private_data_purge")
            }
            database.assertPrivateDataPresent(ACCOUNT_A)
        }
    }
}

private suspend fun KwaborDatabase.seedPrivateData(accountId: String, suffix: String) {
    interactionOutboxDao().insert(
        InteractionOutboxEntity(
            accountId = accountId,
            listingId = "20000000-0000-4000-8000-00000000000$suffix",
            kind = "like",
            desiredSelectedRaw = 1,
            enqueuedAtEpochMilliseconds = 1,
            attemptCount = 0,
            nextAttemptAtEpochMilliseconds = 1,
            terminalErrorCode = null,
        ),
    )
    notificationInboxDao().replaceSnapshot(
        snapshot = NotificationInboxSnapshotEntity(
            accountId = accountId,
            snapshotSequence = 1,
            nextCursor = null,
            latestSequence = 1,
            confirmedSeenThroughSequence = 0,
            unseenCount = 1,
            unreadCount = 1,
            cachedAtEpochMilliseconds = 1,
            itemCount = 1,
        ),
        items = listOf(
            NotificationInboxItemEntity(
                accountId = accountId,
                notificationId = "30000000-0000-4000-8000-00000000000$suffix",
                sequenceNumber = 1,
                family = "recommendation",
                titleKey = "notification.recommendation.title",
                bodyKey = "notification.recommendation.body",
                contentListingName = "Listing $suffix",
                contentCityName = null,
                contentEventStartAtEpochMilliseconds = null,
                targetAvailableRaw = 0,
                targetListingId = null,
                targetListingType = null,
                targetListingName = null,
                targetCityId = null,
                targetCityName = null,
                targetCoverImageUrl = null,
                targetCoverImageAlt = null,
                targetEventStartAtEpochMilliseconds = null,
                sponsoredRaw = 0,
                seenAtEpochMilliseconds = null,
                readAtEpochMilliseconds = null,
                hiddenAtEpochMilliseconds = null,
                createdAtEpochMilliseconds = 1,
            ),
        ),
    )
    notificationOutboxDao().insertOperation(
        NotificationSyncOperationEntity(
            accountId = accountId,
            logicalKey = "mark_read:30000000-0000-4000-8000-00000000000$suffix",
            kind = "mark_read",
            notificationId = "30000000-0000-4000-8000-00000000000$suffix",
            throughSequence = null,
            family = null,
            desiredEnabledRaw = null,
            enqueuedAtEpochMilliseconds = 1,
            attemptCount = 0,
            nextAttemptAtEpochMilliseconds = 1,
            terminalErrorCode = null,
        ),
    )
    notificationPreferencesDao().replacePreferences(
        accountId = accountId,
        preferences = listOf(
            NotificationPreferenceEntity(
                accountId = accountId,
                family = "recommendation",
                enabledRaw = 1,
                updatedAtEpochMilliseconds = 1,
                cachedAtEpochMilliseconds = 1,
            ),
        ),
    )
}

private suspend fun KwaborDatabase.assertPrivateDataAbsent(accountId: String) {
    assertEquals(0, interactionOutboxDao().countForAccount(accountId))
    assertNull(notificationInboxDao().findSnapshot(accountId))
    assertEquals(emptyList(), notificationInboxDao().findItems(accountId))
    assertEquals(emptyList(), notificationOutboxDao().findOperations(accountId, limit = 10))
    assertEquals(emptyList(), notificationPreferencesDao().findPreferences(accountId))
}

private suspend fun KwaborDatabase.assertPrivateDataPresent(accountId: String) {
    assertEquals(1, interactionOutboxDao().countForAccount(accountId))
    assertNotNull(notificationInboxDao().findSnapshot(accountId))
    assertEquals(1, notificationInboxDao().findItems(accountId).size)
    assertEquals(1, notificationOutboxDao().findOperations(accountId, limit = 10).size)
    assertEquals(1, notificationPreferencesDao().findPreferences(accountId).size)
}

private suspend fun withPrivateDataPurgeDatabase(
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

private const val FAIL_PURGE_TRIGGER = """
    CREATE TRIGGER fail_account_private_data_purge
    BEFORE DELETE ON notification_preferences_cache
    WHEN OLD.account_id = '10000000-0000-4000-8000-000000000001'
    BEGIN
        SELECT RAISE(ABORT, 'forced composite purge rollback');
    END
"""
private const val ACCOUNT_A = "10000000-0000-4000-8000-000000000001"
private const val ACCOUNT_B = "10000000-0000-4000-8000-000000000002"
