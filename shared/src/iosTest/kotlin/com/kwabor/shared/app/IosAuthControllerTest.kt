package com.kwabor.shared.app

import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionOutcome
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCleanupPendingCancellation
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.PromoterActivationResult
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.interaction.InteractionAccountDeletionPurgeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosAuthControllerTest {
    @Test
    fun passwordDeletionPurgesBeforeRemoteAndCarriesCapturedAccount() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(events = events)
        val controller = configuredController(repository, lifecycle)
        restore(controller)
        var completed = false

        controller.deleteAccountWithPassword(
            password = "valid-password",
            idempotencyKey = "delete-password-1",
        ) { success -> completed = success }

        assertTrue(completed)
        assertEquals(listOf("purge:$ACCOUNT_A", "remote:$ACCOUNT_A"), events)
        assertEquals(ACCOUNT_A, repository.deletionRequests.single().expectedAccountId)
        assertTrue(lifecycle.resumedAccountIds.isEmpty())
        controller.close()
    }

    @Test
    fun purgeFailurePreventsRemoteDeletionAndPublishesStorageMessage() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(
            events = events,
            purgeResult = DomainResult.Failure(DomainError.LocalStorageUnavailable()),
        )
        val controller = configuredController(repository, lifecycle)
        var observedState: AuthUiState? = null
        controller.observe { state -> observedState = state }
        restore(controller)
        var completed = true

        controller.deleteAccountWithPassword(
            password = "valid-password",
            idempotencyKey = "delete-password-2",
        ) { success -> completed = success }

        assertFalse(completed)
        assertEquals(listOf("purge:$ACCOUNT_A"), events)
        assertTrue(repository.deletionRequests.isEmpty())
        assertEquals(stringsFor(AppLocale.French).settings.privacyPersistenceError, observedState?.errorMessage)
        controller.close()
    }

    @Test
    fun alreadyBlockedPurgeNeverAcquiresOrResumesTheExistingFence() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(
            events = events,
            purgeResult = DomainResult.Success(InteractionAccountDeletionPurgeOutcome.AlreadyBlocked),
        )
        val controller = configuredController(repository, lifecycle)
        var observedState: AuthUiState? = null
        controller.observe { state -> observedState = state }
        restore(controller)
        var prepared: Boolean? = null

        controller.prepareAccountDeletion { result -> prepared = result }
        controller.cancelPreparedAccountDeletion { cancelled -> assertTrue(cancelled) }
        controller.deleteAccountWithPassword("valid-password", "blocked-password") { deleted ->
            assertFalse(deleted)
        }

        assertEquals(false, prepared)
        assertEquals(listOf("purge:$ACCOUNT_A", "purge:$ACCOUNT_A"), events)
        assertTrue(lifecycle.resumedAccountIds.isEmpty())
        assertTrue(repository.deletionRequests.isEmpty())
        assertEquals(stringsFor(AppLocale.French).authAccountDeletionOutcomeUnknown, observedState?.errorMessage)
        controller.close()
    }

    @Test
    fun capturedAccountARejectsAccountBAndResumesA() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(events = events)
        val controller = configuredController(repository, lifecycle)
        restore(controller)
        var prepared = false
        controller.prepareAccountDeletion { success -> prepared = success }
        assertTrue(prepared)

        repository.currentSession = authenticatedSession(ACCOUNT_B)
        restore(controller)
        var completed = true
        controller.deleteAccountWithSocial(
            request = socialRequest(),
            idempotencyKey = "delete-social-account-switch",
        ) { success -> completed = success }

        assertFalse(completed)
        assertTrue(repository.deletionRequests.isEmpty())
        assertEquals(listOf(ACCOUNT_A), lifecycle.resumedAccountIds)
        assertEquals(listOf("purge:$ACCOUNT_A", "resume:$ACCOUNT_A"), events)
        controller.close()
    }

    @Test
    fun explicitRemoteFailureResumesPreparedAccount() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(
            events = events,
            deletionResult = DomainResult.Failure(DomainError.NetworkUnavailable()),
        )
        val lifecycle = RecordingAccountDeletionLifecycle(events = events)
        val controller = configuredController(repository, lifecycle)
        restore(controller)
        controller.prepareAccountDeletion { prepared -> assertTrue(prepared) }
        var completed = true

        controller.deleteAccountWithSocial(
            request = socialRequest(),
            idempotencyKey = "delete-social-failure",
        ) { success -> completed = success }

        assertFalse(completed)
        assertEquals(ACCOUNT_A, repository.deletionRequests.single().expectedAccountId)
        assertEquals(listOf(ACCOUNT_A), lifecycle.resumedAccountIds)
        assertEquals(
            listOf("purge:$ACCOUNT_A", "remote:$ACCOUNT_A", "resume:$ACCOUNT_A"),
            events,
        )
        controller.close()
    }

    @Test
    fun successfulFederatedDeletionKeepsAccountBlocked() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(events = events)
        val controller = configuredController(repository, lifecycle)
        var observedState: AuthUiState? = null
        controller.observe { state -> observedState = state }
        restore(controller)
        controller.prepareAccountDeletion { prepared -> assertTrue(prepared) }
        var completed = false

        controller.deleteAccountWithSocial(
            request = socialRequest(),
            idempotencyKey = "delete-social-success",
        ) { success -> completed = success }

        assertTrue(completed)
        assertTrue(lifecycle.resumedAccountIds.isEmpty())
        assertNull(observedState?.currentSession)
        assertEquals(stringsFor(AppLocale.French).authAccountDeleted, observedState?.noticeMessage)
        controller.close()
    }

    @Test
    fun localCleanupPendingBlocksLiveSignInUntilSessionRestoreRetriesCleanup() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(
            events = events,
            deletionResult = DomainResult.Success(AccountDeletionOutcome.LocalCleanupPending),
        )
        val lifecycle = RecordingAccountDeletionLifecycle(events = events)
        val controller = configuredController(repository, lifecycle)
        restore(controller)
        val deletionCompletions = mutableListOf<Boolean>()
        var signedIn: Boolean? = null

        controller.deleteAccountWithPassword("password", "cleanup-pending", deletionCompletions::add)
        controller.signInWithEmail("other@kwabor.test", "password") { result -> signedIn = result }

        assertEquals(listOf(false), deletionCompletions)
        assertEquals(false, signedIn)
        assertEquals(listOf("purge:$ACCOUNT_A", "remote:$ACCOUNT_A"), events)
        assertTrue(lifecycle.resumedAccountIds.isEmpty())
        assertEquals(0, repository.signInWithEmailCalls)

        repository.currentSessionError = DomainError.LocalStorageUnavailable()
        controller.restoreSession { result -> assertTrue(result.isFailure) }
        controller.signInWithEmail("other@kwabor.test", "password") { result -> signedIn = result }
        assertEquals(0, repository.signInWithEmailCalls)

        repository.currentSessionError = null
        repository.currentSession = null
        controller.restoreSession { result -> assertTrue(result.isReady) }
        controller.signInWithEmail("other@kwabor.test", "password") { result -> signedIn = result }

        assertEquals(1, repository.signInWithEmailCalls)
        assertEquals(listOf(false), deletionCompletions)
        assertTrue(lifecycle.resumedAccountIds.isEmpty())
        controller.close()
    }

    @Test
    fun cancelledFederatedAttemptResumesPreparedAccount() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(events = events)
        val controller = configuredController(repository, lifecycle)
        restore(controller)
        controller.prepareAccountDeletion { prepared -> assertTrue(prepared) }
        var cancelled = false

        controller.cancelPreparedAccountDeletion { completed -> cancelled = completed }

        assertTrue(cancelled)
        assertEquals(listOf(ACCOUNT_A), lifecycle.resumedAccountIds)
        assertEquals(listOf("purge:$ACCOUNT_A", "resume:$ACCOUNT_A"), events)
        controller.close()
    }

    @Test
    fun cancellationDuringFederatedPreparationCannotArmTheAccountAfterward() {
        val events = mutableListOf<String>()
        val purgeGate = CompletableDeferred<Unit>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(events = events, afterAcquiredGate = purgeGate)
        val controller = configuredController(repository, lifecycle)
        restore(controller)
        var preparation: Boolean? = null
        var cancellation: Boolean? = null

        controller.prepareAccountDeletion { prepared -> preparation = prepared }
        controller.cancelPreparedAccountDeletion { completed -> cancellation = completed }
        purgeGate.complete(Unit)

        assertEquals(false, preparation)
        assertEquals(true, cancellation)
        assertEquals(listOf("purge:$ACCOUNT_A", "resume:$ACCOUNT_A"), events)
        assertEquals(listOf(ACCOUNT_A), lifecycle.resumedAccountIds)
        var deletionCompleted: Boolean? = null
        controller.deleteAccountWithSocial(
            request = socialRequest(),
            idempotencyKey = "delete-after-cancelled-preparation",
        ) { completed -> deletionCompleted = completed }
        assertEquals(false, deletionCompleted)
        assertFalse(events.any { event -> event.startsWith("remote:") })
        controller.close()
    }

    @Test
    fun passwordCancellationAfterPurgeAcquiresThenReleasesWithoutRemoteCall() {
        val events = mutableListOf<String>()
        val purgeGate = CompletableDeferred<Unit>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(events = events, afterAcquiredGate = purgeGate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var state = AuthUiState(currentSession = authenticatedSession(ACCOUNT_A))
        val coordinator = IosAccountDeletionCoordinator(
            presenter = AuthPresenter(repository),
            strings = stringsFor(AppLocale.French),
            coroutineScope = scope,
            interactionLifecycle = lifecycle.delegate,
            host = IosAccountDeletionHost(
                currentState = { state },
                publishState = { updatedState -> state = updatedState },
            ),
        )
        var completed: Boolean? = null

        coordinator.deleteWithPassword("password", "cancelled-password") { result -> completed = result }
        scope.cancel()
        purgeGate.complete(Unit)

        assertEquals(false, completed)
        assertEquals(listOf("purge:$ACCOUNT_A", "resume:$ACCOUNT_A"), events)
        assertTrue(repository.deletionRequests.isEmpty())
        assertEquals(listOf(ACCOUNT_A), lifecycle.resumedAccountIds)
    }

    @Test
    fun preTransportCancellationReleasesPreparedFence() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(
            events = events,
            deletionThrowable = AccountDeletionPreTransportCancellation(
                CancellationException("cancelled during reauthentication"),
            ),
        )
        val lifecycle = RecordingAccountDeletionLifecycle(events = events)
        val controller = configuredController(repository, lifecycle)
        restore(controller)
        var completed: Boolean? = null

        controller.deleteAccountWithPassword("password", "pre-transport") { result -> completed = result }

        assertEquals(false, completed)
        assertEquals(listOf("purge:$ACCOUNT_A", "remote:$ACCOUNT_A", "resume:$ACCOUNT_A"), events)
        assertEquals(listOf(ACCOUNT_A), lifecycle.resumedAccountIds)
        controller.close()
    }

    @Test
    fun remoteTransportCancellationKeepsCapturedAccountBlocked() {
        verifyAmbiguousRemoteOutcomeKeepsCapturedAccountBlocked(
            throwable = CancellationException("simulated transport cancellation"),
        )
    }

    @Test
    fun remoteTransportExceptionKeepsCapturedAccountBlocked() {
        val transportException = IllegalStateException("simulated ambiguous transport failure")
        verifyAmbiguousRemoteOutcomeKeepsCapturedAccountBlocked(
            throwable = transportException,
        )
    }

    @Test
    fun ambiguousAccountsRemainBlockedIndependentlyAcrossAccountSwitches() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(
            events = events,
            deletionResult = DomainResult.Success(AccountDeletionOutcome.OutcomeUnknown),
        )
        val lifecycle = RecordingAccountDeletionLifecycle(events = events)
        var state = AuthUiState(currentSession = authenticatedSession(ACCOUNT_A))
        val coordinator = IosAccountDeletionCoordinator(
            presenter = AuthPresenter(repository),
            strings = stringsFor(AppLocale.French),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            interactionLifecycle = lifecycle.delegate,
            host = IosAccountDeletionHost(
                currentState = { state },
                publishState = { updatedState -> state = updatedState },
            ),
        )

        coordinator.deleteWithPassword("password-a", "delete-a") { deleted -> assertFalse(deleted) }
        state = AuthUiState(currentSession = authenticatedSession(ACCOUNT_B))
        coordinator.deleteWithPassword("password-b", "delete-b") { deleted -> assertFalse(deleted) }
        val eventsBeforeReturningToA = events.toList()
        state = AuthUiState(currentSession = authenticatedSession(ACCOUNT_A))

        coordinator.prepareFederated { prepared -> assertFalse(prepared) }
        coordinator.deleteWithPassword("password-a", "retry-a") { deleted -> assertFalse(deleted) }

        assertEquals(eventsBeforeReturningToA, events)
        assertTrue(lifecycle.resumedAccountIds.isEmpty())
        assertEquals(2, repository.deletionRequests.size)
    }

    private fun configuredController(
        repository: AuthRepository,
        lifecycle: RecordingAccountDeletionLifecycle,
    ): IosAuthController = IosAuthController(
        presenter = AuthPresenter(repository),
        dispatcherProvider = IosAccountDeletionDispatcherProvider,
        accountDeletionInteractionLifecycle = lifecycle.delegate,
    )

    private fun restore(controller: IosAuthController) {
        controller.restoreSession { result -> assertTrue(result.isReady) }
    }

    private fun verifyAmbiguousRemoteOutcomeKeepsCapturedAccountBlocked(throwable: Throwable) {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(
            events = events,
            deletionThrowable = throwable,
        )
        val lifecycle = RecordingAccountDeletionLifecycle(events = events)
        val unhandledExceptions = mutableListOf<Throwable>()
        val scope = exceptionRecordingScope(unhandledExceptions)
        var state = AuthUiState(currentSession = authenticatedSession(ACCOUNT_A))
        val coordinator = IosAccountDeletionCoordinator(
            presenter = AuthPresenter(repository),
            strings = stringsFor(AppLocale.French),
            coroutineScope = scope,
            interactionLifecycle = lifecycle.delegate,
            host = IosAccountDeletionHost(
                currentState = { state },
                publishState = { updatedState -> state = updatedState },
            ),
        )
        coordinator.prepareFederated { prepared -> assertTrue(prepared) }
        var completion: Boolean? = null

        coordinator.deleteWithSocial(
            credential = AccountDeletionCredential.Social(socialRequest()),
            idempotencyKey = "delete-social-ambiguous",
        ) { completed -> completion = completed }
        coordinator.cancelPrepared { cancelled -> assertTrue(cancelled) }

        assertAmbiguousDeletionState(
            state = state,
            completion = completion,
            lifecycle = lifecycle,
            events = events,
            unhandledExceptions = unhandledExceptions,
        )
        assertSecondDeletionAttemptRemainsBlocked(coordinator, lifecycle, events)
        scope.cancel()
    }
}

