package com.kwabor.shared.data.auth

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal interface SecureStringStore {
    suspend fun putString(key: String, value: String)

    suspend fun getStringOrNull(key: String): String?

    suspend fun remove(key: String)
}

internal interface PasswordRecoverySessionStore {
    suspend fun markPasswordRecoveryInProgress()

    suspend fun markPasswordUpdatedPendingCleanup()

    suspend fun getPasswordRecoveryPhaseOrNull(): PasswordRecoverySessionPhase?

    suspend fun isPasswordRecoveryInProgress(): Boolean

    suspend fun clearPasswordRecovery()
}

internal enum class PasswordRecoverySessionPhase {
    PasswordUpdateRequired,
    PasswordUpdatedPendingCleanup,
}

internal class PasswordRecoverySessionCoordinator(
    private val store: PasswordRecoverySessionStore,
) {
    suspend fun <T> establishRecoverySession(clearCurrentSession: suspend () -> Unit, verifyOtp: suspend () -> T): T {
        clearCurrentSession()
        store.markPasswordRecoveryInProgress()
        val verificationResult = runCatching { verifyOtp() }
        val verificationFailure = verificationResult.exceptionOrNull() ?: return verificationResult.getOrThrow()
        runCatching { rollbackRecoverySession(clearCurrentSession) }
            .exceptionOrNull()
            ?.let(verificationFailure::addSuppressed)
        throw verificationFailure
    }

    suspend fun <T> restoreRecoverySessionOrNull(
        currentSession: T?,
        loadStoredSession: suspend () -> T?,
        clearCurrentSession: suspend () -> Unit,
    ): T? {
        when (store.getPasswordRecoveryPhaseOrNull()) {
            null -> return null
            PasswordRecoverySessionPhase.PasswordUpdatedPendingCleanup -> {
                clearRecoverySessionNonCancellable(clearCurrentSession)
                return null
            }

            PasswordRecoverySessionPhase.PasswordUpdateRequired -> Unit
        }
        val recoverySession = currentSession ?: loadStoredSession()
        if (recoverySession == null) {
            store.clearPasswordRecovery()
        }
        return recoverySession
    }

    suspend fun completeRecoverySession(
        hasCurrentSession: suspend () -> Boolean,
        missingSessionError: () -> Throwable,
        updatePassword: suspend () -> Unit,
        clearCurrentSession: suspend () -> Unit,
    ) {
        when (store.getPasswordRecoveryPhaseOrNull()) {
            null -> throw missingSessionError()
            PasswordRecoverySessionPhase.PasswordUpdateRequired -> {
                if (!hasCurrentSession()) throw missingSessionError()
                updatePassword()
                withContext(NonCancellable) {
                    store.markPasswordUpdatedPendingCleanup()
                    clearRecoverySession(clearCurrentSession)
                }
            }

            PasswordRecoverySessionPhase.PasswordUpdatedPendingCleanup ->
                clearRecoverySessionNonCancellable(clearCurrentSession)
        }
    }

    suspend fun cancelRecoverySession(clearCurrentSession: suspend () -> Unit) {
        if (store.isPasswordRecoveryInProgress()) {
            clearRecoverySessionNonCancellable(clearCurrentSession)
        } else {
            store.clearPasswordRecovery()
        }
    }

    suspend fun signOut(signOutCurrentSession: suspend () -> Unit) {
        signOutCurrentSession()
        withContext(NonCancellable) {
            store.clearPasswordRecovery()
        }
    }

    private suspend fun rollbackRecoverySession(clearCurrentSession: suspend () -> Unit) {
        clearRecoverySessionNonCancellable(clearCurrentSession)
    }

    private suspend fun clearRecoverySessionNonCancellable(clearCurrentSession: suspend () -> Unit) {
        withContext(NonCancellable) {
            clearRecoverySession(clearCurrentSession)
        }
    }

    private suspend fun clearRecoverySession(clearCurrentSession: suspend () -> Unit) {
        clearCurrentSession()
        store.clearPasswordRecovery()
    }
}

internal class KwaborSessionManager(
    private val store: SecureStringStore,
    private val key: String = SESSION_KEY,
    private val json: Json = sessionJson,
) : SessionManager, PasswordRecoverySessionStore {
    override suspend fun saveSession(session: UserSession) {
        store.putString(key = key, value = json.encodeToString(session))
    }

    override suspend fun loadSession(): UserSession = loadSessionOrNull() ?: error("No session stored")

    override suspend fun loadSessionOrNull(): UserSession? {
        val stored = store.getStringOrNull(key) ?: return null
        return try {
            json.decodeFromString(stored)
        } catch (_: SerializationException) {
            store.remove(key)
            clearPasswordRecovery()
            null
        }
    }

    override suspend fun deleteSession() {
        store.remove(key)
    }

    override suspend fun markPasswordRecoveryInProgress() {
        store.putString(PASSWORD_RECOVERY_KEY, PASSWORD_RECOVERY_IN_PROGRESS_VALUE)
    }

    override suspend fun markPasswordUpdatedPendingCleanup() {
        store.putString(PASSWORD_RECOVERY_KEY, PASSWORD_UPDATED_PENDING_CLEANUP_VALUE)
    }

    override suspend fun getPasswordRecoveryPhaseOrNull(): PasswordRecoverySessionPhase? =
        when (store.getStringOrNull(PASSWORD_RECOVERY_KEY)) {
            null -> null
            PASSWORD_RECOVERY_IN_PROGRESS_VALUE -> PasswordRecoverySessionPhase.PasswordUpdateRequired
            PASSWORD_UPDATED_PENDING_CLEANUP_VALUE -> PasswordRecoverySessionPhase.PasswordUpdatedPendingCleanup
            else -> PasswordRecoverySessionPhase.PasswordUpdateRequired
        }

    override suspend fun isPasswordRecoveryInProgress(): Boolean = getPasswordRecoveryPhaseOrNull() != null

    override suspend fun clearPasswordRecovery() {
        store.remove(PASSWORD_RECOVERY_KEY)
    }

    private companion object {
        const val SESSION_KEY = "kwabor.auth.session"
        const val PASSWORD_RECOVERY_KEY = "kwabor.auth.password_recovery"
        const val PASSWORD_RECOVERY_IN_PROGRESS_VALUE = "in_progress"
        const val PASSWORD_UPDATED_PENDING_CLEANUP_VALUE = "password_updated_pending_cleanup"

        val sessionJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
