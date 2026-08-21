package com.kwabor.shared.data.notification

import androidx.sqlite.SQLiteException
import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.data.local.NotificationOutboxCapacityExceededException
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.notification.ActiveNotificationAccountProvider
import com.kwabor.shared.domain.notification.NotificationAccountScope
import com.kwabor.shared.domain.notification.NotificationCachedInbox
import com.kwabor.shared.domain.notification.NotificationCachedPreferences
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationItemMutation
import com.kwabor.shared.domain.notification.NotificationMarkAllReadConfirmation
import com.kwabor.shared.domain.notification.NotificationOfflineRepository
import com.kwabor.shared.domain.notification.NotificationPreferences
import kotlinx.coroutines.CancellationException

class DataNotificationOfflineRepository internal constructor(
    private val inboxStore: NotificationInboxStore,
    private val preferencesStore: NotificationPreferencesStore,
    private val activeAccountProvider: ActiveNotificationAccountProvider,
) : NotificationOfflineRepository {
    override suspend fun readInbox(
        expectedScope: NotificationAccountScope,
    ): DomainResult<NotificationCachedInbox?> = runScopedPersistenceCall(expectedScope) { accountId ->
        inboxStore.readInbox(accountId)?.toDomain()
    }

    override suspend fun replaceInbox(
        expectedScope: NotificationAccountScope,
        page: NotificationInboxPage,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox> = runScopedPersistenceCall(expectedScope) { accountId ->
        inboxStore.replaceInbox(accountId, page, status, cachedAtEpochMilliseconds).toDomain()
    }

    override suspend fun appendInbox(
        expectedScope: NotificationAccountScope,
        expectedSnapshotSequence: Long,
        expectedNextCursor: String,
        page: NotificationInboxPage,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox> = runScopedPersistenceCall(expectedScope) { accountId ->
        inboxStore.appendInbox(
            accountId = accountId,
            expectedSnapshotSequence = expectedSnapshotSequence,
            expectedNextCursor = expectedNextCursor,
            page = page,
            cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
        ).toDomain()
    }

    override suspend fun storeStatus(
        expectedScope: NotificationAccountScope,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox> = runScopedPersistenceCall(expectedScope) { accountId ->
        inboxStore.storeStatus(accountId, status, cachedAtEpochMilliseconds).toDomain()
    }

    override suspend fun applyItemMutation(
        expectedScope: NotificationAccountScope,
        mutation: NotificationItemMutation,
    ): DomainResult<Boolean> = runScopedPersistenceCall(expectedScope) { accountId ->
        inboxStore.applyItemMutation(accountId, mutation)
    }

    override suspend fun applyMarkAllRead(
        expectedScope: NotificationAccountScope,
        confirmation: NotificationMarkAllReadConfirmation,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedInbox> = runScopedPersistenceCall(expectedScope) { accountId ->
        inboxStore.applyMarkAllRead(accountId, confirmation, cachedAtEpochMilliseconds).toDomain()
    }

    override suspend fun readPreferences(
        expectedScope: NotificationAccountScope,
    ): DomainResult<NotificationCachedPreferences> = runScopedPersistenceCall(expectedScope) { accountId ->
        preferencesStore.readPreferences(accountId).toDomain()
    }

    override suspend fun replacePreferences(
        expectedScope: NotificationAccountScope,
        preferences: NotificationPreferences,
        cachedAtEpochMilliseconds: Long,
    ): DomainResult<NotificationCachedPreferences> = runScopedPersistenceCall(expectedScope) { accountId ->
        preferencesStore.replacePreferences(accountId, preferences, cachedAtEpochMilliseconds).toDomain()
    }

    private suspend fun <T> runScopedPersistenceCall(
        expectedScope: NotificationAccountScope,
        block: suspend (String) -> T,
    ): DomainResult<T> = runNotificationPersistenceCall {
        val canonicalScope = expectedScope.toCanonicalScope()
        activeAccountProvider.requireExactScope(canonicalScope)
        val value = block(canonicalScope.accountId)
        activeAccountProvider.requireExactScope(canonicalScope)
        value
    }
}

private fun CachedNotificationInbox.toDomain(): NotificationCachedInbox = NotificationCachedInbox(
    accountId = accountId,
    snapshotSequence = snapshotSequence,
    nextCursor = nextCursor,
    status = status,
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    items = items,
)

private fun CachedNotificationPreferences.toDomain(): NotificationCachedPreferences = NotificationCachedPreferences(
    accountId = accountId,
    preferences = preferences,
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
)

internal fun NotificationAccountScope.toCanonicalScope(): NotificationAccountScope = copy(
    accountId = accountId.toCanonicalNotificationPersistenceUuid("expected_account_id"),
)

internal fun ActiveNotificationAccountProvider.requireExactScope(expectedScope: NotificationAccountScope) {
    val current = currentScope()
    if (
        current == null ||
        current.epoch != expectedScope.epoch ||
        current.accountId.trim().lowercase() != expectedScope.accountId
    ) {
        throw NotificationPersistenceException.AuthenticationRequired
    }
}

private fun String.toCanonicalNotificationPersistenceUuid(fieldName: String): String {
    val canonical = trim().lowercase()
    if (!canonical.isValidUuid()) {
        throw NotificationPersistenceException.Validation("error.notifications.$fieldName.invalid")
    }
    return canonical
}

private suspend inline fun <T> runNotificationPersistenceCall(
    crossinline block: suspend () -> T,
): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: CancellationException) {
    throw exception
} catch (exception: NotificationPersistenceException) {
    DomainResult.Failure(exception.domainError)
} catch (_: NotificationStorageUnavailableException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable())
} catch (_: NotificationOutboxCapacityExceededException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable("error.notifications.outbox_full"))
} catch (_: NotificationCacheCapacityExceededException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable("error.notifications.cache_full"))
} catch (_: NotificationCacheSnapshotMismatchException) {
    DomainResult.Failure(DomainError.Validation("error.notifications.cache_snapshot_changed"))
} catch (_: SQLiteException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable())
} catch (_: IllegalArgumentException) {
    DomainResult.Failure(DomainError.Validation("error.notifications.invalid_request"))
} catch (_: IllegalStateException) {
    DomainResult.Failure(DomainError.LocalStorageUnavailable())
}

internal sealed class NotificationPersistenceException(
    val domainError: DomainError,
) : RuntimeException(domainError.messageKey) {
    class Validation(messageKey: String) : NotificationPersistenceException(DomainError.Validation(messageKey))

    data object AuthenticationRequired :
        NotificationPersistenceException(DomainError.AuthenticationRequired())
}