class IosRejectedCleanupPendingTest {
    @Test
    fun explicitRejectionResumesFenceWhileCleanupRetryKeepsAuthFailClosed() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(
            events = events,
            deletionResult = DomainResult.Success(
                AccountDeletionOutcome.RejectedCleanupPending(
                    DomainError.Validation("error.auth.account_deletion_reauthentication_failed"),
                ),
            ),
        )
        val lifecycle = RecordingAccountDeletionLifecycle(events)
        val controller = IosAuthController(
            presenter = AuthPresenter(repository),
            dispatcherProvider = IosAccountDeletionDispatcherProvider,
            accountDeletionInteractionLifecycle = lifecycle.delegate,
        )
        controller.restoreSession { result -> assertTrue(result.isReady) }
        val deletionCompletions = mutableListOf<Boolean>()

        controller.deleteAccountWithPassword("password", "rejected-cleanup", deletionCompletions::add)
        controller.signInWithEmail("other@kwabor.test", "password") { }
        assertEquals(0, repository.signInWithEmailCalls)

        repository.currentSessionError = DomainError.LocalStorageUnavailable()
        controller.restoreSession { result -> assertTrue(result.isFailure) }
        controller.signInWithEmail("other@kwabor.test", "password") { }
        assertEquals(0, repository.signInWithEmailCalls)

