package com.kwabor.shared.data.auth

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun loadSessionOrNull_purgesCorruptedSessionAndRecoveryMarker() = runTest {
        val store = MemorySecureStringStore()
        val manager = KwaborSessionManager(store)
        store.putString("kwabor.auth.session", "not-json")
        manager.markPasswordRecoveryInProgress()

        val session = manager.loadSessionOrNull()

        assertNull(session)
        assertNull(store.getStringOrNull("kwabor.auth.session"))
        assertFalse(manager.isPasswordRecoveryInProgress())
    }

    @Test
    fun recoveryMarker_survivesManagerRecreationUntilCleared() = runTest {
        val store = MemorySecureStringStore()
        val firstManager = KwaborSessionManager(store)
        firstManager.markPasswordRecoveryInProgress()

        val restoredManager = KwaborSessionManager(store)

        assertTrue(restoredManager.isPasswordRecoveryInProgress())
        restoredManager.clearPasswordRecovery()
        assertFalse(restoredManager.isPasswordRecoveryInProgress())
    }

    @Test
    fun invalidRecoveryOtp_clearsExistingStandardSessionAndRecoveryMarker() = runTest {
        val store = MemorySecureStringStore()
        val manager = KwaborSessionManager(store)
        val coordinator = PasswordRecoverySessionCoordinator(manager)
        manager.saveSession(
            UserSession(
                accessToken = "standard-access-token",
                refreshToken = "standard-refresh-token",
                expiresIn = 3_600,
                tokenType = "bearer",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.establishRecoverySession(
                clearCurrentSession = manager::deleteSession,
            ) {
                throw IllegalArgumentException("invalid otp")
            }
        }

        assertNull(manager.loadSessionOrNull())
        assertFalse(manager.isPasswordRecoveryInProgress())
    }

    @Test
    fun recoveryVerificationFailure_rollsBackSessionPersistedBeforeFailure() = runTest {
        val manager = KwaborSessionManager(MemorySecureStringStore())
        val coordinator = PasswordRecoverySessionCoordinator(manager)

        assertFailsWith<IllegalArgumentException> {
            coordinator.establishRecoverySession(clearCurrentSession = manager::deleteSession) {
                manager.saveSession(recoverySession("recovery-access-token"))
                throw IllegalArgumentException("verification failed after import")
            }
        }

        assertNull(manager.loadSessionOrNull())
        assertFalse(manager.isPasswordRecoveryInProgress())
    }

    @Test
    fun recoveryVerificationCancellation_rollsBackPersistedSessionUnderCancellation() = runTest {
        val manager = KwaborSessionManager(MemorySecureStringStore())
        val coordinator = PasswordRecoverySessionCoordinator(manager)

        assertFailsWith<CancellationException> {
            coordinator.establishRecoverySession(clearCurrentSession = manager::deleteSession) {
                manager.saveSession(recoverySession("cancelled-recovery-token"))
                throw CancellationException("cancelled after import")
            }
        }

        assertNull(manager.loadSessionOrNull())
        assertFalse(manager.isPasswordRecoveryInProgress())
    }

    @Test
    fun recoveryRollbackFailure_preservesMarkerFailClosed() = runTest {
        val manager = KwaborSessionManager(MemorySecureStringStore())
        val coordinator = PasswordRecoverySessionCoordinator(manager)
        var cleanupCalls = 0
        val clearSession: suspend () -> Unit = {
            cleanupCalls += 1
            if (cleanupCalls == 1) {
                manager.deleteSession()
            } else {
                error("secure session cleanup failed")
            }
        }

        assertFailsWith<IllegalArgumentException> {
            coordinator.establishRecoverySession(clearCurrentSession = clearSession) {
                manager.saveSession(recoverySession("preserved-recovery-token"))
                throw IllegalArgumentException("verification failed")
            }
        }

        assertEquals("preserved-recovery-token", manager.loadSessionOrNull()?.accessToken)
        assertTrue(manager.isPasswordRecoveryInProgress())
    }

    @Test
    fun recoveryRestore_preservesMarkerAcrossOfflineThenOnlineRefresh() = runTest {
        val manager = KwaborSessionManager(MemorySecureStringStore())
        val coordinator = PasswordRecoverySessionCoordinator(manager)
        val storedSession = recoverySession("expired-recovery-token")
        manager.saveSession(storedSession)
        manager.markPasswordRecoveryInProgress()

        val offlineSession = coordinator.restoreRecoverySessionOrNull(
            currentSession = null,
            loadStoredSession = manager::loadSessionOrNull,
            clearCurrentSession = manager::deleteSession,
        )
        val refreshedSession = recoverySession("refreshed-recovery-token")
        val onlineSession = coordinator.restoreRecoverySessionOrNull(
            currentSession = refreshedSession,
            loadStoredSession = manager::loadSessionOrNull,
            clearCurrentSession = manager::deleteSession,
        )

        assertEquals("expired-recovery-token", offlineSession?.accessToken)
        assertTrue(manager.isPasswordRecoveryInProgress())
        assertEquals("refreshed-recovery-token", onlineSession?.accessToken)
        assertTrue(manager.isPasswordRecoveryInProgress())
    }

    @Test
    fun recoveryCompletionCleanupFailure_preservesPendingPhaseAndRetrySkipsPasswordUpdate() = runTest {
        val manager = KwaborSessionManager(MemorySecureStringStore())
        val coordinator = PasswordRecoverySessionCoordinator(manager)
        manager.saveSession(recoverySession("recovery-access-token"))
        manager.markPasswordRecoveryInProgress()
        var passwordUpdateCalls = 0

        assertFailsWith<IllegalStateException> {
            coordinator.completeRecoverySession(
                hasCurrentSession = { true },
                missingSessionError = ::missingRecoverySessionError,
                updatePassword = { passwordUpdateCalls += 1 },
                clearCurrentSession = { throw IllegalStateException("session cleanup failed") },
            )
        }
        assertEquals(
            PasswordRecoverySessionPhase.PasswordUpdatedPendingCleanup,
            manager.getPasswordRecoveryPhaseOrNull(),
        )
        coordinator.completeRecoverySession(
            hasCurrentSession = { true },
            missingSessionError = ::missingRecoverySessionError,
            updatePassword = { passwordUpdateCalls += 1 },
            clearCurrentSession = manager::deleteSession,
        )

        assertEquals(1, passwordUpdateCalls)
        assertNull(manager.loadSessionOrNull())
        assertFalse(manager.isPasswordRecoveryInProgress())
    }

    @Test
    fun recoveryRestartWithPendingCleanup_autoCleansSessionWithoutPasswordUpdate() = runTest {
        val store = MemorySecureStringStore()
        val firstManager = KwaborSessionManager(store)
        firstManager.saveSession(recoverySession("updated-password-session"))
        firstManager.markPasswordUpdatedPendingCleanup()
        val restoredManager = KwaborSessionManager(store)
        val restoredCoordinator = PasswordRecoverySessionCoordinator(restoredManager)

        val session = restoredCoordinator.restoreRecoverySessionOrNull(
            currentSession = restoredManager.loadSessionOrNull(),
            loadStoredSession = restoredManager::loadSessionOrNull,
            clearCurrentSession = restoredManager::deleteSession,
        )

        assertNull(session)
        assertNull(restoredManager.loadSessionOrNull())
        assertFalse(restoredManager.isPasswordRecoveryInProgress())
    }

    @Test
    fun recoveryRestartPendingCleanupFailure_preservesFailClosedPhase() = runTest {
        val store = MemorySecureStringStore()
        val firstManager = KwaborSessionManager(store)
        firstManager.saveSession(recoverySession("updated-password-session"))
        firstManager.markPasswordUpdatedPendingCleanup()
        val restoredManager = KwaborSessionManager(store)
        val restoredCoordinator = PasswordRecoverySessionCoordinator(restoredManager)

        assertFailsWith<IllegalStateException> {
            restoredCoordinator.restoreRecoverySessionOrNull(
                currentSession = restoredManager.loadSessionOrNull(),
                loadStoredSession = restoredManager::loadSessionOrNull,
                clearCurrentSession = { throw IllegalStateException("session cleanup failed") },
            )
        }

        assertEquals(
            PasswordRecoverySessionPhase.PasswordUpdatedPendingCleanup,
            restoredManager.getPasswordRecoveryPhaseOrNull(),
        )
        assertEquals("updated-password-session", restoredManager.loadSessionOrNull()?.accessToken)
    }

    @Test
    fun passwordUpdateFailure_keepsRecoveryActiveAndPropagatesOriginalError() = runTest {
        val manager = KwaborSessionManager(MemorySecureStringStore())
        val coordinator = PasswordRecoverySessionCoordinator(manager)
        manager.saveSession(recoverySession("recovery-access-token"))
        manager.markPasswordRecoveryInProgress()

        val failure = assertFailsWith<IllegalArgumentException> {
            coordinator.completeRecoverySession(
                hasCurrentSession = { true },
                missingSessionError = ::missingRecoverySessionError,
                updatePassword = { throw IllegalArgumentException("same password") },
                clearCurrentSession = manager::deleteSession,
            )
        }

        assertEquals("same password", failure.message)
        assertEquals(
            PasswordRecoverySessionPhase.PasswordUpdateRequired,
            manager.getPasswordRecoveryPhaseOrNull(),
        )
        assertEquals("recovery-access-token", manager.loadSessionOrNull()?.accessToken)
    }

    @Test
    fun signOutFailure_preservesRecoveryMarkerFailClosed() = runTest {
        val manager = KwaborSessionManager(MemorySecureStringStore())
        val coordinator = PasswordRecoverySessionCoordinator(manager)
        manager.markPasswordRecoveryInProgress()

        assertFailsWith<IllegalStateException> {
            coordinator.signOut {
                throw IllegalStateException("sign out failed")
            }
        }

        assertTrue(manager.isPasswordRecoveryInProgress())
    }

    @Test
    fun cancelBeforeOtp_preservesExistingStandardSession() = runTest {
        val store = MemorySecureStringStore()
        val manager = KwaborSessionManager(store)
        val coordinator = PasswordRecoverySessionCoordinator(manager)
        manager.saveSession(
            UserSession(
                accessToken = "standard-access-token",
                refreshToken = "standard-refresh-token",
                expiresIn = 3_600,
                tokenType = "bearer",
            ),
        )

        coordinator.cancelRecoverySession(manager::deleteSession)

        assertEquals("standard-access-token", manager.loadSessionOrNull()?.accessToken)
        assertFalse(manager.isPasswordRecoveryInProgress())
    }
}

private fun missingRecoverySessionError(): Throwable = IllegalStateException("missing recovery session")

private fun recoverySession(accessToken: String): UserSession = UserSession(
    accessToken = accessToken,
    refreshToken = "recovery-refresh-token",
    expiresIn = 3_600,
    tokenType = "bearer",
)

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
