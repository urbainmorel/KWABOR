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
import com.kwabor.shared.domain.notification.NotificationFamilyPreference
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxRepository
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationItemMutation
import com.kwabor.shared.domain.notification.NotificationMarkAllReadConfirmation
import com.kwabor.shared.domain.notification.NotificationPageRequest
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import com.kwabor.shared.domain.notification.NotificationPreferences
import com.kwabor.shared.domain.notification.NotificationPreferencesRepository
import com.kwabor.shared.domain.notification.NotificationSubmitOutcome
import com.kwabor.shared.domain.notification.NotificationSyncCommand
import com.kwabor.shared.domain.notification.PendingNotificationSync
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DataNotificationSyncRepositoryTest {
    @Test
    fun delayedAbaResponseCannotSettleDebtAndEpochIsReboundOnReload() = runTest {
        withSyncNotificationDatabase(coroutineContext) { database ->
            val lock = NotificationStoreLock()
            val provider = AbaSyncAccountProvider(SYNC_SCOPE)
            val outbox = NotificationOutboxStore(database.notificationOutboxDao(), lock)
            val settlement = NotificationOutboxSettlementStore(
                database.notificationOutboxDao(),
                database.notificationConfirmationSettlementDao(),
                lock,
            )
            val inboxStore = NotificationInboxStore(database.notificationInboxDao(), lock)
            val preferencesStore = NotificationPreferencesStore(database.notificationPreferencesDao(), lock)
            val sync = DataNotificationSyncRepository(
                outboxStore = outbox,
                settlementStore = settlement,
                drainSingleFlight = NotificationDrainSingleFlight(backgroundScope),
                dependencies = NotificationSyncDependencies(
                    inboxRepository = AbaInboxRepository(provider),
                    preferencesRepository = UnusedPreferencesRepository,
                    inboxStore = inboxStore,
                    preferencesStore = preferencesStore,
                    activeAccountProvider = provider,
                ),
                clockProvider = object : ClockProvider {
                    override fun nowEpochMilliseconds(): Long = SYNC_TIME
                },
            )
            val command = NotificationSyncCommand.MarkRead(SYNC_SCOPE, NOTIFICATION_ID)

            assertIs<NotificationSubmitOutcome.Queued>(assertIs<DomainResult.Success<*>>(sync.submit(command)).value)
            val drain = sync.drainDue(SYNC_SCOPE)

            assertIs<DomainError.AuthenticationRequired>(assertIs<DomainResult.Failure>(drain).error)
            assertEquals(1, outbox.listOperations(ACCOUNT_ID).size)
            assertEquals(listOf(null, SYNC_ABA_SCOPE), provider.transitions)
            val rebound = assertIs<DomainResult.Success<List<PendingNotificationSync>>>(
                sync.loadPending(SYNC_ABA_SCOPE),
            ).value
            val pending = rebound.single()
            assertEquals(SYNC_ABA_SCOPE, pending.command.scope)
        }
    }
}

private class AbaSyncAccountProvider(
    var scope: NotificationAccountScope?,
) : ActiveNotificationAccountProvider {
    val transitions = mutableListOf<NotificationAccountScope?>()

    override fun currentScope(): NotificationAccountScope? = scope

    fun moveThroughGuestToAba() {
        scope = null
        transitions += null
        scope = SYNC_ABA_SCOPE
        transitions += SYNC_ABA_SCOPE
    }
}

private class AbaInboxRepository(
    private val provider: AbaSyncAccountProvider,
) : NotificationInboxRepository {
    override suspend fun markRead(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ): DomainResult<NotificationItemMutation> {
        provider.moveThroughGuestToAba()
        return DomainResult.Success(
            NotificationItemMutation(
                notificationId = notificationId,
                sequence = 1,
                seenAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                readAtEpochMilliseconds = STATE_TIMESTAMP_EPOCH_MILLISECONDS,
                hiddenAtEpochMilliseconds = null,
            ),
        )
    }

    override suspend fun getStatus(
        expectedScope: NotificationAccountScope,
    ): DomainResult<NotificationInboxStatus> = unusedSyncCall()

    override suspend fun listInbox(
        expectedScope: NotificationAccountScope,
        page: NotificationPageRequest,
    ): DomainResult<NotificationInboxPage> = unusedSyncCall()

    override suspend fun markSeenThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ): DomainResult<NotificationInboxStatus> = unusedSyncCall()

    override suspend fun markAllReadThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ): DomainResult<NotificationMarkAllReadConfirmation> = unusedSyncCall()

    override suspend fun hide(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ): DomainResult<NotificationItemMutation> = unusedSyncCall()
}

private data object UnusedPreferencesRepository : NotificationPreferencesRepository {
    override suspend fun getPreferences(
        expectedScope: NotificationAccountScope,
    ): DomainResult<NotificationPreferences> = unusedSyncCall()

    override suspend fun setPreference(
        expectedScope: NotificationAccountScope,
        family: NotificationPreferenceFamily,
        enabled: Boolean,
    ): DomainResult<NotificationFamilyPreference> = unusedSyncCall()
}

private fun <T> unusedSyncCall(): DomainResult<T> = DomainResult.Failure(DomainError.Unexpected())

private suspend fun withSyncNotificationDatabase(
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

private val SYNC_SCOPE = NotificationAccountScope(ACCOUNT_ID, epoch = 1)
private val SYNC_ABA_SCOPE = NotificationAccountScope(ACCOUNT_ID, epoch = 3)
private const val SYNC_TIME = 1_786_356_100_000L