        repository.currentSessionError = null
        repository.currentSession = null
        controller.restoreSession { result -> assertTrue(result.isReady) }
        controller.signInWithEmail("other@kwabor.test", "password") { }

        assertEquals(1, repository.signInWithEmailCalls)
        assertEquals(listOf(false), deletionCompletions)
        assertEquals(listOf("purge:$ACCOUNT_A", "remote:$ACCOUNT_A", "resume:$ACCOUNT_A"), events)
        assertEquals(listOf(ACCOUNT_A), lifecycle.resumedAccountIds)
        controller.close()
    }

    @Test
    fun cancelledPreTransportCleanupDebtResumesFenceAndKeepsAuthClosedUntilRetry() {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(
            events = events,
            deletionThrowable = AccountDeletionPreTransportCleanupPendingCancellation(
                CancellationException("cancelled with durable cleanup debt"),
            ),
        )
        val lifecycle = RecordingAccountDeletionLifecycle(events)
        val controller = IosAuthController(
            presenter = AuthPresenter(repository),
            dispatcherProvider = IosAccountDeletionDispatcherProvider,
            accountDeletionInteractionLifecycle = lifecycle.delegate,
        )
        var state: AuthUiState? = null
        controller.observe { updatedState -> state = updatedState }
        controller.restoreSession { result -> assertTrue(result.isReady) }
        val deletionCompletions = mutableListOf<Boolean>()

        controller.deleteAccountWithPassword("password", "cancelled-cleanup", deletionCompletions::add)
        controller.signInWithEmail("other@kwabor.test", "password") { }
        assertEquals(0, repository.signInWithEmailCalls)
        assertNull(state?.currentSession)
        assertEquals(stringsFor(AppLocale.French).settings.privacyPersistenceError, state?.errorMessage)

        repository.currentSessionError = DomainError.LocalStorageUnavailable()
        controller.restoreSession { result -> assertTrue(result.isFailure) }
        repository.currentSessionError = null
        repository.currentSession = null
        controller.restoreSession { result -> assertTrue(result.isReady) }
        controller.signInWithEmail("other@kwabor.test", "password") { }

        assertEquals(1, repository.signInWithEmailCalls)
        assertEquals(listOf(false), deletionCompletions)
        assertEquals(listOf("purge:$ACCOUNT_A", "remote:$ACCOUNT_A", "resume:$ACCOUNT_A"), events)
        assertEquals(listOf(ACCOUNT_A), lifecycle.resumedAccountIds)
        controller.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IosAccountDeletionPurgeTimeoutTest {
    @Test
    fun passwordTimeoutAfterAcquiredReleasesExactlyOnceWithoutRemoteCall() = runTest {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(
            events = events,
            afterAcquiredGate = CompletableDeferred(),
        )
        val fixture = timeoutCoordinator(repository, lifecycle)
        val completions = mutableListOf<Boolean>()

        fixture.coordinator.deleteWithPassword("password", "timed-out-password", completions::add)
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf(false), completions)
        assertPurgeWasReleasedWithoutRemote(events, lifecycle, repository)
    }

    @Test
    fun federatedTimeoutAfterAcquiredReleasesExactlyOnce() = runTest {
        val events = mutableListOf<String>()
        val repository = IosAccountDeletionAuthRepository(events = events)
        val lifecycle = RecordingAccountDeletionLifecycle(
            events = events,
            afterAcquiredGate = CompletableDeferred(),
        )
        val fixture = timeoutCoordinator(repository, lifecycle)
        val completions = mutableListOf<Boolean>()

        fixture.coordinator.prepareFederated(completions::add)
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf(false), completions)
        assertPurgeWasReleasedWithoutRemote(events, lifecycle, repository)
    }

    private fun kotlinx.coroutines.test.TestScope.timeoutCoordinator(
        repository: IosAccountDeletionAuthRepository,
        lifecycle: RecordingAccountDeletionLifecycle,
    ): IosTimeoutCoordinatorFixture {
        var state = AuthUiState(currentSession = authenticatedSession(ACCOUNT_A))
        val coordinator = IosAccountDeletionCoordinator(
            presenter = AuthPresenter(repository),
            strings = stringsFor(AppLocale.French),
            coroutineScope = this,
            interactionLifecycle = lifecycle.delegate,
            host = IosAccountDeletionHost(
                currentState = { state },
                publishState = { state = it },
                purgeTimeoutMilliseconds = 1L,
            ),
        )
        return IosTimeoutCoordinatorFixture(coordinator)
    }
}

