package com.kwabor.android.auth

import android.content.Context

internal interface PromoterActivationSessionStore {
    fun hasPendingImportedSession(): Boolean

    fun markImportedSessionPending(): Boolean

    fun clear(): Boolean
}

internal class SharedPreferencesPromoterActivationSessionStore(
    context: Context,
) : PromoterActivationSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun hasPendingImportedSession(): Boolean = preferences.getBoolean(KEY_IMPORTED_SESSION_PENDING, false)

    override fun markImportedSessionPending(): Boolean =
        preferences.edit().putBoolean(KEY_IMPORTED_SESSION_PENDING, true).commit()

    override fun clear(): Boolean = preferences.edit().remove(KEY_IMPORTED_SESSION_PENDING).commit()
}

private const val PREFERENCES_NAME = "kwabor_promoter_activation_session"
private const val KEY_IMPORTED_SESSION_PENDING = "imported_session_pending"
