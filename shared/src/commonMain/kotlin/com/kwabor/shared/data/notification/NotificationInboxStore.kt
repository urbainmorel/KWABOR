package com.kwabor.shared.data.notification

import com.kwabor.shared.data.local.NotificationInboxDao
import com.kwabor.shared.data.local.NotificationInboxItemEntity
import com.kwabor.shared.domain.notification.NotificationInboxItem
import com.kwabor.shared.domain.notification.NotificationInboxPage
import com.kwabor.shared.domain.notification.NotificationInboxStatus
import com.kwabor.shared.domain.notification.NotificationItemMutation
import com.kwabor.shared.domain.notification.NotificationMarkAllReadConfirmation
import kotlinx.coroutines.sync.withLock

internal class NotificationInboxStore(
    private val daoFactory: () -> NotificationInboxDao,
    internal val isDurable: Boolean,
    private val lock: NotificationStoreLock,
    private val maxCachedItemCount: Int = DEFAULT_MAX_NOTIFICATION_CACHE_ITEMS,
) {
    private val dao: NotificationInboxDao by lazy(daoFactory)

    internal constructor(
        dao: NotificationInboxDao,
        lock: NotificationStoreLock = NotificationStoreLock(),
        maxCachedItemCount: Int = DEFAULT_MAX_NOTIFICATION_CACHE_ITEMS,
    ) : this(
        daoFactory = { dao },
        isDurable = true,
        lock = lock,
        maxCachedItemCount = maxCachedItemCount,
    )

    init {
        require(maxCachedItemCount in 1..DEFAULT_MAX_NOTIFICATION_CACHE_ITEMS) {
            "Notification cache item bound is invalid."
        }
    }

    suspend fun readInbox(accountId: String): CachedNotificationInbox? = lock.mutex.withLock {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        readInboxLocked(accountId)
    }

    suspend fun replaceInbox(
        accountId: String,
        page: NotificationInboxPage,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): CachedNotificationInbox = lock.mutex.withLock {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        cachedAtEpochMilliseconds.requireNotificationStoreTimestamp("cachedAtEpochMilliseconds")
        val snapshotSequence = page.snapshotSequence ?: status.latestSequence
        require(snapshotSequence >= 0L) { "Notification snapshot sequence must not be negative." }
        require(page.nextCursor == null || page.items.isNotEmpty()) {
            "An empty notification page cannot expose a continuation cursor."
        }
        val current = readInboxLocked(accountId)
        if (current != null && current.isNewerThan(snapshotSequence, status.latestSequence)) return@withLock current
        val mergedItems = page.items.mergeMonotoneState(current?.items.orEmpty())
        val itemEntities = mergedItems.toValidatedCacheEntities(accountId, snapshotSequence, maxCachedItemCount)
        val projectedStatus = page.projectStatus(status, snapshotSequence, mergedItems)
        val cachedStatus = current?.let { cached -> projectedStatus.projectAfter(cached.status) } ?: projectedStatus
        val snapshot = cachedStatus.toSnapshotEntity(
            accountId = accountId,
            snapshotSequence = snapshotSequence,
            nextCursor = page.nextCursor.takeUnless { itemEntities.size == maxCachedItemCount },
            cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
            itemCount = itemEntities.size,
        )
        dao.replaceSnapshot(snapshot, itemEntities)
        snapshot.toCachedInbox(mergedItems)
    }

    suspend fun appendInbox(
        accountId: String,
        expectedSnapshotSequence: Long,
        expectedNextCursor: String,
        page: NotificationInboxPage,
        cachedAtEpochMilliseconds: Long,
    ): CachedNotificationInbox = lock.mutex.withLock {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        expectedSnapshotSequence.requirePositiveNotificationSequence("expectedSnapshotSequence")
        expectedNextCursor.requireNotificationCursor("expectedNextCursor")
        cachedAtEpochMilliseconds.requireNotificationStoreTimestamp("cachedAtEpochMilliseconds")
        val current = readInboxLocked(accountId) ?: throw NotificationCacheSnapshotMismatchException()
        current.requireMatchingAppend(expectedSnapshotSequence, expectedNextCursor, page)
        val mergedItems = current.items + page.items
        val itemEntities = mergedItems.toValidatedCacheEntities(
            accountId,
            expectedSnapshotSequence,
            maxCachedItemCount,
        )
        val snapshot = current.toAppendedSnapshot(
            accountId = accountId,
            page = page,
            itemCount = itemEntities.size,
            maximumItemCount = maxCachedItemCount,
            cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
        )
        dao.replaceSnapshot(snapshot, itemEntities)
        snapshot.toCachedInbox(mergedItems)
    }

    suspend fun storeStatus(
        accountId: String,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): CachedNotificationInbox = lock.mutex.withLock {
        storeStatusLocked(accountId, status, cachedAtEpochMilliseconds)
    }

    internal suspend fun storeStatusLocked(
        accountId: String,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): CachedNotificationInbox {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        cachedAtEpochMilliseconds.requireNotificationStoreTimestamp("cachedAtEpochMilliseconds")
        val current = readInboxLocked(accountId)
        if (current != null && status.isOlderThan(current.status)) return current
        if (current == null) return dao.storeStatusWithoutItems(accountId, status, cachedAtEpochMilliseconds)
        val updatedStatus = status.projectAfter(current.status)
        val updated = dao.updateStatus(updatedStatus.toStatusUpdate(accountId, cachedAtEpochMilliseconds))
        check(updated == 1) { "Notification status snapshot disappeared during its serialized update." }
        return current.copy(status = updatedStatus, cachedAtEpochMilliseconds = cachedAtEpochMilliseconds)
    }

    internal suspend fun prepareConfirmedStatusLocked(
        accountId: String,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): PreparedNotificationStatusSettlement {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        cachedAtEpochMilliseconds.requireNotificationStoreTimestamp("cachedAtEpochMilliseconds")
        val current = readInboxForConfirmedSettlementLocked(accountId)
        if (current != null && status.isOlderThan(current.status)) throw NotificationCacheSnapshotMismatchException()
        val updatedStatus = current?.let { cached -> status.projectAfter(cached.status) } ?: status
        return PreparedNotificationStatusSettlement(
            status = updatedStatus.toStatusUpdate(accountId, cachedAtEpochMilliseconds),
            snapshotWhenAbsent = if (current == null) {
                updatedStatus.toEmptySnapshotEntity(accountId, cachedAtEpochMilliseconds)
            } else {
                null
            },
        )
    }

    suspend fun applyItemMutation(accountId: String, mutation: NotificationItemMutation): Boolean =
        lock.mutex.withLock {
            applyItemMutationLocked(accountId, mutation)
        }

    internal suspend fun applyItemMutationLocked(accountId: String, mutation: NotificationItemMutation): Boolean {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        mutation.notificationId.requireCanonicalNotificationId()
        mutation.sequence.requirePositiveNotificationSequence("sequence")
        val inbox = readInboxLocked(accountId) ?: return false
        val current = inbox.items.firstOrNull { item ->
            item.id == mutation.notificationId && item.sequence == mutation.sequence
        } ?: return false
        val updated = current.mergeMutation(mutation)
        val updatedEntity = updated.toNotificationEntity(accountId)
        require(updatedEntity.toNotificationDomainOrNull(mutation.sequence) != null) {
            "Notification mutation produced invalid cached state."
        }
        val projectedStatus = inbox.status.projectAfterItemMutation(current, updated)
        dao.applyItemMutation(
            item = updatedEntity,
            status = projectedStatus.toStatusUpdate(accountId, inbox.cachedAtEpochMilliseconds),
        )
        return true
    }

    internal suspend fun prepareConfirmedItemAndStatusLocked(
        accountId: String,
        mutation: NotificationItemMutation,
        status: NotificationInboxStatus,
        cachedAtEpochMilliseconds: Long,
    ): PreparedNotificationItemSettlement {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        mutation.notificationId.requireCanonicalNotificationId()
        mutation.sequence.requirePositiveNotificationSequence("sequence")
        cachedAtEpochMilliseconds.requireNotificationStoreTimestamp("cachedAtEpochMilliseconds")
        val current = readInboxForConfirmedSettlementLocked(accountId)
        if (current != null && status.isOlderThan(current.status)) throw NotificationCacheSnapshotMismatchException()
        val currentById = current?.items?.firstOrNull { item -> item.id == mutation.notificationId }
        require(currentById == null || currentById.sequence == mutation.sequence) {
            "Notification mutation sequence differs from its cached item."
        }
        val updatedStatus = current?.let { cached -> status.projectAfter(cached.status) } ?: status
        val updatedItem = currentById?.mergeMutation(mutation)
        val updatedEntity = updatedItem?.toNotificationEntity(accountId)
        val updatedItemIsValid = updatedEntity == null ||
            updatedEntity.toNotificationDomainOrNull(checkNotNull(current).snapshotSequence) != null
        require(updatedItemIsValid) {
            "Notification mutation produced invalid cached state."
        }
        return PreparedNotificationItemSettlement(
            item = updatedEntity,
            status = updatedStatus.toStatusUpdate(accountId, cachedAtEpochMilliseconds),
            snapshotWhenAbsent = if (current == null) {
                updatedStatus.toEmptySnapshotEntity(accountId, cachedAtEpochMilliseconds)
            } else {
                null
            },
        )
    }

    suspend fun applyMarkAllRead(
        accountId: String,
        confirmation: NotificationMarkAllReadConfirmation,
        cachedAtEpochMilliseconds: Long,
    ): CachedNotificationInbox = lock.mutex.withLock {
        applyMarkAllReadLocked(accountId, confirmation, cachedAtEpochMilliseconds)
    }

    internal suspend fun applyMarkAllReadLocked(
        accountId: String,
        confirmation: NotificationMarkAllReadConfirmation,
        cachedAtEpochMilliseconds: Long,
    ): CachedNotificationInbox {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        cachedAtEpochMilliseconds.requireNotificationStoreTimestamp("cachedAtEpochMilliseconds")
        val current = readInboxLocked(accountId)
            ?: return dao.storeStatusWithoutItems(accountId, confirmation.status, cachedAtEpochMilliseconds)
        val status = if (confirmation.status.isOlderThan(current.status)) {
            current.status
        } else {
            confirmation.status.projectAfter(current.status)
        }
        val items = current.items.map { item -> item.markReadThrough(confirmation) }
        val snapshot = status.toSnapshotEntity(
            accountId = accountId,
            snapshotSequence = current.snapshotSequence,
            nextCursor = current.nextCursor,
            cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
            itemCount = items.size,
        )
        dao.applyMarkAllRead(
            throughSequence = confirmation.throughSequence,
            mutationAtEpochMilliseconds = confirmation.mutationAtEpochMilliseconds,
            snapshot = snapshot,
        )
        return current.copy(
            status = status,
            cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
            items = items,
        )
    }

    internal suspend fun prepareConfirmedMarkAllReadLocked(
        accountId: String,
        confirmation: NotificationMarkAllReadConfirmation,
        cachedAtEpochMilliseconds: Long,
    ): PreparedNotificationMarkAllReadSettlement {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        cachedAtEpochMilliseconds.requireNotificationStoreTimestamp("cachedAtEpochMilliseconds")
        val current = readInboxForConfirmedSettlementLocked(accountId)
        val status = when {
            current == null -> confirmation.status
            confirmation.status.isOlderThan(current.status) -> current.status
            else -> confirmation.status.projectAfter(current.status)
        }
        val snapshot = status.toSnapshotEntity(
            accountId = accountId,
            snapshotSequence = current?.snapshotSequence ?: status.latestSequence,
            nextCursor = current?.nextCursor,
            cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
            itemCount = current?.items?.size ?: 0,
        )
        return PreparedNotificationMarkAllReadSettlement(
            throughSequence = confirmation.throughSequence,
            mutationAtEpochMilliseconds = confirmation.mutationAtEpochMilliseconds,
            snapshot = snapshot,
            snapshotWasPresent = current != null,
        )
    }

    private suspend fun readInboxLocked(accountId: String): CachedNotificationInbox? {
        val snapshot = dao.findSnapshot(accountId) ?: return null
        if (!snapshot.isValid(accountId, maxCachedItemCount)) {
            dao.deleteInbox(accountId)
            return null
        }
        val items = mutableListOf<NotificationInboxItem>()
        var evicted = false
        dao.findItems(accountId).forEach { entity ->
            val item = entity.toNotificationDomainOrNull(snapshot.snapshotSequence)
            if (item == null || item.sequence > snapshot.snapshotSequence) {
                dao.deleteItem(accountId, entity.notificationId)
                evicted = true
            } else {
                items += item
            }
        }
        if (!items.isStrictNotificationOrder() || items.size > maxCachedItemCount) {
            dao.deleteInbox(accountId)
            return null
        }
        val repaired = if (evicted || snapshot.itemCount != items.size.toLong()) {
            dao.repairSnapshotAfterItemEviction(accountId, items.size.toLong())
            snapshot.copy(nextCursor = null, itemCount = items.size.toLong())
        } else {
            snapshot
        }
        return repaired.toCachedInbox(items.toList())
    }

    private suspend fun readInboxForConfirmedSettlementLocked(accountId: String): CachedNotificationInbox? {
        val snapshot = dao.findSnapshot(accountId) ?: return null
        if (!snapshot.isValid(accountId, maxCachedItemCount)) throw NotificationCacheSnapshotMismatchException()
        val items = dao.findItems(accountId).map { entity ->
            entity.toNotificationDomainOrNull(snapshot.snapshotSequence)
                ?.takeIf { item -> item.sequence <= snapshot.snapshotSequence }
                ?: throw NotificationCacheSnapshotMismatchException()
        }
        if (
            !items.isStrictNotificationOrder() ||
            items.size > maxCachedItemCount ||
            snapshot.itemCount != items.size.toLong()
        ) {
            throw NotificationCacheSnapshotMismatchException()
        }
        return snapshot.toCachedInbox(items)
    }

    private fun requireDurableStorage() {
        if (!isDurable) throw NotificationStorageUnavailableException()
    }
}

