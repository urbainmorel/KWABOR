package com.kwabor.shared.data.notification

import com.kwabor.shared.data.core.isValidUuid
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
import kotlinx.coroutines.CancellationException

class DataNotificationInboxRepository internal constructor(
    private val dataSource: NotificationDataSource,
    private val activeAccountProvider: ActiveNotificationAccountProvider,
) : NotificationInboxRepository {
    override suspend fun getStatus(expectedScope: NotificationAccountScope): DomainResult<NotificationInboxStatus> =
        runScopedNotificationDataCall(expectedScope, activeAccountProvider) { accountId ->
            dataSource.getStatus(accountId).toDomain()
        }

    override suspend fun listInbox(
        expectedScope: NotificationAccountScope,
        page: NotificationPageRequest,
    ): DomainResult<NotificationInboxPage> = runScopedNotificationDataCall(
        expectedScope,
        activeAccountProvider,
    ) { accountId ->
        page.cursor?.requireNotificationInputCursor()
        dataSource.listInbox(
            expectedAccountId = accountId,
            page = page,
        ).toDomain()
    }

    override suspend fun markSeenThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ): DomainResult<NotificationInboxStatus> = runScopedNotificationDataCall(
        expectedScope,
        activeAccountProvider,
    ) { accountId ->
        throughSequence.requireNotificationBoundary()
        dataSource.markSeenThrough(
            expectedAccountId = accountId,
            throughSequence = throughSequence,
        ).toDomain().also { status ->
            if (status.latestSequence < throughSequence || status.seenThroughSequence < throughSequence) {
                invalidNotificationValue("seen_through_sequence", "mutation did not confirm requested boundary")
            }
        }
    }

    override suspend fun markRead(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ): DomainResult<NotificationItemMutation> = runScopedNotificationDataCall(
        expectedScope,
        activeAccountProvider,
    ) { accountId ->
        val canonicalNotificationId = notificationId.requireNotificationInputId()
        dataSource.markRead(
            expectedAccountId = accountId,
            notificationId = canonicalNotificationId,
        ).toReadDomain(canonicalNotificationId)
    }

    override suspend fun markAllReadThrough(
        expectedScope: NotificationAccountScope,
        throughSequence: Long,
    ): DomainResult<NotificationMarkAllReadConfirmation> = runScopedNotificationDataCall(
        expectedScope,
        activeAccountProvider,
    ) { accountId ->
        throughSequence.requireNotificationBoundary()
        dataSource.markAllReadThrough(
            expectedAccountId = accountId,
            throughSequence = throughSequence,
        ).toDomain(throughSequence).also { confirmation ->
            if (confirmation.status.latestSequence < throughSequence) {
                invalidNotificationValue("through_sequence", "mutation boundary exceeds latest sequence")
            }
        }
    }

    override suspend fun hide(
        expectedScope: NotificationAccountScope,
        notificationId: String,
    ): DomainResult<NotificationItemMutation> = runScopedNotificationDataCall(
        expectedScope,
        activeAccountProvider,
    ) { accountId ->
        val canonicalNotificationId = notificationId.requireNotificationInputId()
        dataSource.hide(
            expectedAccountId = accountId,
            notificationId = canonicalNotificationId,
        ).toHiddenDomain(canonicalNotificationId)
    }
}

class DataNotificationPreferencesRepository internal constructor(
    private val dataSource: NotificationDataSource,
    private val activeAccountProvider: ActiveNotificationAccountProvider,
) : NotificationPreferencesRepository {
    override suspend fun getPreferences(
        expectedScope: NotificationAccountScope,
    ): DomainResult<NotificationPreferences> =
        runScopedNotificationDataCall(expectedScope, activeAccountProvider) { accountId ->
            dataSource.listPreferences(accountId)
                .toDomainPreferences()
        }

    override suspend fun setPreference(
        expectedScope: NotificationAccountScope,
        family: NotificationPreferenceFamily,
        enabled: Boolean,
    ): DomainResult<NotificationFamilyPreference> = runScopedNotificationDataCall(
        expectedScope,
        activeAccountProvider,
    ) { accountId ->
        dataSource.setPreference(
            expectedAccountId = accountId,
            family = family,
            enabled = enabled,
        ).toDomain().also { preference ->
            if (
                preference.family != family ||
                preference.enabled != enabled ||
                preference.updatedAtEpochMilliseconds == null
            ) {
                invalidNotificationValue("preference", "mutation did not confirm requested state")
            }
        }
    }
}

private suspend inline fun <T> runNotificationDataCall(crossinline block: suspend () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: CancellationException) {
    throw exception
} catch (exception: NotificationDataException) {
    DomainResult.Failure(exception.domainError)
}

private suspend inline fun <T> runScopedNotificationDataCall(
    expectedScope: NotificationAccountScope,
    activeAccountProvider: ActiveNotificationAccountProvider,
    crossinline block: suspend (String) -> T,
): DomainResult<T> = runNotificationDataCall {
    val canonicalScope = NotificationAccountScope(
        accountId = expectedScope.accountId.requireNotificationExpectedAccountId(),
        epoch = expectedScope.epoch,
    )
    activeAccountProvider.requireNetworkScope(canonicalScope)
    val value = block(canonicalScope.accountId)
    activeAccountProvider.requireNetworkScope(canonicalScope)
    value
}

private fun ActiveNotificationAccountProvider.requireNetworkScope(expectedScope: NotificationAccountScope) {
    val current = currentScope()
    if (
        current == null ||
        current.epoch != expectedScope.epoch ||
        current.accountId.trim().lowercase() != expectedScope.accountId
    ) {
        throw NotificationDataException.AuthenticationRequired()
    }
}

private fun String.requireNotificationExpectedAccountId(): String {
    val canonical = trim().lowercase()
    if (!canonical.isValidUuid()) {
        throw NotificationDataException.Validation("error.notifications.expected_account_id.invalid")
    }
    return canonical
}

private fun String.requireNotificationInputId(): String {
    val canonical = trim().lowercase()
    if (!canonical.isValidUuid()) {
        throw NotificationDataException.Validation("error.notifications.notification_id.invalid")
    }
    return canonical
}

private fun Long.requireNotificationBoundary() {
    if (this <= 0L) {
        throw NotificationDataException.Validation("error.notifications.sequence.invalid")
    }
}

private fun String.requireNotificationInputCursor() {
    val isInvalid = listOf(
        isEmpty(),
        encodeToByteArray().size > MAXIMUM_NOTIFICATION_INPUT_CURSOR_UTF8_BYTES,
        trim() != this,
        any(Char::isWhitespace),
        any(Char::isISOControl),
    ).any { condition -> condition }
    if (isInvalid) {
        throw NotificationDataException.Validation("error.notifications.cursor.invalid")
    }
}

private const val MAXIMUM_NOTIFICATION_INPUT_CURSOR_UTF8_BYTES = 4_096
