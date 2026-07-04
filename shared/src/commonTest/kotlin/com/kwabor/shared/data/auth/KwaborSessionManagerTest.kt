package com.kwabor.shared.data.auth

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KwaborSessionManagerTest {
    @Test
    fun saveAndLoadSession_roundTripsSupabaseTokensInsideStore() = runTest {
        val manager = KwaborSessionManager(MemorySecureStringStore())
        val session = UserSession(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresIn = 3_600,
            tokenType = "bearer",
        )

        manager.saveSession(session)

        val loaded = manager.loadSession()
        assertEquals("access-token", loaded.accessToken)
        assertEquals("refresh-token", loaded.refreshToken)
    }

    @Test
    fun deleteSession_removesStoredSession() = runTest {
        val manager = KwaborSessionManager(MemorySecureStringStore())
        manager.saveSession(
            UserSession(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresIn = 3_600,
                tokenType = "bearer",
            ),
        )

        manager.deleteSession()

        assertNull(manager.loadSessionOrNull())
    }
}

private class MemorySecureStringStore : SecureStringStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun getStringOrNull(key: String): String? = values[key]

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
