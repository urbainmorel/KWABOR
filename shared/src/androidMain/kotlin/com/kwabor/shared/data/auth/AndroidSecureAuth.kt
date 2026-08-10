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
        check(preferences.edit().putString(key, value).commit()) {
            "Unable to durably save secure item"
        }
    }

    override suspend fun getStringOrNull(key: String): String? = preferences.getString(key, null)

    override suspend fun remove(key: String) {
        check(preferences.edit().remove(key).commit()) {
            "Unable to durably delete secure item"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "kwabor_auth_secure_store"
    }
}
