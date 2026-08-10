package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCancellation
import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCleanupPendingCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCleanupPendingCancellation
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            val outcome = dataSource.deleteAccount(passwordDeletionRequest())

            assertEquals(AccountDeletionDataOutcome.Deleted, outcome)
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
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
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
    fun mainSessionMismatchIsRejectedBeforeCreatingTheStepUpSession() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore()
        val stepUpSession = RecordingStepUpSession(reauthenticatedUserId = OTHER_USER_ID)
        var stepUpSessionCreations = 0
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory {
                stepUpSessionCreations += 1
                stepUpSession
            },
            passwordRecoverySessionStore = recoveryStore,
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            val exception = assertFailsWith<AuthDataException.Validation> {
                dataSource.deleteAccount(passwordDeletionRequest(expectedAccountId = OTHER_USER_ID))
            }

            assertEquals(AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY, exception.domainError.messageKey)
            assertEquals(0, stepUpSessionCreations)
            assertEquals(emptyList(), stepUpSession.calls)
            assertEquals(0, recoveryStore.clearCalls)
            assertNotNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun durableMarkerFailurePreventsEnteringDeletionTransport() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore().apply {
            markAccountDeletionFailure = IllegalStateException("secure write failed")
        }
        val stepUpSession = RecordingStepUpSession(reauthenticatedUserId = USER_ID)
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            assertFailsWith<AuthDataException.Unexpected> {
                dataSource.deleteAccount(passwordDeletionRequest())
            }

            assertEquals(listOf("reauthenticate", "close"), stepUpSession.calls)
            assertFalse(recoveryStore.accountDeletionCleanupPending)
            assertNotNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun networkFailureAfterInvokeReturnsUnknownAndClearsTheMainSession() = runTest {
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
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            val outcome = dataSource.deleteAccount(passwordDeletionRequest())

            assertEquals(AccountDeletionDataOutcome.OutcomeUnknown, outcome)
            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertEquals(1, recoveryStore.clearCalls)
            assertFalse(recoveryStore.accountDeletionCleanupPending)
            assertNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun serializationFailureAfterInvokeReturnsUnknownInsteadOfAnExplicitRejection() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore()
        val stepUpSession = RecordingStepUpSession(
            reauthenticatedUserId = USER_ID,
            invokeFailure = SerializationException("invalid Edge response"),
        )
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            val outcome = dataSource.deleteAccount(passwordDeletionRequest())

            assertEquals(AccountDeletionDataOutcome.OutcomeUnknown, outcome)
            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun cancellationStillClosesTheStepUpSessionAndClearsTheMainSession() = runTest {
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
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            assertFailsWith<AccountDeletionOutcomeUnknownCancellation> {
                dataSource.deleteAccount(passwordDeletionRequest())
            }

            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertEquals(1, recoveryStore.clearCalls)
            assertFalse(recoveryStore.accountDeletionCleanupPending)
            assertNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun cancellationDuringReauthenticationIsMarkedPreTransportAndKeepsTheMainSession() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore()
        val stepUpSession = RecordingStepUpSession(
            reauthenticatedUserId = USER_ID,
            reauthenticateFailure = CancellationException("cancelled before marker"),
        )
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            assertFailsWith<AccountDeletionPreTransportCancellation> {
                dataSource.deleteAccount(passwordDeletionRequest())
            }

            assertEquals(listOf("reauthenticate", "close"), stepUpSession.calls)
            assertFalse(recoveryStore.accountDeletionCleanupPending)
            assertNotNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun cancellationWhileResolvingPriorCleanupDebtRemainsOutcomeUnknown() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore().apply {
            accountDeletionCleanupPending = true
        }
        var stepUpSessionCreations = 0
        val coordinator = AccountDeletionSessionCoordinator(recoveryStore, recoveryStore)
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory {
                stepUpSessionCreations += 1
                RecordingStepUpSession(reauthenticatedUserId = USER_ID)
            },
            passwordRecoverySessionStore = recoveryStore,
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = AccountDeletionSessionGuard(coordinator) {
                throw CancellationException("cancelled while clearing prior deletion debt")
            },
        )

        try {
            assertFailsWith<AccountDeletionOutcomeUnknownCancellation> {
                dataSource.deleteAccount(passwordDeletionRequest())
            }

            assertEquals(0, stepUpSessionCreations)
            assertTrue(recoveryStore.accountDeletionCleanupPending)
            assertNotNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun stepUpCleanupFailureKeepsDurableMarkerAndReturnsLocalCleanupPending() = runTest {
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
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            val outcome = dataSource.deleteAccount(passwordDeletionRequest())

            assertEquals(AccountDeletionDataOutcome.LocalCleanupPending, outcome)
            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertEquals(1, recoveryStore.clearCalls)
            assertTrue(recoveryStore.accountDeletionCleanupPending)
            assertNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }

    @Test
    fun explicitRejectionWithMarkerCleanupFailureRemainsRejectedAndCleanupPending() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore().apply {
            clearAccountDeletionFailure = IllegalStateException("secure marker delete failed")
        }
        val rejection = AuthDataException.Validation(AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY)
        val stepUpSession = RecordingStepUpSession(
            reauthenticatedUserId = USER_ID,
            invokeFailure = rejection,
        )
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            val outcome = assertIs<AccountDeletionDataOutcome.RejectedCleanupPending>(
                dataSource.deleteAccount(passwordDeletionRequest()),
            )

            assertSame(rejection, outcome.rejection)
            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertTrue(recoveryStore.accountDeletionCleanupPending)
            assertNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }
}

