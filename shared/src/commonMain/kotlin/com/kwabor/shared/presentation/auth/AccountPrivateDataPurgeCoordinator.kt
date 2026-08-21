package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AccountPrivateDataPurgeRepository
import com.kwabor.shared.domain.auth.AccountPrivateDataPurgeResult
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.presentation.interaction.InteractionCoordinator
import com.kwabor.shared.presentation.interaction.InteractionDeletionBlockRegistration
import com.kwabor.shared.presentation.interaction.InteractionDeletionBlockToken
import com.kwabor.shared.presentation.notification.NotificationDeletionBlockRegistration
import com.kwabor.shared.presentation.notification.NotificationDeletionBlockToken
import com.kwabor.shared.presentation.notification.NotificationRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

sealed interface AccountPrivateDataPurgeOutcome {
    data class Acquired(
        val result: AccountPrivateDataPurgeResult,
        val ownership: AccountPrivateDataPurgeOwnership,
    ) : AccountPrivateDataPurgeOutcome

    data class PostCommitRecoveryRequired(
        val ownership: AccountPrivateDataPurgeOwnership,
    ) : AccountPrivateDataPurgeOutcome

    data object AlreadyBlocked : AccountPrivateDataPurgeOutcome
}

class AccountPrivateDataPurgeOwnership(
    internal val accountId: String,
)

