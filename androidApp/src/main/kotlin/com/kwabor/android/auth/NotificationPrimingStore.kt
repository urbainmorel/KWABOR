package com.kwabor.android.auth

import android.content.Context
import android.content.SharedPreferences

internal interface NotificationPrimingStore {
    fun isResolved(): Boolean

    fun markResolved(): Boolean
}

internal class SharedPreferencesNotificationPrimingStore(
    context: Context,
) : NotificationPrimingStore {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun isResolved(): Boolean = preferences.getBoolean(KEY_RESOLVED, false)

    override fun markResolved(): Boolean = preferences.edit().putBoolean(KEY_RESOLVED, true).commit()
}

private const val PREFERENCES_NAME = "kwabor_notification_priming"
private const val KEY_RESOLVED = "resolved"