class AccountDeletionCancellationBoundaryTest {
    @Test
    fun cancellationAfterMarkerBeforeTransportKeepsMainSession() = runTest {
        val fixture = cancellationBoundaryFixture()

        try {
            runDeletionUntilCancelled(fixture)

            assertIs<AccountDeletionPreTransportCancellation>(fixture.failure)
            assertEquals(listOf("reauthenticate", "close"), fixture.stepUpSession.calls)
            assertFalse(fixture.recoveryStore.accountDeletionCleanupPending)
            assertNotNull(fixture.mainClient.auth.currentSessionOrNull())
        } finally {
            fixture.mainClient.close()
        }
    }

    @Test
    fun markerCleanupFailureIsTypedAndClearsMainSession() = runTest {
        val fixture = cancellationBoundaryFixture().apply {
            recoveryStore.clearAccountDeletionFailure = IllegalStateException("secure marker delete failed")
        }

        try {
            runDeletionUntilCancelled(fixture)

            assertIs<AccountDeletionPreTransportCleanupPendingCancellation>(fixture.failure)
            assertEquals(listOf("reauthenticate", "close"), fixture.stepUpSession.calls)
            assertTrue(fixture.recoveryStore.accountDeletionCleanupPending)
            assertNull(fixture.mainClient.auth.currentSessionOrNull())
        } finally {
            fixture.mainClient.close()
        }
    }

    @Test
    fun remoteCancellationWithStepUpCleanupFailureKeepsMarkerAndClearsMainSession() = runTest {
        val mainClient = createMainAuthClientWithSession()
        val recoveryStore = RecordingPasswordRecoverySessionStore()
        val stepUpSession = RecordingStepUpSession(
            reauthenticatedUserId = USER_ID,
            invokeFailure = CancellationException("cancelled during invoke"),
            closeFailure = IllegalStateException("step-up cleanup failed"),
        )
        val dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        )

        try {
            assertFailsWith<AccountDeletionOutcomeUnknownCleanupPendingCancellation> {
                dataSource.deleteAccount(passwordDeletionRequest())
            }
            assertEquals(listOf("reauthenticate", "invoke", "close"), stepUpSession.calls)
            assertTrue(recoveryStore.accountDeletionCleanupPending)
            assertNull(mainClient.auth.currentSessionOrNull())
        } finally {
            mainClient.close()
        }
    }
}

