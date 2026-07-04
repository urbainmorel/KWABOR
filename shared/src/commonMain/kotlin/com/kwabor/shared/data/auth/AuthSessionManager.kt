package com.kwabor.shared.data.auth

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

internal interface SecureStringStore {
    suspend fun putString(key: String, value: String)

    suspend fun getStringOrNull(key: String): String?

    suspend fun remove(key: String)
}

internal class KwaborSessionManager(
    private val store: SecureStringStore,
    private val key: String = SESSION_KEY,
    private val json: Json = sessionJson,
) : SessionManager {
    override suspend fun saveSession(session: UserSession) {
        store.putString(key = key, value = json.encodeToString(session))
    }

    override suspend fun loadSession(): UserSession {
        val stored = store.getStringOrNull(key) ?: error("No session stored")
        return json.decodeFromString(stored)
    }

    override suspend fun loadSessionOrNull(): UserSession? = store.getStringOrNull(key)?.let(json::decodeFromString)

    override suspend fun deleteSession() {
        store.remove(key)
    }

    private companion object {
        const val SESSION_KEY = "kwabor.auth.session"

        val sessionJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
