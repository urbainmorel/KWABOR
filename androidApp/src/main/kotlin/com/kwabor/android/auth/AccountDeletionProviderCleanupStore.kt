package com.kwabor.android.auth

import android.content.Context

internal interface AccountDeletionProviderCleanupStore {
    fun hasPendingCleanup(): Boolean

    fun markPending(): Boolean

    fun clear(): Boolean
}

internal class SharedPreferencesAccountDeletionProviderCleanupStore(
    context: Context,
) : AccountDeletionProviderCleanupStore {
    private val applicationContext = context.applicationContext
    private val preferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    override fun hasPendingCleanup(): Boolean = readPendingOrNull() ?: true

    override fun markPending(): Boolean {
        when (readPendingOrNull()) {
            true -> return true
            null -> return false
            false -> Unit
        }
        val committed = preferences.edit().putBoolean(KEY_PENDING_CLEANUP, true).commit()
        return committed && readPendingOrNull() == true
    }

    override fun clear(): Boolean {
        if (readPendingOrNull() == false) return true
        val committed = preferences.edit().remove(KEY_PENDING_CLEANUP).commit()
        return committed && readPendingOrNull() == false
    }

    private fun readPendingOrNull(): Boolean? = try {
        preferences.getBoolean(KEY_PENDING_CLEANUP, false)
    } catch (_: ClassCastException) {
        null
    }
}

private const val PREFERENCES_NAME = "kwabor_account_deletion_provider_cleanup"
private const val KEY_PENDING_CLEANUP = "pending_cleanup"