private suspend fun cancellationBoundaryFixture(): AccountDeletionCancellationBoundaryFixture {
    val mainClient = createMainAuthClientWithSession()
    val recoveryStore = RecordingPasswordRecoverySessionStore()
    val stepUpSession = RecordingStepUpSession(reauthenticatedUserId = USER_ID)
    return AccountDeletionCancellationBoundaryFixture(
        mainClient = mainClient,
        recoveryStore = recoveryStore,
        stepUpSession = stepUpSession,
        dataSource = SupabaseAccountSecurityAuthDataSource(
            auth = mainClient.auth,
            stepUpSessionFactory = AccountDeletionStepUpSessionFactory { stepUpSession },
            passwordRecoverySessionStore = recoveryStore,
            accountDeletionSessionStore = recoveryStore,
            accountDeletionSessionGuard = recoveryStore.guard(mainClient.auth),
        ),
    )
}

private suspend fun TestScope.runDeletionUntilCancelled(fixture: AccountDeletionCancellationBoundaryFixture) {
    lateinit var deletionJob: Job
    fixture.recoveryStore.afterMarkAccountDeletion = {
        deletionJob.cancel(CancellationException("cancelled after durable marker"))
    }
    deletionJob = launch(start = CoroutineStart.LAZY) {
        fixture.failure = runCatching {
            fixture.dataSource.deleteAccount(passwordDeletionRequest())
        }.exceptionOrNull()
    }
    deletionJob.start()
    deletionJob.join()
}

private data class AccountDeletionCancellationBoundaryFixture(
    val mainClient: SupabaseClient,
    val recoveryStore: RecordingPasswordRecoverySessionStore,
    val stepUpSession: RecordingStepUpSession,
    val dataSource: SupabaseAccountSecurityAuthDataSource,
    var failure: Throwable? = null,
)

private class RecordingStepUpSession(
    private val reauthenticatedUserId: String?,
    private val reauthenticateFailure: Throwable? = null,
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
        reauthenticateFailure?.let { failure -> throw failure }
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

private class RecordingPasswordRecoverySessionStore : PasswordRecoverySessionStore, AccountDeletionSessionStore {
    var clearCalls: Int = 0
    var accountDeletionCleanupPending: Boolean = false
    var markAccountDeletionFailure: Throwable? = null
    var clearAccountDeletionFailure: Throwable? = null
    var afterMarkAccountDeletion: () -> Unit = {}

    override suspend fun markPasswordRecoveryInProgress() = Unit

    override suspend fun markPasswordUpdatedPendingCleanup() = Unit

    override suspend fun getPasswordRecoveryPhaseOrNull(): PasswordRecoverySessionPhase? = null

    override suspend fun isPasswordRecoveryInProgress(): Boolean = false

    override suspend fun clearPasswordRecovery() {
        clearCalls += 1
    }

    override suspend fun markAccountDeletionCleanupPending() {
        markAccountDeletionFailure?.let { failure -> throw failure }
        accountDeletionCleanupPending = true
        afterMarkAccountDeletion()
    }

    override suspend fun isAccountDeletionCleanupPending(): Boolean = accountDeletionCleanupPending

    override suspend fun clearAccountDeletionCleanupPending() {
        clearAccountDeletionFailure?.let { failure -> throw failure }
        accountDeletionCleanupPending = false
    }
}

private fun RecordingPasswordRecoverySessionStore.guard(auth: Auth): AccountDeletionSessionGuard =
    AccountDeletionSessionGuard(
        coordinator = AccountDeletionSessionCoordinator(
            accountDeletionStore = this,
            passwordRecoveryStore = this,
        ),
        clearCurrentSession = auth::clearSession,
    )

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

private fun passwordDeletionRequest(expectedAccountId: String = USER_ID) = AccountDeletionRequest(
    expectedAccountId = expectedAccountId,
    idempotencyKey = IDEMPOTENCY_KEY,
    credential = AccountDeletionCredential.Password("test-password"),
)

private const val USER_ID = "11111111-1111-4111-8111-111111111111"
private const val OTHER_USER_ID = "22222222-2222-4222-8222-222222222222"
private const val USER_EMAIL = "user@kwabor.test"
private const val IDEMPOTENCY_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
