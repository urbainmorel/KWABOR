package com.kwabor.shared.app

import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCancellation
import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCleanupPendingCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCleanupPendingCancellation
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.i18n.KwaborStrings
import com.kwabor.shared.presentation.auth.AccountDeletionActionResult
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.AuthUiState
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.interaction.InteractionAccountDeletionPurgeOutcome
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val ACCOUNT_DELETION_PURGE_TIMEOUT_MILLISECONDS = 5_000L

internal class IosAccountDeletionInteractionLifecycle(
    private val purgeAction: suspend (String, () -> Unit) -> DomainResult<InteractionAccountDeletionPurgeOutcome>,
    private val resumeAction: suspend (String) -> Unit,
) {
    constructor(interactionCoordinator: InteractionCoordinator?) : this(
        purgeAction = { accountId, onAcquired ->
            interactionCoordinator?.purgeForAccountDeletion(accountId, onAcquired)
                ?: DomainResult.Failure(DomainError.LocalStorageUnavailable())
        },
        resumeAction = { accountId ->
            interactionCoordinator?.resumeAfterAccountDeletionFailure(accountId)
        },
    )

    suspend fun purge(accountId: String, onAcquired: () -> Unit): IosAccountDeletionPurgeResult {
        val result = purgeAction(accountId, onAcquired)
        return when (result) {
            is DomainResult.Failure -> IosAccountDeletionPurgeResult.Failed
            is DomainResult.Success -> when (result.value) {
                InteractionAccountDeletionPurgeOutcome.AlreadyBlocked ->
                    IosAccountDeletionPurgeResult.AlreadyBlocked

                is InteractionAccountDeletionPurgeOutcome.Acquired -> IosAccountDeletionPurgeResult.Acquired
            }
        }
    }

    suspend fun resume(accountId: String) {
        withContext(NonCancellable) {
            resumeAction(accountId)
        }
    }
}

internal enum class IosAccountDeletionPurgeResult {
    Acquired,
    AlreadyBlocked,
    Failed,
}

internal class IosAccountDeletionHost(
    val currentState: () -> AuthUiState,
    val publishState: (AuthUiState) -> Unit,
    val onLocalCleanupPending: () -> Unit = {},
    val isClosed: () -> Boolean = { false },
    val purgeTimeoutMilliseconds: Long = ACCOUNT_DELETION_PURGE_TIMEOUT_MILLISECONDS,
)