private data class IosTimeoutCoordinatorFixture(val coordinator: IosAccountDeletionCoordinator)

private fun exceptionRecordingScope(unhandledExceptions: MutableList<Throwable>): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, exception ->
        unhandledExceptions += exception
    },
)

private fun assertPurgeWasReleasedWithoutRemote(
    events: List<String>,
    lifecycle: RecordingAccountDeletionLifecycle,
    repository: IosAccountDeletionAuthRepository,
) {
    assertEquals(listOf("purge:$ACCOUNT_A", "resume:$ACCOUNT_A"), events)
    assertEquals(listOf(ACCOUNT_A), lifecycle.resumedAccountIds)
    assertTrue(repository.deletionRequests.isEmpty())
}

private fun assertAmbiguousDeletionState(
    state: AuthUiState,
    completion: Boolean?,
    lifecycle: RecordingAccountDeletionLifecycle,
    events: MutableList<String>,
    unhandledExceptions: List<Throwable>,
) {
    assertEquals(false, completion)
    assertEquals(listOf("purge:$ACCOUNT_A", "remote:$ACCOUNT_A"), events)
    assertTrue(lifecycle.resumedAccountIds.isEmpty())
    assertTrue(unhandledExceptions.isEmpty())
    assertFalse(state.isLoading)
    assertEquals(stringsFor(AppLocale.French).authAccountDeletionOutcomeUnknown, state.errorMessage)
}

