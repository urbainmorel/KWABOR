package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.presentation.auth.AccountPrivateDataPurgeOutcome
import com.kwabor.shared.presentation.auth.AccountPrivateDataPurgeOwnership
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
    private val purge: suspend (String) -> DomainResult<AccountPrivateDataPurgeOutcome>,
    private val resume: suspend (AccountPrivateDataPurgeOwnership) -> Boolean,
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
            if (handoff.complete(result)) {
                resumeLateAcquisitionAndRelease(checkNotNull(result.ownershipOrNull()), handoff)
            }
        }
        return handoff
    }

    fun resumeAbandonedAcquisition(ownership: AccountPrivateDataPurgeOwnership, handoff: AccountDeletionPurgeHandoff) {
        workerScope.launch {
            resumeLateAcquisitionAndRelease(ownership, handoff)
        }
    }

    private suspend fun runPurge(accountId: String): AccountDeletionPurgeWorkerResult = try {
        when (val result = purge(accountId)) {
            is DomainResult.Failure -> AccountDeletionPurgeWorkerResult.Failed
            is DomainResult.Success -> when (val outcome = result.value) {
                AccountPrivateDataPurgeOutcome.AlreadyBlocked ->
                    AccountDeletionPurgeWorkerResult.AlreadyBlocked
                is AccountPrivateDataPurgeOutcome.Acquired ->
                    AccountDeletionPurgeWorkerResult.Acquired(outcome.ownership)
                is AccountPrivateDataPurgeOutcome.PostCommitRecoveryRequired -> {
                    val resumed = withContext(NonCancellable) { resume(outcome.ownership) }
                    AccountDeletionPurgeWorkerResult.Recovery(
                        ownership = outcome.ownership,
                        resumed = resumed,
                    )
                }
            }
        }
    } catch (_: CancellationException) {
        AccountDeletionPurgeWorkerResult.Failed
    } catch (_: Exception) {
        AccountDeletionPurgeWorkerResult.Failed
    }

    private suspend fun resumeLateAcquisitionAndRelease(
        ownership: AccountPrivateDataPurgeOwnership,
        handoff: AccountDeletionPurgeHandoff,
    ) {
        try {
            withContext(NonCancellable) { resume(ownership) }
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
                result is AccountDeletionPurgeWorkerResult.Acquired &&
                ownerState == AccountDeletionPurgeOwnerState.Abandoned
            ) {
                ownerState = AccountDeletionPurgeOwnerState.Released
                true
            } else {
                false
            }
        }
        completion.complete(result)
        if (result !is AccountDeletionPurgeWorkerResult.Acquired) releaseRegistration()
        return releaseLateAcquisition
    }

    fun claimAcquisition(): Boolean {
        val claimed = synchronized(stateLock) {
            if (
                completedResult !is AccountDeletionPurgeWorkerResult.Acquired ||
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

    fun abandon(): AccountPrivateDataPurgeOwnership? = synchronized(stateLock) {
        when (ownerState) {
            AccountDeletionPurgeOwnerState.Waiting -> {
                val acquired = completedResult as? AccountDeletionPurgeWorkerResult.Acquired
                if (acquired != null) {
                    ownerState = AccountDeletionPurgeOwnerState.Released
                    acquired.ownership
                } else {
                    ownerState = AccountDeletionPurgeOwnerState.Abandoned
                    null
                }
            }
            AccountDeletionPurgeOwnerState.Claimed,
            AccountDeletionPurgeOwnerState.Abandoned,
            AccountDeletionPurgeOwnerState.Released,
            -> null
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

internal sealed interface AccountDeletionPurgeWorkerResult {
    data class Acquired(val ownership: AccountPrivateDataPurgeOwnership) : AccountDeletionPurgeWorkerResult

    data class Recovery(
        val ownership: AccountPrivateDataPurgeOwnership,
        val resumed: Boolean,
    ) : AccountDeletionPurgeWorkerResult

    data object AlreadyBlocked : AccountDeletionPurgeWorkerResult

    data object Failed : AccountDeletionPurgeWorkerResult
}

private fun AccountDeletionPurgeWorkerResult.ownershipOrNull(): AccountPrivateDataPurgeOwnership? =
    (this as? AccountDeletionPurgeWorkerResult.Acquired)?.ownership

private enum class AccountDeletionPurgeOwnerState {
    Waiting,
    Claimed,
    Abandoned,
    Released,
}

private const val ACCOUNT_DELETION_PURGE_WAIT_TIMEOUT_MILLIS = 10_000L
