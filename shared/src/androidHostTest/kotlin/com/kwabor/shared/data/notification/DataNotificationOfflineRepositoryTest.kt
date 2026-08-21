package com.kwabor.shared.data.notification

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.data.local.KwaborDatabase
import com.kwabor.shared.data.local.KwaborDatabaseConstructor
import com.kwabor.shared.data.local.buildKwaborDatabase
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.ActiveNotificationAccountProvider
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DataNotificationOfflineRepositoryTest {
    @Test
    fun readAndStaleReplaceRejectAnAbaEpochWithoutExposingOrRegressingCache() = runTest {
        withOfflineNotificationDatabase(coroutineContext) { database ->
            val stores = offlineStores(database)
            stores.inbox.replaceInbox(
                accountId = ACCOUNT_ID,
                page = offlinePage(sequence = 20),
                status = offlineStatus(latest = 20),
                cachedAtEpochMilliseconds = OFFLINE_CACHE_TIME,
            )
            val readProvider = AbaNotificationAccountProvider()
            val readRepository = stores.repository(readProvider)

            val read = readRepository.readInbox(OFFLINE_SCOPE)

            assertIs<DomainError.AuthenticationRequired>(assertIs<DomainResult.Failure>(read).error)
            assertEquals(listOf(OFFLINE_SCOPE, null, ABA_SCOPE), readProvider.transitions)
            val replaceProvider = AbaNotificationAccountProvider()
            val replaceRepository = stores.repository(replaceProvider)
            val replace = replaceRepository.replaceInbox(
                expectedScope = OFFLINE_SCOPE,
                page = offlinePage(sequence = 10),
                status = offlineStatus(latest = 10),
                cachedAtEpochMilliseconds = OFFLINE_CACHE_TIME + 1,
            )

            assertIs<DomainError.AuthenticationRequired>(assertIs<DomainResult.Failure>(replace).error)
            assertEquals(listOf(OFFLINE_SCOPE, null, ABA_SCOPE), replaceProvider.transitions)
            assertEquals(20L, assertNotNull(stores.inbox.readInbox(ACCOUNT_ID)).snapshotSequence)
        }
    }

    @Test
    fun memoryFallbackFailsClosedWithoutOpeningRoom() = runTest {
        val lock = NotificationStoreLock()
        val unavailableDao = { error("Room must stay unopened in memory-only mode") }
        val repository = DataNotificationOfflineRepository(
            inboxStore = NotificationInboxStore(unavailableDao, false, lock),
            preferencesStore = NotificationPreferencesStore(unavailableDao, false, lock),
            activeAccountProvider = ActiveNotificationAccountProvider { OFFLINE_SCOPE },
        )

        val read = repository.readInbox(OFFLINE_SCOPE)

        assertIs<DomainError.LocalStorageUnavailable>(assertIs<DomainResult.Failure>(read).error)
    }
}

private class AbaNotificationAccountProvider : ActiveNotificationAccountProvider {
    private var requestCount = 0
    val transitions = mutableListOf<NotificationAccountScope?>()

    override fun currentScope(): NotificationAccountScope? {
        requestCount += 1
        return if (requestCount == 1) {
            OFFLINE_SCOPE.also(transitions::add)
        } else {
            transitions += null
            ABA_SCOPE.also(transitions::add)
        }
    }
}

private data class OfflineStores(
    val inbox: NotificationInboxStore,
    val preferences: NotificationPreferencesStore,
) {
    fun repository(provider: ActiveNotificationAccountProvider): DataNotificationOfflineRepository =
        DataNotificationOfflineRepository(inbox, preferences, provider)
}

private fun offlineStores(database: KwaborDatabase): OfflineStores {
    val lock = NotificationStoreLock()
    return OfflineStores(
        inbox = NotificationInboxStore(database.notificationInboxDao(), lock),
        preferences = NotificationPreferencesStore(database.notificationPreferencesDao(), lock),
    )
}

private fun offlinePage(sequence: Long): NotificationInboxPage = NotificationInboxPage(
    items = listOf(
        notificationRow(
            sequence = sequence,
            notificationId = "20000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}",
            rowCursor = "cursor-$sequence",
        ).copy(snapshotSequence = sequence).toDomain(),
    ),
    snapshotSequence = sequence,
    nextCursor = null,
)

private fun offlineStatus(latest: Long): NotificationInboxStatus = NotificationInboxStatus(
    latestSequence = latest,
    seenThroughSequence = 0,
    unseenCount = 1,
    unreadCount = 1,
)

private suspend fun withOfflineNotificationDatabase(
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

private val OFFLINE_SCOPE = NotificationAccountScope(ACCOUNT_ID, epoch = 1)
private val ABA_SCOPE = NotificationAccountScope(ACCOUNT_ID, epoch = 3)
private const val OFFLINE_CACHE_TIME = 1_786_356_100_000L
