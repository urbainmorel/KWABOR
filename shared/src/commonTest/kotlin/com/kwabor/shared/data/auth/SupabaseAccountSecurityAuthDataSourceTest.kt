package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.core.DomainError
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SupabaseAccountSecurityAuthDataSourceTest {
    @Test
    fun knownBusinessErrorKeepsItsDedicatedDomainError() {
        val cause = IllegalStateException("SDK response")

        val result = mapAccountDeletionError(
            errorCode = "organization_ownership_conflict",
            cause = cause,
        )

        val domainError = assertIs<DomainError.Validation>(result.domainError)
        assertEquals(AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY, domainError.messageKey)
        assertSame(cause, result.cause)
    }

    @Test
    fun unknownRestErrorNeverFallsBackToLegalDocumentsError() {
        val cause = IllegalStateException("SDK response")

        val result = mapAccountDeletionError(
            errorCode = "function_not_found",
            cause = cause,
        )

        assertIs<AuthDataException.Unexpected>(result)
        assertIs<DomainError.Unexpected>(result.domainError)
        assertSame(cause, result.cause)
    }

    @Test
    fun successfulDeletionUsesTheStepUpIdentityThenClearsTheMainSession() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore()
        val stepUpSession = RecordingStepUpSession(reauthenticatedUserId = USER_ID)
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
        )

        try {
            dataSource.deleteAccount(passwordDeletionRequest())

            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertEquals(USER_EMAIL, stepUpSession.lastEmail)
            assertEquals(IDEMPOTENCY_KEY, stepUpSession.lastIdempotencyKey)
            assertEquals(1, recoveryStore.clearCalls)
            assertNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun identityMismatchNeverInvokesDeletionAndKeepsTheMainSession() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore()
        val stepUpSession = RecordingStepUpSession(reauthenticatedUserId = OTHER_USER_ID)
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
        )

        try {
            val exception = assertFailsWith<AuthDataException.Validation> {
                dataSource.deleteAccount(passwordDeletionRequest())
            }

            assertEquals(AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY, exception.domainError.messageKey)
            assertEquals(listOf("reauthenticate", "close"), stepUpSession.calls)
            assertEquals(0, recoveryStore.clearCalls)
            assertNotNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun failedDeletionClosesTheStepUpSessionAndKeepsTheMainSession() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore()
        val expectedFailure = AuthDataException.NetworkUnavailable()
        val stepUpSession = RecordingStepUpSession(
            reauthenticatedUserId = USER_ID,
            invokeFailure = expectedFailure,
        )
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
        )

        try {
            val exception = assertFailsWith<AuthDataException.NetworkUnavailable> {
                dataSource.deleteAccount(passwordDeletionRequest())
            }

            assertSame(expectedFailure, exception)
            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertEquals(0, recoveryStore.clearCalls)
            assertNotNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun cancellationStillClosesTheStepUpSessionWithoutClearingTheMainSession() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore()
        val stepUpSession = RecordingStepUpSession(
            reauthenticatedUserId = USER_ID,
            invokeFailure = CancellationException("cancelled"),
        )
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
        )

        try {
            assertFailsWith<CancellationException> {
                dataSource.deleteAccount(passwordDeletionRequest())
            }

            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertEquals(0, recoveryStore.clearCalls)
            assertNotNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun serverSuccessClearsTheMainSessionEvenWhenStepUpCleanupFails() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore()
        val stepUpSession = RecordingStepUpSession(
            reauthenticatedUserId = USER_ID,
            closeFailure = IllegalStateException("cleanup failed"),
        )
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
        )

        try {
            dataSource.deleteAccount(passwordDeletionRequest())

            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertEquals(1, recoveryStore.clearCalls)
            assertNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }
}

private class RecordingStepUpSession(
    private val reauthenticatedUserId: String?,
    private val invokeFailure: Throwable? = null,
    private val closeFailure: Throwable? = null,
) : AccountDeletionStepUpSession {
    val calls = mutableListOf<String>()
    var lastEmail: String? = null
    var lastIdempotencyKey: String? = null

    override suspend fun reauthenticate(email: String?, credential: AccountDeletionCredential): String? {
        calls += "reauthenticate"
        lastEmail = email
        assertIs<AccountDeletionCredential.Password>(credential)
        return reauthenticatedUserId
    }

    override suspend fun invokeDeletion(idempotencyKey: String) {
        calls += "invoke"
        lastIdempotencyKey = idempotencyKey
        invokeFailure?.let { failure -> throw failure }
    }

    override suspend fun close() {
        calls += "close"
        closeFailure?.let { failure -> throw failure }
    }
}

private class RecordingPasswordRecoverySessionStore : PasswordRecoverySessionStore {
    var clearCalls: Int = 0

    override suspend fun markPasswordRecoveryInProgress() = Unit

    override suspend fun markPasswordUpdatedPendingCleanup() = Unit

    override suspend fun getPasswordRecoveryPhaseOrNull(): PasswordRecoverySessionPhase? = null

    override suspend fun isPasswordRecoveryInProgress(): Boolean = false

    override suspend fun clearPasswordRecovery() {
        clearCalls += 1
    }
}

private suspend fun createMainAuthClientWithSession(): SupabaseClient {
    val client = createSupabaseClient(
        supabaseUrl = "https://kwabor.test",
        supabaseKey = "publishable-test-key",
    ) {
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            minimalConfig()
        }
    }
    client.auth.awaitInitialization()
    client.auth.importSession(
        UserSession(
            accessToken = "main-access-token",
            refreshToken = "main-refresh-token",
            expiresIn = 3_600,
            tokenType = "bearer",
            user = UserInfo(
                aud = "authenticated",
                id = USER_ID,
                email = USER_EMAIL,
            ),
        ),
    )
    return client
}

private fun passwordDeletionRequest() = AccountDeletionRequest(
    idempotencyKey = IDEMPOTENCY_KEY,
    credential = AccountDeletionCredential.Password("test-password"),
)

private const val USER_ID = "11111111-1111-4111-8111-111111111111"
private const val OTHER_USER_ID = "22222222-2222-4222-8222-222222222222"
private const val USER_EMAIL = "user@kwabor.test"
private const val IDEMPOTENCY_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