internal class IosAccountDeletionCoordinator(
    private val presenter: AuthPresenter?,
    private val strings: KwaborStrings,
    private val coroutineScope: CoroutineScope,
    private val interactionLifecycle: IosAccountDeletionInteractionLifecycle,
    private val host: IosAccountDeletionHost,
) {
    private var preparedAccountId: String? = null
    private var activePreparation: IosFederatedDeletionPreparation? = null
    private val ambiguousAccountIds = mutableSetOf<String>()

    fun prepareFederated(onCompleted: (Boolean) -> Unit) {
        val accountId = accountIdForNewDeletion(onCompleted) ?: return
        val preparation = IosFederatedDeletionPreparation(onCompleted)
        activePreparation = preparation
        host.publishState(host.currentState().toAccountDeletionLoadingState())
        preparation.job = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runFederatedPreparation(accountId, preparation)
        }
    }

    private suspend fun runFederatedPreparation(accountId: String, preparation: IosFederatedDeletionPreparation) {
        val ownerJob = currentCoroutineContext()[Job]
        val purgeAttempt = IosAccountDeletionPurgeAttempt(accountId, interactionLifecycle, host)
        try {
            val purgeResult = purgeAttempt.await()
            withContext(NonCancellable) {
                completeFederatedPurge(purgeResult, ownerJob, accountId, preparation, purgeAttempt)
            }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                purgeAttempt.releaseIfAcquired()
                finishPreparation(preparation, prepared = false, errorMessage = null)
            }
            throw cancellation
        }
    }

    private suspend fun completeFederatedPurge(
        purgeResult: IosAccountDeletionPurgeResult?,
        ownerJob: Job?,
        accountId: String,
        preparation: IosFederatedDeletionPreparation,
        purgeAttempt: IosAccountDeletionPurgeAttempt,
    ) {
        val cancelled = preparation.cancelRequested ||
            activePreparation !== preparation || ownerJob?.isActive != true
        val accountChanged = !host.currentState().isCurrentAuthenticatedAccount(accountId)
        when {
            purgeResult == IosAccountDeletionPurgeResult.Acquired && !cancelled && !accountChanged -> {
                preparedAccountId = accountId
                finishPreparation(preparation, prepared = true, errorMessage = null)
            }

            purgeResult == IosAccountDeletionPurgeResult.AlreadyBlocked -> finishPreparation(
                preparation,
                prepared = false,
                errorMessage = strings.authAccountDeletionOutcomeUnknown,
            )

            purgeResult == IosAccountDeletionPurgeResult.Acquired -> {
                purgeAttempt.releaseIfAcquired()
                val errorMessage = if (accountChanged && !cancelled) strings.authSessionExpired else null
                finishPreparation(preparation, prepared = false, errorMessage = errorMessage)
            }

            else -> {
                purgeAttempt.releaseIfAcquired()
                finishPreparation(
                    preparation,
                    prepared = false,
                    errorMessage = strings.settings.privacyPersistenceError,
                )
            }
        }
    }

    fun cancelPrepared(onCompleted: (Boolean) -> Unit) {
        activePreparation?.let { preparation ->
            preparation.cancelRequested = true
            preparation.cancellationCallbacks += onCompleted
            preparation.job?.cancel()
            return
        }
        val accountId = preparedAccountId
        preparedAccountId = null
        if (accountId == null) {
            onCompleted.completeUnless(host.isClosed(), true)
            return
        }
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            interactionLifecycle.resume(accountId)
            if (!host.currentState().isLoading) host.publishState(host.currentState().toAccountDeletionReadyState())
            onCompleted.completeUnless(host.isClosed(), true)
        }
    }

    fun deleteWithPassword(password: String, idempotencyKey: String, onCompleted: (Boolean) -> Unit) {
        val accountId = accountIdForNewDeletion(onCompleted) ?: return
        val currentPresenter = presenter ?: run {
            onCompleted.completeUnless(host.isClosed(), false)
            return
        }
        host.publishState(host.currentState().toAccountDeletionLoadingState())
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runPasswordDeletion(
                IosPasswordDeletionCommand(
                    presenter = currentPresenter,
                    accountId = accountId,
                    password = password,
                    idempotencyKey = idempotencyKey,
                    onCompleted = onCompleted,
                ),
            )
        }
    }

    private suspend fun runPasswordDeletion(command: IosPasswordDeletionCommand) {
        val ownerJob = currentCoroutineContext()[Job]
        val purgeAttempt = IosAccountDeletionPurgeAttempt(command.accountId, interactionLifecycle, host)
        var remoteAttemptStarted = false
        try {
            val purgeResult = purgeAttempt.await()
            val deletionMayStart = withContext(NonCancellable) {
                settlePasswordPurge(
                    purgeResult = purgeResult,
                    settlement = IosPasswordPurgeSettlement(
                        ownerJob = ownerJob,
                        purgeAttempt = purgeAttempt,
                        command = command,
                        host = host,
                        strings = strings,
                    ),
                )
            }
            if (!deletionMayStart) return
            performDeletion(command.toDeletionExecution { remoteAttemptStarted = true })
        } catch (cancellation: CancellationException) {
            if (!remoteAttemptStarted) {
                withContext(NonCancellable) {
                    purgeAttempt.releaseIfAcquired()
                    host.publishState(host.currentState().toAccountDeletionReadyState())
                    command.onCompleted.completeUnless(host.isClosed(), false)
                }
            }
            throw cancellation
        }
    }

    fun deleteWithSocial(
        credential: AccountDeletionCredential.Social,
        idempotencyKey: String,
        onCompleted: (Boolean) -> Unit,
    ) {
        val accountId = preparedAccountId
        val currentPresenter = presenter
        if (accountId == null || currentPresenter == null) {
            if (currentPresenter != null) {
                host.publishState(host.currentState().toAccountDeletionFailureState(strings.authAccountDeletionFailed))
            }
            onCompleted.completeUnless(host.isClosed(), false)
            return
        }
        if (host.currentState().isLoading) {
            preparedAccountId = null
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                interactionLifecycle.resume(accountId)
                onCompleted.completeUnless(host.isClosed(), false)
            }
            return
        }
        host.publishState(host.currentState().toAccountDeletionLoadingState())
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            performDeletion(
                IosDeletionExecution(
                    presenter = currentPresenter,
                    request = AccountDeletionRequest(accountId, idempotencyKey, credential),
                    onCompleted = onCompleted,
                    onRemoteAttemptStarted = {},
                ),
            )
        }
    }

    private suspend fun performDeletion(execution: IosDeletionExecution) {
        val accountId = execution.request.expectedAccountId
        if (!validatePreparedAccount(accountId, execution.onCompleted)) return
        // The remote outcome becomes ambiguous once transport starts; only an explicit failure may release the block.
        preparedAccountId = null
        val result = runCatching {
            execution.onRemoteAttemptStarted()
            execution.presenter.deleteAccount(
                request = execution.request,
                strings = strings,
            )
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            handleDeletionException(accountId, execution.onCompleted, failure)
            return
        }
        result.getOrNull()?.let { deletionResult ->
            finishDeletionAction(accountId, execution.onCompleted, deletionResult)
        }
    }

    private suspend fun handleDeletionException(
        accountId: String,
        onCompleted: (Boolean) -> Unit,
        failure: Throwable,
    ) {
        when (failure) {
            is AccountDeletionPreTransportCleanupPendingCancellation -> withContext(NonCancellable) {
                host.onLocalCleanupPending()
                interactionLifecycle.resume(accountId)
                host.publishState(
                    initialAuthUiState().copy(errorMessage = strings.settings.privacyPersistenceError),
                )
                onCompleted.completeUnless(host.isClosed(), false)
            }

            is AccountDeletionPreTransportCancellation -> withContext(NonCancellable) {
                interactionLifecycle.resume(accountId)
                host.publishState(host.currentState().toAccountDeletionReadyState())
                onCompleted.completeUnless(host.isClosed(), false)
            }

            is AccountDeletionOutcomeUnknownCleanupPendingCancellation -> withContext(NonCancellable) {
                host.onLocalCleanupPending()
                finishAmbiguousDeletion(accountId, onCompleted)
            }

            is AccountDeletionOutcomeUnknownCancellation,
            is CancellationException,
            -> withContext(NonCancellable) {
                finishAmbiguousDeletion(accountId, onCompleted)
            }

            is Exception -> finishAmbiguousDeletion(accountId, onCompleted)
        }
        if (failure is CancellationException || failure !is Exception) throw failure
    }

    private suspend fun finishDeletionAction(
        accountId: String,
        onCompleted: (Boolean) -> Unit,
        result: AccountDeletionActionResult,
    ) {
        when (result) {
            AccountDeletionActionResult.Deleted -> {
                host.publishState(initialAuthUiState().copy(noticeMessage = strings.authAccountDeleted))
                onCompleted.completeUnless(host.isClosed(), true)
            }

            AccountDeletionActionResult.LocalCleanupPending -> {
                host.onLocalCleanupPending()
                finishAmbiguousDeletion(accountId, onCompleted)
            }

            AccountDeletionActionResult.OutcomeUnknown -> finishAmbiguousDeletion(accountId, onCompleted)

            is AccountDeletionActionResult.RejectedCleanupPending -> {
                host.onLocalCleanupPending()
                interactionLifecycle.resume(accountId)
                host.publishState(initialAuthUiState().copy(errorMessage = result.errorMessage))
                onCompleted.completeUnless(host.isClosed(), false)
            }

            is AccountDeletionActionResult.Rejected -> {
                interactionLifecycle.resume(accountId)
                host.publishState(host.currentState().toAccountDeletionFailureState(result.errorMessage))
                onCompleted.completeUnless(host.isClosed(), false)
            }
        }
    }

    private suspend fun validatePreparedAccount(accountId: String, onCompleted: (Boolean) -> Unit): Boolean {
        if (host.currentState().isCurrentAuthenticatedAccount(accountId)) return true
        preparedAccountId = null
        interactionLifecycle.resume(accountId)
        host.publishState(host.currentState().toAccountDeletionFailureState(strings.authSessionExpired))
        onCompleted.completeUnless(host.isClosed(), false)
        return false
    }

    private fun finishAmbiguousDeletion(accountId: String, onCompleted: (Boolean) -> Unit) {
        ambiguousAccountIds += accountId
        host.publishState(initialAuthUiState().copy(errorMessage = strings.authAccountDeletionOutcomeUnknown))
        onCompleted.completeUnless(host.isClosed(), false)
    }

    private fun finishPreparation(
        preparation: IosFederatedDeletionPreparation,
        prepared: Boolean,
        errorMessage: String?,
    ) {
        if (preparation.completed) return
        preparation.completed = true
        if (activePreparation === preparation) activePreparation = null
        val nextState = if (errorMessage != null) {
            host.currentState().toAccountDeletionFailureState(errorMessage)
        } else {
            host.currentState().toAccountDeletionReadyState()
        }
        host.publishState(nextState)
        preparation.onCompleted.completeUnless(host.isClosed(), prepared)
        if (!host.isClosed()) preparation.cancellationCallbacks.forEach { callback -> callback(true) }
        preparation.cancellationCallbacks.clear()
    }

    private fun accountIdForNewDeletion(onCompleted: (Boolean) -> Unit): String? {
        val state = host.currentState()
        if (deletionCannotStart(presenter, state, preparedAccountId, activePreparation)) {
            onCompleted.completeUnless(host.isClosed(), false)
            return null
        }
        val accountId = state.currentSession
            ?.takeIf { state.isAuthenticated }
            ?.userId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (accountId == null) {
            host.publishState(host.currentState().toAccountDeletionFailureState(strings.authSessionExpired))
            onCompleted.completeUnless(host.isClosed(), false)
            return null
        }
        if (accountId in ambiguousAccountIds) {
            host.publishState(
                host.currentState().toAccountDeletionFailureState(strings.authAccountDeletionOutcomeUnknown),
            )
            onCompleted.completeUnless(host.isClosed(), false)
            return null
        }
        return accountId
    }
}

