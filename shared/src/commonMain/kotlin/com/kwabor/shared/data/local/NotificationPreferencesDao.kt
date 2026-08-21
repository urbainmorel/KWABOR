package com.kwabor.shared.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal abstract class NotificationPreferencesDao {
    @Query(
        """
        SELECT *
        FROM notification_preferences_cache
        WHERE account_id = :accountId
        ORDER BY family ASC
        """,
    )
    abstract suspend fun findPreferences(accountId: String): List<NotificationPreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPreferences(preferences: List<NotificationPreferenceEntity>)

    @Query("DELETE FROM notification_preferences_cache WHERE account_id = :accountId")
    protected abstract suspend fun deletePreferences(accountId: String): Int

    @Query(
        """
        DELETE FROM notification_preferences_cache
        WHERE account_id = :accountId
          AND family = :family
        """,
    )
    abstract suspend fun deletePreference(accountId: String, family: String): Int

    @Transaction
    open suspend fun replacePreferences(accountId: String, preferences: List<NotificationPreferenceEntity>) {
        deletePreferences(accountId)
        if (preferences.isNotEmpty()) insertPreferences(preferences)
    }
}
