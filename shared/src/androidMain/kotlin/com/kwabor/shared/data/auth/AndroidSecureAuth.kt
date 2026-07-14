@file:Suppress("DEPRECATION")

package com.kwabor.shared.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.auth.SessionManager

fun createAndroidSecureAuthSessionManager(context: Context): SessionManager = KwaborSessionManager(
    store = AndroidSecureStringStore(context.applicationContext),
)

private class AndroidSecureStringStore(
    context: Context,
) : SecureStringStore {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        PREFERENCES_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override suspend fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override suspend fun getStringOrNull(key: String): String? = preferences.getString(key, null)

    override suspend fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "kwabor_auth_secure_store"
    }
}