private fun assertSecondDeletionAttemptRemainsBlocked(
    coordinator: IosAccountDeletionCoordinator,
    lifecycle: RecordingAccountDeletionLifecycle,
    events: MutableList<String>,
) {
    var secondPreparation: Boolean? = null
    var secondCancellation: Boolean? = null
    coordinator.prepareFederated { prepared -> secondPreparation = prepared }
    coordinator.cancelPrepared { cancelled -> secondCancellation = cancelled }
    assertEquals(false, secondPreparation)
    assertEquals(true, secondCancellation)
    assertEquals(listOf("purge:$ACCOUNT_A", "remote:$ACCOUNT_A"), events)
    assertTrue(lifecycle.resumedAccountIds.isEmpty())
}

private class RecordingAccountDeletionLifecycle(
    private val events: MutableList<String>,
    purgeResult: DomainResult<InteractionAccountDeletionPurgeOutcome> =
        DomainResult.Success(InteractionAccountDeletionPurgeOutcome.Acquired(0)),
    purgeGate: CompletableDeferred<Unit>? = null,
    afterAcquiredGate: CompletableDeferred<Unit>? = null,
) {
    val resumedAccountIds = mutableListOf<String>()
    val delegate = IosAccountDeletionInteractionLifecycle(
        purgeAction = { accountId, onAcquired ->
            events += "purge:$accountId"
            purgeGate?.await()
            if (
                purgeResult is DomainResult.Success &&
                purgeResult.value is InteractionAccountDeletionPurgeOutcome.Acquired
            ) {
                onAcquired()
                afterAcquiredGate?.await()
            }
            purgeResult
        },
        resumeAction = { accountId ->
            resumedAccountIds += accountId
            events += "resume:$accountId"
        },
    )
}

