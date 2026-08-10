package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.presentation.interaction.InteractionAccountDeletionPurgeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class AccountDeletionPurgeWorker(
    private val workerScope: CoroutineScope,
    private val registry: AccountDeletionPurgeRegistry,
    private val purge: suspend (String) -> DomainResult<InteractionAccountDeletionPurgeOutcome>,
    private val resume: suspend (String) -> Unit,
) {
    fun start(accountId: String): AccountDeletionPurgeHandoff {
        val registration = registry.tryStart(accountId)
        if (registration == null) {
            val handoff = AccountDeletionPurgeHandoff()
            handoff.complete(AccountDeletionPurgeWorkerResult.AlreadyBlocked)
            return handoff
        }
        val handoff = AccountDeletionPurgeHandoff(
            onRegistrationReleased = { registry.finish(registration) },
        )
        workerScope.launch {
            val result = runPurge(accountId)
            if (handoff.complete(result)) resumeLateAcquisitionAndRelease(accountId, handoff)
        }
        return handoff
    }

    fun resumeAbandonedAcquisition(accountId: String, handoff: AccountDeletionPurgeHandoff) {
        workerScope.launch {
            resumeLateAcquisitionAndRelease(accountId, handoff)
        }
    }

    private suspend fun runPurge(accountId: String): AccountDeletionPurgeWorkerResult = try {
        when (val result = purge(accountId)) {
            is DomainResult.Failure -> AccountDeletionPurgeWorkerResult.Failed
            is DomainResult.Success -> when (result.value) {
                InteractionAccountDeletionPurgeOutcome.AlreadyBlocked ->
                    AccountDeletionPurgeWorkerResult.AlreadyBlocked
                is InteractionAccountDeletionPurgeOutcome.Acquired ->
                    AccountDeletionPurgeWorkerResult.Acquired
            }
        }
    } catch (_: CancellationException) {
        AccountDeletionPurgeWorkerResult.Failed
    } catch (_: Exception) {
        AccountDeletionPurgeWorkerResult.Failed
    }

    private suspend fun resumeLateAcquisition(accountId: String) = withContext(NonCancellable) {
        resume(accountId)
    }

    private suspend fun resumeLateAcquisitionAndRelease(accountId: String, handoff: AccountDeletionPurgeHandoff) {
        try {
            resumeLateAcquisition(accountId)
        } finally {
            handoff.releaseRegistration()
        }
    }
}

internal class AccountDeletionPurgeRegistry {
    private val stateLock = Any()
    private val inFlightRegistrations = mutableMapOf<String, Registration>()

    fun tryStart(accountId: String): Registration? = synchronized(stateLock) {
        if (inFlightRegistrations.containsKey(accountId)) return@synchronized null
        Registration(accountId).also { registration ->
            inFlightRegistrations[accountId] = registration
        }
    }

    fun finish(registration: Registration) {
        synchronized(stateLock) {
            if (inFlightRegistrations[registration.accountId] === registration) {
                inFlightRegistrations.remove(registration.accountId)
            }
        }
    }

    internal class Registration(val accountId: String)
}

internal class AccountDeletionPurgeHandoff(
    private val onRegistrationReleased: () -> Unit = {},
) {
    private val completion = CompletableDeferred<AccountDeletionPurgeWorkerResult>()
    private val stateLock = Any()
    private var completedResult: AccountDeletionPurgeWorkerResult? = null
    private var ownerState = AccountDeletionPurgeOwnerState.Waiting
    private var registrationReleased = false

    suspend fun awaitResult(): AccountDeletionPurgeWorkerResult? = withTimeoutOrNull(
        ACCOUNT_DELETION_PURGE_WAIT_TIMEOUT_MILLIS,
    ) {
        completion.await()
    }

    fun complete(result: AccountDeletionPurgeWorkerResult): Boolean {
        val releaseLateAcquisition = synchronized(stateLock) {
            check(completedResult == null)
            completedResult = result
            if (
                result == AccountDeletionPurgeWorkerResult.Acquired &&
                ownerState == AccountDeletionPurgeOwnerState.Abandoned
            ) {
                ownerState = AccountDeletionPurgeOwnerState.Released
                true
            } else {
                false
            }
        }
        completion.complete(result)
        if (result != AccountDeletionPurgeWorkerResult.Acquired) releaseRegistration()
        return releaseLateAcquisition
    }

    fun claimAcquisition(): Boolean {
        val claimed = synchronized(stateLock) {
            if (
                completedResult != AccountDeletionPurgeWorkerResult.Acquired ||
                ownerState != AccountDeletionPurgeOwnerState.Waiting
            ) {
                false
            } else {
                ownerState = AccountDeletionPurgeOwnerState.Claimed
                true
            }
        }
        if (claimed) releaseRegistration()
        return claimed
    }

    fun abandon(): Boolean = synchronized(stateLock) {
        when (ownerState) {
            AccountDeletionPurgeOwnerState.Waiting -> {
                if (completedResult == AccountDeletionPurgeWorkerResult.Acquired) {
                    ownerState = AccountDeletionPurgeOwnerState.Released
                    true
                } else {
                    ownerState = AccountDeletionPurgeOwnerState.Abandoned
                    false
                }
            }
            AccountDeletionPurgeOwnerState.Claimed,
            AccountDeletionPurgeOwnerState.Abandoned,
            AccountDeletionPurgeOwnerState.Released,
            -> false
        }
    }

    fun releaseRegistration() {
        val shouldRelease = synchronized(stateLock) {
            if (registrationReleased) {
                false
            } else {
                registrationReleased = true
                true
            }
        }
        if (shouldRelease) onRegistrationReleased()
    }
}

internal enum class AccountDeletionPurgeWorkerResult {
    Acquired,
    AlreadyBlocked,
    Failed,
}

private enum class AccountDeletionPurgeOwnerState {
    Waiting,
    Claimed,
    Abandoned,
    Released,
}

private const val ACCOUNT_DELETION_PURGE_WAIT_TIMEOUT_MILLIS = 10_000L