private class IosFederatedDeletionPreparation(
    val onCompleted: (Boolean) -> Unit,
) {
    var job: Job? = null
    var cancelRequested: Boolean = false
    var completed: Boolean = false
    val cancellationCallbacks = mutableListOf<(Boolean) -> Unit>()
}

private class IosAccountDeletionPurgeAttempt(
    private val accountId: String,
    private val lifecycle: IosAccountDeletionInteractionLifecycle,
    private val host: IosAccountDeletionHost,
) {
    private var acquired = false
    private var released = false

    suspend fun await(): IosAccountDeletionPurgeResult? = withTimeoutOrNull(host.purgeTimeoutMilliseconds) {
        lifecycle.purge(accountId) {
            acquired = true
        }
    }

    suspend fun releaseIfAcquired() {
        if (!acquired || released) return
        released = true
        lifecycle.resume(accountId)
    }
}

private data class IosPasswordDeletionCommand(
    val presenter: AuthPresenter,
    val accountId: String,
    val password: String,
    val idempotencyKey: String,
    val onCompleted: (Boolean) -> Unit,
)

private data class IosDeletionExecution(
    val presenter: AuthPresenter,
    val request: AccountDeletionRequest,
    val onCompleted: (Boolean) -> Unit,
    val onRemoteAttemptStarted: () -> Unit,
)