private suspend fun NotificationInboxDao.storeStatusWithoutItems(
    accountId: String,
    status: NotificationInboxStatus,
    cachedAtEpochMilliseconds: Long,
): CachedNotificationInbox {
    val snapshot = status.toSnapshotEntity(
        accountId = accountId,
        snapshotSequence = status.latestSequence,
        nextCursor = null,
        cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
        itemCount = 0,
    )
    replaceSnapshot(snapshot, emptyList())
    return snapshot.toCachedInbox(emptyList())
}

private fun NotificationInboxStatus.projectAfterItemMutation(
    current: NotificationInboxItem,
    updated: NotificationInboxItem,
): NotificationInboxStatus {
    val unseenDelta = updated.isUnseen(seenThroughSequence).toCountDelta() -
        current.isUnseen(seenThroughSequence).toCountDelta()
    val unreadDelta = updated.isUnread().toCountDelta() - current.isUnread().toCountDelta()
    return copy(
        unseenCount = (unseenCount + unseenDelta).coerceAtLeast(0),
        unreadCount = (unreadCount + unreadDelta).coerceAtLeast(0),
    )
}

private fun List<NotificationInboxItem>.toValidatedCacheEntities(
    accountId: String,
    snapshotSequence: Long,
    maximumItemCount: Int,
): List<NotificationInboxItemEntity> {
    if (size > maximumItemCount) throw NotificationCacheCapacityExceededException(maximumItemCount)
    require(isStrictNotificationOrder()) {
        "Notification cache items must be in strict newest-first order."
    }
    require(
        distinctBy(NotificationInboxItem::id).size == size &&
            distinctBy(NotificationInboxItem::sequence).size == size,
    ) { "Notification cache items must have unique IDs and sequences." }
    return map { item ->
        require(item.sequence <= snapshotSequence) {
            "Notification item sequence cannot exceed the cached snapshot."
        }
        item.toNotificationEntity(accountId).also { entity ->
            require(entity.toNotificationDomainOrNull(snapshotSequence) == item) {
                "Notification item is invalid for local persistence."
            }
        }
    }
}

private fun NotificationInboxStatus.toEmptySnapshotEntity(
    accountId: String,
    cachedAtEpochMilliseconds: Long,
) = toSnapshotEntity(
    accountId = accountId,
    snapshotSequence = latestSequence,
    nextCursor = null,
    cachedAtEpochMilliseconds = cachedAtEpochMilliseconds,
    itemCount = 0,
)