private class IosAccountDeletionAuthRepository(
    private val events: MutableList<String>,
    private val deletionResult: DomainResult<AccountDeletionOutcome> =
        DomainResult.Success(AccountDeletionOutcome.Deleted),
    private val deletionThrowable: Throwable? = null,
) : AuthRepository {
    var currentSession: AuthSession? = authenticatedSession(ACCOUNT_A)
    var currentSessionError: DomainError? = null
    val deletionRequests = mutableListOf<AccountDeletionRequest>()
    var signInWithEmailCalls: Int = 0

    override suspend fun getCurrentSession(): DomainResult<AuthSession?> = currentSessionError
        ?.let { error -> DomainResult.Failure(error) }
        ?: DomainResult.Success(currentSession)

    override suspend fun deleteAccount(request: AccountDeletionRequest): DomainResult<AccountDeletionOutcome> {
        deletionRequests += request
        events += "remote:${request.expectedAccountId}"
        deletionThrowable?.let { throwable -> throw throwable }
        return deletionResult
    }

    override suspend fun signInWithEmail(email: String, password: String): DomainResult<AuthSession> {
        signInWithEmailCalls += 1
        return unused()
    }

    override suspend fun signInWithSocialProvider(request: SocialSignInRequest): DomainResult<AuthSession> = unused()

    override suspend fun signOut(): DomainResult<Unit> = unused()

    override suspend fun requestEmailOtp(email: String): DomainResult<Unit> = unused()

    override suspend fun verifyEmailOtp(email: String, otpCode: String): DomainResult<AuthSession> = unused()

    override suspend fun setInitialPassword(password: String): DomainResult<Unit> = unused()

    override suspend fun listActiveLegalDocuments(locale: AppLocale): DomainResult<List<LegalDocumentRevision>> =
        unused()

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): DomainResult<AuthSession> = unused()

    override suspend fun handlePromoterActivationCallback(
        callbackUrl: String,
    ): DomainResult<PromoterActivationContext> = unused()

    override suspend fun activatePromoterInvite(
        request: PromoterActivationRequest,
    ): DomainResult<PromoterActivationResult> = unused()

    override suspend fun requestPasswordRecovery(email: String): DomainResult<Unit> = unused()

    override suspend fun verifyPasswordRecoveryOtp(email: String, otpCode: String): DomainResult<AuthSession> = unused()

    override suspend fun completePasswordRecovery(newPassword: String): DomainResult<Unit> = unused()

    override suspend fun cancelPasswordRecovery(): DomainResult<Unit> = unused()

    private fun <T> unused(): DomainResult<T> = DomainResult.Failure(DomainError.Unexpected("unused"))
}

private object IosAccountDeletionDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
}

private fun authenticatedSession(accountId: String): AuthSession = AuthSession(
    userId = accountId,
    email = "$accountId@kwabor.test",
    expiresAtEpochMilliseconds = Long.MAX_VALUE,
    accountSetupStatus = AccountSetupStatus.Complete,
)

private fun socialRequest(): SocialSignInRequest = SocialSignInRequest(
    provider = SocialAuthProvider.Apple,
    idToken = "social-id-token",
    rawNonce = "social-raw-nonce",
)

private const val ACCOUNT_A = "10000000-0000-4000-8000-000000000001"
private const val ACCOUNT_B = "20000000-0000-4000-8000-000000000002"