private fun IosPasswordDeletionCommand.toDeletionExecution(onRemoteAttemptStarted: () -> Unit) = IosDeletionExecution(
    presenter = presenter,
    request = AccountDeletionRequest(
        expectedAccountId = accountId,
        idempotencyKey = idempotencyKey,
        credential = AccountDeletionCredential.Password(password),
    ),
    onCompleted = onCompleted,
    onRemoteAttemptStarted = onRemoteAttemptStarted,
)

private data class IosPasswordPurgeSettlement(
    val ownerJob: Job?,
    val purgeAttempt: IosAccountDeletionPurgeAttempt,
    val command: IosPasswordDeletionCommand,
    val host: IosAccountDeletionHost,
    val strings: KwaborStrings,
)

private suspend fun settlePasswordPurge(
    purgeResult: IosAccountDeletionPurgeResult?,
    settlement: IosPasswordPurgeSettlement,
): Boolean {
    if (purgeResult == IosAccountDeletionPurgeResult.Acquired) {
        if (settlement.ownerJob?.isActive == true) return true
        settlement.purgeAttempt.releaseIfAcquired()
        settlement.host.publishState(settlement.host.currentState().toAccountDeletionReadyState())
        settlement.command.onCompleted.completeUnless(settlement.host.isClosed(), false)
        return false
    }
    val errorMessage = if (purgeResult == IosAccountDeletionPurgeResult.AlreadyBlocked) {
        settlement.strings.authAccountDeletionOutcomeUnknown
    } else {
        settlement.purgeAttempt.releaseIfAcquired()
        settlement.strings.settings.privacyPersistenceError
    }
    settlement.host.publishState(settlement.host.currentState().toAccountDeletionFailureState(errorMessage))
    settlement.command.onCompleted.completeUnless(settlement.host.isClosed(), false)
    return false
}

private fun ((Boolean) -> Unit).completeUnless(closed: Boolean, result: Boolean) {
    if (!closed) invoke(result)
}

private fun AuthUiState.isCurrentAuthenticatedAccount(accountId: String): Boolean =
    isAuthenticated && currentSession?.userId?.trim() == accountId

private fun AuthUiState.toAccountDeletionLoadingState(): AuthUiState =
    copy(isLoading = true, errorMessage = null, noticeMessage = null)

private fun AuthUiState.toAccountDeletionReadyState(): AuthUiState =
    copy(isLoading = false, errorMessage = null, noticeMessage = null)

private fun AuthUiState.toAccountDeletionFailureState(message: String): AuthUiState =
    copy(isLoading = false, errorMessage = message, noticeMessage = null)

private fun deletionCannotStart(
    presenter: AuthPresenter?,
    state: AuthUiState,
    preparedAccountId: String?,
    activePreparation: IosFederatedDeletionPreparation?,
): Boolean {
    val deletionAlreadyStarted = state.isLoading || preparedAccountId != null
    val deletionUnavailable = presenter == null || activePreparation != null
    return deletionAlreadyStarted || deletionUnavailable
}
