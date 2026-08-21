package com.kwabor.shared.data.notification

import com.kwabor.shared.data.local.NotificationPreferenceEntity
import com.kwabor.shared.data.local.NotificationPreferencesDao
import com.kwabor.shared.domain.notification.NotificationFamilyPreference
import com.kwabor.shared.domain.notification.NotificationPreferences
import kotlinx.coroutines.sync.withLock

internal class NotificationPreferencesStore(
    private val daoFactory: () -> NotificationPreferencesDao,
    internal val isDurable: Boolean,
    private val lock: NotificationStoreLock,
) {
    private val dao: NotificationPreferencesDao by lazy(daoFactory)

    internal constructor(
        dao: NotificationPreferencesDao,
        lock: NotificationStoreLock = NotificationStoreLock(),
    ) : this(daoFactory = { dao }, isDurable = true, lock = lock)

    suspend fun readPreferences(accountId: String): CachedNotificationPreferences = lock.mutex.withLock {
        readPreferencesLocked(accountId)
    }

    internal suspend fun readPreferencesLocked(accountId: String): CachedNotificationPreferences {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        val valid = dao.findPreferences(accountId).filter { entity ->
            val isValid = entity.toNotificationPreferenceDtoOrNull() != null
            if (!isValid) dao.deletePreference(accountId, entity.family)
            isValid
        }
        return valid.toCachedNotificationPreferences(accountId)
    }

    suspend fun replacePreferences(
        accountId: String,
        preferences: NotificationPreferences,
        cachedAtEpochMilliseconds: Long,
    ): CachedNotificationPreferences = lock.mutex.withLock {
        replacePreferencesLocked(accountId, preferences, cachedAtEpochMilliseconds)
    }

    internal suspend fun replacePreferencesLocked(
        accountId: String,
        preferences: NotificationPreferences,
        cachedAtEpochMilliseconds: Long,
    ): CachedNotificationPreferences {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        cachedAtEpochMilliseconds.requireNotificationStoreTimestamp("cachedAtEpochMilliseconds")
        val incoming = preferences.toNotificationPreferenceEntities(accountId, cachedAtEpochMilliseconds)
        val current = dao.findPreferences(accountId).filter { entity ->
            entity.toNotificationPreferenceDtoOrNull() != null
        }
        val entities = incoming.mergeNewestPreferences(
            current = current,
            timestampTiePolicy = NotificationPreferenceTimestampTiePolicy.KeepCached,
        )
        val validated = entities.toCachedNotificationPreferences(accountId)
        require(
            validated.cachedAtEpochMilliseconds != null &&
                validated.cachedAtEpochMilliseconds >= cachedAtEpochMilliseconds,
        ) {
            "Notification preferences are invalid for local persistence."
        }
        dao.replacePreferences(accountId, entities)
        return validated
    }

    internal suspend fun prepareConfirmedPreferenceLocked(
        accountId: String,
        preference: NotificationFamilyPreference,
        cachedAtEpochMilliseconds: Long,
    ): List<NotificationPreferenceEntity> {
        requireDurableStorage()
        accountId.requireCanonicalNotificationAccountId()
        cachedAtEpochMilliseconds.requireNotificationStoreTimestamp("cachedAtEpochMilliseconds")
        val stored = dao.findPreferences(accountId)
        val current = stored.filter { entity ->
            entity.toNotificationPreferenceDtoOrNull() != null
        }
        if (current.size != stored.size) throw NotificationCacheSnapshotMismatchException()
        val cached = current.toCachedNotificationPreferences(accountId)
        val updated = cached.preferences.copy(
            entries = cached.preferences.entries.map { existing ->
                if (existing.family == preference.family) preference else existing
            },
        )
        val entities = updated.toNotificationPreferenceEntities(accountId, cachedAtEpochMilliseconds)
            .mergeNewestPreferences(
                current = current,
                timestampTiePolicy = NotificationPreferenceTimestampTiePolicy.PreferIncoming,
            )
        require(entities.toCachedNotificationPreferences(accountId).cachedAtEpochMilliseconds != null) {
            "Notification preferences are invalid for confirmed local persistence."
        }
        return entities
    }

    private fun requireDurableStorage() {
        if (!isDurable) throw NotificationStorageUnavailableException()
    }
}

private fun List<NotificationPreferenceEntity>.mergeNewestPreferences(
    current: List<NotificationPreferenceEntity>,
    timestampTiePolicy: NotificationPreferenceTimestampTiePolicy,
): List<NotificationPreferenceEntity> {
    val currentByFamily = current.associateBy(NotificationPreferenceEntity::family)
    return map { incoming ->
        val cached = currentByFamily[incoming.family]
        val selected = when {
            cached == null -> incoming
            cached.updatedAtEpochMilliseconds == null && incoming.updatedAtEpochMilliseconds != null -> incoming
            cached.updatedAtEpochMilliseconds != null && incoming.updatedAtEpochMilliseconds == null -> cached
            cached.updatedAtEpochMilliseconds != null &&
                incoming.updatedAtEpochMilliseconds != null &&
                (
                    incoming.updatedAtEpochMilliseconds > cached.updatedAtEpochMilliseconds ||
                        incoming.updatedAtEpochMilliseconds == cached.updatedAtEpochMilliseconds &&
                        timestampTiePolicy == NotificationPreferenceTimestampTiePolicy.PreferIncoming
                ) -> incoming
            else -> cached
        }
        selected.copy(
            cachedAtEpochMilliseconds = maxOf(
                selected.cachedAtEpochMilliseconds,
                incoming.cachedAtEpochMilliseconds,
            ),
        )
    }
}

private enum class NotificationPreferenceTimestampTiePolicy {
    KeepCached,
    PreferIncoming,
}
