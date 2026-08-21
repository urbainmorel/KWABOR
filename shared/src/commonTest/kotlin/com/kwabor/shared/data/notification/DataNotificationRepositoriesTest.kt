package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.ActiveNotificationAccountProvider
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationPageRequest
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DataNotificationRepositoriesTest {
    @Test
    fun repositoriesCanonicalizeAndForwardExpectedAccountIdOnEveryCall() = runTest {
        val provider = MutableNotificationAccountProvider(ACTIVE_SCOPE)
        val source = RecordingNotificationDataSource()
        val inbox = DataNotificationInboxRepository(source, provider)
        val preferences = DataNotificationPreferencesRepository(source, provider)
        val requestedScope = NotificationAccountScope(ACCOUNT_ID.uppercase(), ACTIVE_SCOPE.epoch)

        assertIs<DomainResult.Success<*>>(inbox.getStatus(requestedScope))
        assertIs<DomainResult.Success<*>>(inbox.listInbox(requestedScope))
        assertIs<DomainResult.Success<*>>(inbox.markSeenThrough(requestedScope, SNAPSHOT_SEQUENCE))
        assertIs<DomainResult.Success<*>>(inbox.markRead(requestedScope, NOTIFICATION_ID))
        assertIs<DomainResult.Success<*>>(inbox.markAllReadThrough(requestedScope, SNAPSHOT_SEQUENCE))
        assertIs<DomainResult.Success<*>>(inbox.hide(requestedScope, NOTIFICATION_ID))
        assertIs<DomainResult.Success<*>>(preferences.getPreferences(requestedScope))
        assertIs<DomainResult.Success<*>>(
            preferences.setPreference(requestedScope, NotificationPreferenceFamily.Sponsored, true),
        )

        assertEquals(List(8) { ACCOUNT_ID }, source.expectedAccountIds)
    }

    @Test
    fun invalidIdentifiersAndCursorsFailBeforeTransport() = runTest {
        val source = RecordingNotificationDataSource()
        val inbox = DataNotificationInboxRepository(source, MutableNotificationAccountProvider(ACTIVE_SCOPE))

        val invalidAccount = inbox.getStatus(NotificationAccountScope("not-an-account", ACTIVE_SCOPE.epoch))
        val invalidNotification = inbox.markRead(ACTIVE_SCOPE, "not-a-notification")
        val invalidCursor = inbox.listInbox(ACTIVE_SCOPE, NotificationPageRequest(cursor = "bad cursor"))

        assertIs<DomainError.Validation>(assertIs<DomainResult.Failure>(invalidAccount).error)
        assertIs<DomainError.Validation>(assertIs<DomainResult.Failure>(invalidNotification).error)
        assertIs<DomainError.Validation>(assertIs<DomainResult.Failure>(invalidCursor).error)
        assertEquals(emptyList(), source.expectedAccountIds)
    }

    @Test
    fun responseIsRejectedWhenAccountOrEpochChangesDuringRpc() = runTest {
        val provider = MutableNotificationAccountProvider(ACTIVE_SCOPE)
        val source = RecordingNotificationDataSource {
            provider.scope = NotificationAccountScope(ACCOUNT_ID_2, ACTIVE_SCOPE.epoch + 1)
            provider.scope = null
        }
        val switchedAccount = DataNotificationInboxRepository(source, provider).getStatus(ACTIVE_SCOPE)

        assertIs<DomainError.AuthenticationRequired>(assertIs<DomainResult.Failure>(switchedAccount).error)

        provider.scope = ACTIVE_SCOPE
        source.afterCall = {
            provider.scope = null
            provider.scope = NotificationAccountScope(ACCOUNT_ID, ACTIVE_SCOPE.epoch + 2)
        }
        val abaEpoch = DataNotificationPreferencesRepository(source, provider).getPreferences(ACTIVE_SCOPE)

        assertIs<DomainError.AuthenticationRequired>(assertIs<DomainResult.Failure>(abaEpoch).error)
    }
}

internal class MutableNotificationAccountProvider(
    var scope: NotificationAccountScope?,
) : ActiveNotificationAccountProvider {
    override fun currentScope(): NotificationAccountScope? = scope
}

private class RecordingNotificationDataSource(
    var afterCall: () -> Unit = {},
) : NotificationDataSource {
    val expectedAccountIds = mutableListOf<String>()

    override suspend fun getStatus(expectedAccountId: String): NotificationInboxStatusDto = status(expectedAccountId)

    override suspend fun listInbox(
        expectedAccountId: String,
        page: NotificationPageRequest,
    ): NotificationInboxPageDto = record(expectedAccountId) {
        listOf(notificationRow(sequence = 1)).toNotificationInboxPageDto(page.limit)
    }

    override suspend fun markSeenThrough(
        expectedAccountId: String,
        throughSequence: Long,
    ): NotificationInboxStatusDto = status(expectedAccountId)

    override suspend fun markRead(
        expectedAccountId: String,
        notificationId: String,
    ): NotificationItemMutationDto = record(expectedAccountId) {
        NotificationItemMutationDto(notificationId, 1, STATE_TIMESTAMP, STATE_TIMESTAMP, null)
    }

    override suspend fun markAllReadThrough(
        expectedAccountId: String,
        throughSequence: Long,
    ): NotificationMarkAllReadResultDto = record(expectedAccountId) {
        NotificationMarkAllReadResultDto(
            latestSequence = SNAPSHOT_SEQUENCE,
            seenThroughSequence = SNAPSHOT_SEQUENCE,
            unseenCount = 0,
            unreadCount = 0,
            mutationAt = STATE_TIMESTAMP,
        )
    }

    override suspend fun hide(
        expectedAccountId: String,
        notificationId: String,
    ): NotificationItemMutationDto = record(expectedAccountId) {
        NotificationItemMutationDto(notificationId, 1, STATE_TIMESTAMP, null, STATE_TIMESTAMP)
    }

    override suspend fun listPreferences(expectedAccountId: String): List<NotificationPreferenceRowDto> =
        record(expectedAccountId) { emptyList() }

    override suspend fun setPreference(
        expectedAccountId: String,
        family: NotificationPreferenceFamily,
        enabled: Boolean,
    ): NotificationPreferenceRowDto = record(expectedAccountId) {
        NotificationPreferenceRowDto(family.toWireValue(), enabled, STATE_TIMESTAMP)
    }

    private fun status(expectedAccountId: String): NotificationInboxStatusDto = record(expectedAccountId) {
        NotificationInboxStatusDto(SNAPSHOT_SEQUENCE, SNAPSHOT_SEQUENCE, 0, 0)
    }

    private fun <T> record(expectedAccountId: String, result: () -> T): T {
        expectedAccountIds += expectedAccountId
        return result().also { afterCall() }
    }
}

internal val ACTIVE_SCOPE = NotificationAccountScope(ACCOUNT_ID, epoch = 7)
internal const val ACCOUNT_ID_2 = "10000000-0000-4000-8000-000000000002"