class AccountPrivateDataPurgeCoordinator internal constructor(
    private val repository: AccountPrivateDataPurgeRepository,
    private val interactionParticipant: AccountPrivateDataPurgeParticipant,
    private val notificationParticipant: AccountPrivateDataPurgeParticipant,
    private val workerScope: CoroutineScope,
) {
    internal constructor(
        repository: AccountPrivateDataPurgeRepository,
        interactionCoordinator: InteractionCoordinator,
        notificationRuntime: NotificationRuntime,
        workerScope: CoroutineScope,
    ) : this(
        repository = repository,
        interactionParticipant = InteractionPrivateDataPurgeParticipant(interactionCoordinator),
        notificationParticipant = NotificationPrivateDataPurgeParticipant(notificationRuntime),
        workerScope = workerScope,
    )

    private val mutex = Mutex()
    private val attempts = mutableMapOf<String, AccountPrivateDataPurgeAttempt>()
    private val ownershipStates = mutableMapOf<String, AccountPrivateDataPurgeOwnershipState>()

    suspend fun purgeForAccountDeletion(
        expectedAccountId: String,
    ): DomainResult<AccountPrivateDataPurgeOutcome> = purgeForAccountDeletion(expectedAccountId, onAcquired = { _ -> })

    internal suspend fun purgeForAccountDeletion(
        expectedAccountId: String,
        onAcquired: (AccountPrivateDataPurgeOwnership) -> Unit,
    ): DomainResult<AccountPrivateDataPurgeOutcome> {
        val accountId = expectedAccountId.toCanonicalPrivateDataPurgeAccountId()
            ?: return DomainResult.Failure(DomainError.Validation(INVALID_ACCOUNT_ID_ERROR_KEY))
        var existingOutcome: AccountPrivateDataPurgeOutcome? = null
        val attempt = mutex.withLock {
            if (accountId in attempts) return@withLock null
            ownershipStates[accountId]?.let { state ->
                existingOutcome = if (state.recoveryRequired) {
                    AccountPrivateDataPurgeOutcome.PostCommitRecoveryRequired(state.ownership)
                } else {
                    AccountPrivateDataPurgeOutcome.AlreadyBlocked
                }
                return@withLock null
            }
            AccountPrivateDataPurgeAttempt(accountId).also { current -> attempts[accountId] = current }
        }
        existingOutcome?.let { outcome -> return DomainResult.Success(outcome) }
        if (attempt == null) return DomainResult.Success(AccountPrivateDataPurgeOutcome.AlreadyBlocked)
        workerScope.launch { execute(attempt) }
        val result = try {
            attempt.completion.await()
        } catch (cancellation: CancellationException) {
            workerScope.launch {
                val lateResult = runCatching { attempt.completion.await() }.getOrNull()
                lateResult.ownedBlockOrNull()?.let { ownership ->
                    resumeAfterAccountDeletionFailure(ownership)
                }
            }
            throw cancellation
        }
        val ownership = result.ownedBlockOrNull()
        if (ownership != null) {
            try {
                coroutineContext.ensureActive()
                onAcquired(ownership)
            } catch (cancellation: CancellationException) {
                workerScope.launch { resumeAfterAccountDeletionFailure(ownership) }
                throw cancellation
            }
        }
        return result
    }

    suspend fun resumeAfterAccountDeletionFailure(ownership: AccountPrivateDataPurgeOwnership): Boolean {
        val state = mutex.withLock {
            ownershipStates[ownership.accountId]?.takeIf { current -> current.ownership === ownership }
        } ?: return false
        return withContext(NonCancellable) {
            state.settlementMutex.withLock settlement@{
                val stillOwned = mutex.withLock { ownershipStates[ownership.accountId] === state }
                if (!stillOwned) return@settlement false
                settleCommittedBlockLocked(state)
                if (!state.interactionResumed) {
                    state.interactionResumed = runPostCommitStep {
                        interactionParticipant.resume(ownership.accountId)
                    }
                }
                if (!state.notificationResumed) {
                    state.notificationResumed = runPostCommitStep {
                        notificationParticipant.resume(ownership.accountId)
                    }
                }
                val resumed = state.interactionResumed && state.notificationResumed
                mutex.withLock terminal@{
                    if (ownershipStates[ownership.accountId] !== state) return@terminal false
                    if (resumed) {
                        state.terminal = AccountPrivateDataPurgeOwnershipTerminal.Resumed
                        ownershipStates.remove(ownership.accountId)
                    } else {
                        state.recoveryRequired = true
                    }
                    resumed
                }
            }
        }
    }

    suspend fun retainBlockAfterAccountDeletion(ownership: AccountPrivateDataPurgeOwnership): Boolean {
        val state = mutex.withLock {
            ownershipStates[ownership.accountId]?.takeIf { current -> current.ownership === ownership }
        } ?: return false
        return withContext(NonCancellable) {
            state.settlementMutex.withLock {
                mutex.withLock ownership@{
                    if (
                        ownershipStates[ownership.accountId] !== state ||
                        state.recoveryRequired ||
                        state.terminal != AccountPrivateDataPurgeOwnershipTerminal.Owned
                    ) {
                        return@ownership false
                    }
                    state.terminal = AccountPrivateDataPurgeOwnershipTerminal.Retained
                    ownershipStates.remove(ownership.accountId)
                    true
                }
            }
        }
    }

    private suspend fun settleCommittedBlock(state: AccountPrivateDataPurgeOwnershipState): Boolean =
        state.settlementMutex.withLock {
            settleCommittedBlockLocked(state)
        }

    private suspend fun settleCommittedBlockLocked(state: AccountPrivateDataPurgeOwnershipState): Boolean {
        if (!state.interactionInvalidated) {
            state.interactionInvalidated = runPostCommitUnitStep {
                interactionParticipant.invalidate(state.ownership.accountId)
            }
        }
        if (!state.notificationInvalidated) {
            state.notificationInvalidated = runPostCommitUnitStep {
                notificationParticipant.invalidate(state.ownership.accountId)
            }
        }
        if (state.notificationInvalidated && !state.notificationFinished) {
            state.notificationFinished = runPostCommitStep {
                notificationParticipant.finish(state.notificationToken, committed = true)
            }
        }
        if (state.interactionInvalidated && !state.interactionFinished) {
            state.interactionFinished = runPostCommitStep {
                interactionParticipant.finish(state.interactionToken, committed = true)
            }
        }
        return state.interactionInvalidated &&
            state.notificationInvalidated &&
            state.interactionFinished &&
            state.notificationFinished
    }

    private suspend fun execute(attempt: AccountPrivateDataPurgeAttempt) {
        var interactionOwner: AccountPrivateDataPurgeBlockRegistration.Owner? = null
        var notificationOwner: AccountPrivateDataPurgeBlockRegistration.Owner? = null
        val result = try {
            interactionOwner = when (val registration = interactionParticipant.register(attempt.accountId)) {
                AccountPrivateDataPurgeBlockRegistration.AlreadyBlocked ->
                    return complete(attempt, DomainResult.Success(AccountPrivateDataPurgeOutcome.AlreadyBlocked))
                is AccountPrivateDataPurgeBlockRegistration.Owner -> registration
            }
            notificationOwner = when (val registration = notificationParticipant.register(attempt.accountId)) {
                AccountPrivateDataPurgeBlockRegistration.AlreadyBlocked -> {
                    check(
                        interactionParticipant.finish(
                            checkNotNull(interactionOwner).token,
                            committed = false,
                        ),
                    )
                    interactionOwner = null
                    return complete(attempt, DomainResult.Success(AccountPrivateDataPurgeOutcome.AlreadyBlocked))
                }
                is AccountPrivateDataPurgeBlockRegistration.Owner -> registration
            }
            interactionOwner.idle?.await()
            notificationOwner.idle?.await()
            withContext(NonCancellable) {
                when (val purge = repository.purge(attempt.accountId)) {
                    is DomainResult.Failure -> purge
                    is DomainResult.Success -> {
                        val ownership = AccountPrivateDataPurgeOwnership(attempt.accountId)
                        val state = AccountPrivateDataPurgeOwnershipState(
                            ownership = ownership,
                            interactionToken = checkNotNull(interactionOwner).token,
                            notificationToken = checkNotNull(notificationOwner).token,
                        )
                        mutex.withLock { ownershipStates[attempt.accountId] = state }
                        notificationOwner = null
                        interactionOwner = null
                        val settled = settleCommittedBlock(state)
                        mutex.withLock { state.recoveryRequired = !settled }
                        if (settled) {
                            DomainResult.Success(AccountPrivateDataPurgeOutcome.Acquired(purge.value, ownership))
                        } else if (resumeAfterAccountDeletionFailure(ownership)) {
                            DomainResult.Failure(DomainError.LocalStorageUnavailable())
                        } else {
                            DomainResult.Success(AccountPrivateDataPurgeOutcome.PostCommitRecoveryRequired(ownership))
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DomainResult.Failure(DomainError.LocalStorageUnavailable())
        } finally {
            withContext(NonCancellable) {
                notificationOwner?.let { owner ->
                    notificationParticipant.finish(owner.token, committed = false)
                }
                interactionOwner?.let { owner ->
                    interactionParticipant.finish(owner.token, committed = false)
                }
            }
        }
        complete(attempt, result)
    }

    private suspend fun complete(
        attempt: AccountPrivateDataPurgeAttempt,
        result: DomainResult<AccountPrivateDataPurgeOutcome>,
    ) {
        mutex.withLock {
            if (attempts[attempt.accountId] === attempt) attempts.remove(attempt.accountId)
        }
        attempt.completion.complete(result)
    }
}

internal interface AccountPrivateDataPurgeParticipant {
    suspend fun register(accountId: String): AccountPrivateDataPurgeBlockRegistration

    suspend fun finish(token: AccountPrivateDataPurgeBlockToken, committed: Boolean): Boolean

    suspend fun invalidate(accountId: String)

    suspend fun resume(accountId: String): Boolean
}

internal interface AccountPrivateDataPurgeBlockToken

internal sealed interface AccountPrivateDataPurgeBlockRegistration {
    data object AlreadyBlocked : AccountPrivateDataPurgeBlockRegistration

    data class Owner(
        val token: AccountPrivateDataPurgeBlockToken,
        val idle: Deferred<Unit>?,
    ) : AccountPrivateDataPurgeBlockRegistration
}

private class InteractionPrivateDataPurgeBlockToken(
    val value: InteractionDeletionBlockToken,
) : AccountPrivateDataPurgeBlockToken

private class InteractionPrivateDataPurgeParticipant(
    private val coordinator: InteractionCoordinator,
) : AccountPrivateDataPurgeParticipant {
    override suspend fun register(accountId: String): AccountPrivateDataPurgeBlockRegistration =
        when (val registration = coordinator.registerAccountDeletionBlock(accountId)) {
            InteractionDeletionBlockRegistration.AlreadyBlocked ->
                AccountPrivateDataPurgeBlockRegistration.AlreadyBlocked
            is InteractionDeletionBlockRegistration.Owner -> AccountPrivateDataPurgeBlockRegistration.Owner(
                token = InteractionPrivateDataPurgeBlockToken(registration.token),
                idle = registration.idle,
            )
        }

    override suspend fun finish(token: AccountPrivateDataPurgeBlockToken, committed: Boolean): Boolean =
        coordinator.finishAccountDeletionBlock(
            token = (token as InteractionPrivateDataPurgeBlockToken).value,
            committed = committed,
        )

    override suspend fun invalidate(accountId: String) {
        coordinator.invalidateAfterCompositePurge(accountId)
    }

    override suspend fun resume(accountId: String): Boolean =
        coordinator.resumeAfterAccountDeletionFailure(accountId)
}

private class NotificationPrivateDataPurgeBlockToken(
    val value: NotificationDeletionBlockToken,
) : AccountPrivateDataPurgeBlockToken

private class NotificationPrivateDataPurgeParticipant(
    private val runtime: NotificationRuntime,
) : AccountPrivateDataPurgeParticipant {
    override suspend fun register(accountId: String): AccountPrivateDataPurgeBlockRegistration =
        when (val registration = runtime.registerAccountDeletionBlock(accountId)) {
            NotificationDeletionBlockRegistration.AlreadyBlocked ->
                AccountPrivateDataPurgeBlockRegistration.AlreadyBlocked
            is NotificationDeletionBlockRegistration.Owner -> AccountPrivateDataPurgeBlockRegistration.Owner(
                token = NotificationPrivateDataPurgeBlockToken(registration.token),
                idle = registration.idle,
            )
        }

    override suspend fun finish(token: AccountPrivateDataPurgeBlockToken, committed: Boolean): Boolean =
        runtime.finishAccountDeletionBlock(
            token = (token as NotificationPrivateDataPurgeBlockToken).value,
            committed = committed,
        )

    override suspend fun invalidate(accountId: String) {
        runtime.invalidateAfterCompositePurge(accountId)
    }

    override suspend fun resume(accountId: String): Boolean = runtime.resumeAfterAccountDeletionFailure(accountId)
}

private class AccountPrivateDataPurgeAttempt(
    val accountId: String,
    val completion: CompletableDeferred<DomainResult<AccountPrivateDataPurgeOutcome>> = CompletableDeferred(),
)

private class AccountPrivateDataPurgeOwnershipState(
    val ownership: AccountPrivateDataPurgeOwnership,
    val interactionToken: AccountPrivateDataPurgeBlockToken,
    val notificationToken: AccountPrivateDataPurgeBlockToken,
) {
    val settlementMutex = Mutex()
    var recoveryRequired: Boolean = true
    var interactionInvalidated: Boolean = false
    var notificationInvalidated: Boolean = false
    var interactionFinished: Boolean = false
    var notificationFinished: Boolean = false
    var interactionResumed: Boolean = false
    var notificationResumed: Boolean = false
    var terminal: AccountPrivateDataPurgeOwnershipTerminal = AccountPrivateDataPurgeOwnershipTerminal.Owned
}

private enum class AccountPrivateDataPurgeOwnershipTerminal {
    Owned,
    Resumed,
    Retained,
}

private fun DomainResult<AccountPrivateDataPurgeOutcome>?.ownedBlockOrNull(): AccountPrivateDataPurgeOwnership? =
    when (this) {
        is DomainResult.Failure,
        null,
        -> null
        is DomainResult.Success -> when (val outcome = value) {
            is AccountPrivateDataPurgeOutcome.Acquired -> outcome.ownership
            is AccountPrivateDataPurgeOutcome.PostCommitRecoveryRequired -> outcome.ownership
            AccountPrivateDataPurgeOutcome.AlreadyBlocked -> null
        }
    }

private suspend fun runPostCommitStep(action: suspend () -> Boolean): Boolean = try {
    action()
} catch (_: Exception) {
    false
}

private suspend fun runPostCommitUnitStep(action: suspend () -> Unit): Boolean = try {
    action()
    true
} catch (_: Exception) {
    false
}

private fun String.toCanonicalPrivateDataPurgeAccountId(): String? = trim().lowercase().takeIf { accountId ->
    PRIVATE_DATA_PURGE_UUID_PATTERN.matches(accountId)
}

private val PRIVATE_DATA_PURGE_UUID_PATTERN = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)
private const val INVALID_ACCOUNT_ID_ERROR_KEY = "error.account_deletion.account_id_invalid"
