package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_STORAGE_ERROR_KEY
import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCancellation
import com.kwabor.shared.domain.auth.AccountDeletionOutcomeUnknownCleanupPendingCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCancellation
import com.kwabor.shared.domain.auth.AccountDeletionPreTransportCleanupPendingCancellation
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class SupabaseAccountSecurityAuthDataSource(
    private val auth: Auth,
    private val stepUpSessionFactory: AccountDeletionStepUpSessionFactory,
    private val passwordRecoverySessionStore: PasswordRecoverySessionStore,
    accountDeletionSessionStore: AccountDeletionSessionStore,
    private val accountDeletionSessionGuard: AccountDeletionSessionGuard,
) : AccountSecurityAuthDataSource {
    private val accountDeletionSessionCoordinator = AccountDeletionSessionCoordinator(
        accountDeletionStore = accountDeletionSessionStore,
        passwordRecoveryStore = passwordRecoverySessionStore,
    )

    override suspend fun deleteAccount(request: AccountDeletionRequest): AccountDeletionDataOutcome {
        var remoteAttemptStarted = false
        if (!completePriorCleanup()) return AccountDeletionDataOutcome.LocalCleanupPending
        return try {
            deleteAccountAfterPriorCleanup(request) {
                remoteAttemptStarted = true
            }
        } catch (cancellation: AccountDeletionPreTransportCleanupPendingCancellation) {
            throw cancellation
        } catch (cancellation: AccountDeletionPreTransportCancellation) {
            throw cancellation
        } catch (cancellation: AccountDeletionOutcomeUnknownCleanupPendingCancellation) {
            throw cancellation
        } catch (cancellation: AccountDeletionOutcomeUnknownCancellation) {
            throw cancellation
        } catch (cancellation: CancellationException) {
            if (remoteAttemptStarted) {
                throw AccountDeletionOutcomeUnknownCancellation(cancellation)
            }
            throw AccountDeletionPreTransportCancellation(cancellation)
        }
    }

    private suspend fun completePriorCleanup(): Boolean {
        var priorCleanupMayBePending = true
        return try {
            val cleanupPending = withContext(NonCancellable) {
                accountDeletionSessionGuard.isCleanupPending()
            }
            priorCleanupMayBePending = cleanupPending
            accountDeletionSessionGuard.ensureCleanupCompleted()
            true
        } catch (cancellation: CancellationException) {
            if (priorCleanupMayBePending) {
                throw AccountDeletionOutcomeUnknownCancellation(cancellation)
            }
            throw AccountDeletionPreTransportCancellation(cancellation)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun deleteAccountAfterPriorCleanup(
        request: AccountDeletionRequest,
        onRemoteAttemptStarted: () -> Unit,
    ): AccountDeletionDataOutcome = runAuthRequest {
        auth.awaitInitialization()
        val currentUser = auth.currentSessionOrNull()?.user
            ?: throw AuthDataException.AuthenticationRequired()
        if (currentUser.id != request.expectedAccountId) {
            throw AuthDataException.Validation(AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY)
        }
        val stepUpSession = stepUpSessionFactory.create()
        val execution = executeDeletionAttempt(
            stepUpSession = stepUpSession,
            email = currentUser.email,
            request = request,
            onRemoteAttemptStarted = onRemoteAttemptStarted,
        )
        val settlement = settleDeletionAttempt(stepUpSession, execution)
        resolveDeletionOutcome(execution, settlement)
    }

    private suspend fun executeDeletionAttempt(
        stepUpSession: AccountDeletionStepUpSession,
        email: String?,
        request: AccountDeletionRequest,
        onRemoteAttemptStarted: () -> Unit,
    ): AccountDeletionExecutionState {
        var cleanupMarkerPersisted = false
        var remoteInvocationStarted = false
        var deletionOutcome: AccountDeletionDataOutcome? = null
        val executionFailure = runCatching {
            val reauthenticatedUserId = stepUpSession.reauthenticate(
                email = email,
                credential = request.credential,
            )
            if (reauthenticatedUserId != request.expectedAccountId) {
                throw AuthDataException.Validation(AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY)
            }
            withContext(NonCancellable) {
                accountDeletionSessionCoordinator.markCleanupPending()
                cleanupMarkerPersisted = true
            }
            deletionOutcome = invokeDeletion(stepUpSession, request) {
                remoteInvocationStarted = true
                onRemoteAttemptStarted()
            }
        }.exceptionOrNull()
        return AccountDeletionExecutionState(
            cleanupMarkerPersisted = cleanupMarkerPersisted,
            remoteInvocationStarted = remoteInvocationStarted,
            deletionOutcome = deletionOutcome,
            executionFailure = executionFailure,
        )
    }

    private suspend fun invokeDeletion(
        stepUpSession: AccountDeletionStepUpSession,
        request: AccountDeletionRequest,
        onRemoteAttemptStarted: () -> Unit,
    ): AccountDeletionDataOutcome = try {
        runAuthRequest {
            currentCoroutineContext().ensureActive()
            onRemoteAttemptStarted()
            try {
                stepUpSession.invokeDeletion(request.idempotencyKey)
            } catch (exception: RestException) {
                throw exception.toAccountDeletionDataException()
            }
        }
        AccountDeletionDataOutcome.Deleted
    } catch (exception: AuthDataException) {
        when (exception) {
            is AuthDataException.NetworkUnavailable,
            is AuthDataException.Unexpected,
            -> AccountDeletionDataOutcome.OutcomeUnknown

            is AuthDataException.AuthenticationRequired,
            is AuthDataException.LegalDocumentsUnavailable,
            is AuthDataException.PermissionDenied,
            is AuthDataException.Validation,
            -> throw exception
        }
    }

    private suspend fun settleDeletionAttempt(
        stepUpSession: AccountDeletionStepUpSession,
        execution: AccountDeletionExecutionState,
    ): AccountDeletionSettlement {
        var cleanupFailure: Throwable? = null
        var stepUpCleanupFailed = false
        withContext(NonCancellable) {
            runCatching {
                stepUpSession.close()
            }.exceptionOrNull()?.let { stepUpCleanupFailure ->
                stepUpCleanupFailed = true
                cleanupFailure = cleanupFailure.mergeWith(stepUpCleanupFailure)
            }
            if (execution.cleanupMarkerPersisted) {
                val accountCleanupFailure = cleanupAccountAfterAttempt(
                    execution = execution,
                    stepUpCleanupFailed = stepUpCleanupFailed,
                )
                accountCleanupFailure?.let { failure ->
                    cleanupFailure = cleanupFailure.mergeWith(failure)
                }
            }
        }
        return AccountDeletionSettlement(execution.executionFailure, cleanupFailure)
    }

    private suspend fun cleanupAccountAfterAttempt(
        execution: AccountDeletionExecutionState,
        stepUpCleanupFailed: Boolean,
    ): Throwable? {
        val remoteAttemptNeedsCleanup = execution.deletionOutcome != null ||
            execution.executionFailure is AccountDeletionOutcomeUnknownCancellation ||
            execution.executionFailure is CancellationException && execution.remoteInvocationStarted
        if (remoteAttemptNeedsCleanup && !stepUpCleanupFailed) {
            return runCatching {
                accountDeletionSessionCoordinator.completeCleanup(auth::clearSession)
            }.exceptionOrNull()
        }
        if (remoteAttemptNeedsCleanup) {
            return runCatching {
                accountDeletionSessionCoordinator.clearLocalSessionKeepingMarker(auth::clearSession)
            }.exceptionOrNull()
        }
        return runCatching {
            accountDeletionSessionCoordinator.clearAfterExplicitRejection()
        }.exceptionOrNull()?.also { markerFailure ->
            runCatching {
                accountDeletionSessionCoordinator.clearLocalSessionKeepingMarker(auth::clearSession)
            }.exceptionOrNull()?.let(markerFailure::addSuppressed)
        }
    }

    private fun resolveDeletionOutcome(
        execution: AccountDeletionExecutionState,
        settlement: AccountDeletionSettlement,
    ): AccountDeletionDataOutcome {
        val completedFailure = settlement.finalFailure
        if (completedFailure != null) {
            if (completedFailure is CancellationException && settlement.cleanupFailure != null) {
                completedFailure.addSuppressed(settlement.cleanupFailure)
                completedFailure.throwCleanupPendingCancellation(execution.remoteInvocationStarted)
            }
            if (
                execution.cleanupMarkerPersisted &&
                settlement.cleanupFailure != null &&
                completedFailure !is CancellationException
            ) {
                return if (completedFailure is AuthDataException) {
                    AccountDeletionDataOutcome.RejectedCleanupPending(completedFailure)
                } else {
                    AccountDeletionDataOutcome.LocalCleanupPending
                }
            }
            settlement.cleanupFailure?.let(completedFailure::addSuppressed)
            throw completedFailure
        }
        val completedOutcome = execution.deletionOutcome ?: throw AuthDataException.Unexpected()
        return if (settlement.cleanupFailure != null) {
            AccountDeletionDataOutcome.LocalCleanupPending
        } else {
            completedOutcome
        }
    }
}

private data class AccountDeletionExecutionState(
    val cleanupMarkerPersisted: Boolean,
    val remoteInvocationStarted: Boolean,
    val deletionOutcome: AccountDeletionDataOutcome?,
    val executionFailure: Throwable?,
)

private data class AccountDeletionSettlement(
    val finalFailure: Throwable?,
    val cleanupFailure: Throwable?,
)

private fun CancellationException.throwCleanupPendingCancellation(remoteInvocationStarted: Boolean): Nothing {
    val typedCancellation = if (remoteInvocationStarted) {
        AccountDeletionOutcomeUnknownCleanupPendingCancellation(this)
    } else {
        AccountDeletionPreTransportCleanupPendingCancellation(this)
    }
    throw typedCancellation
}

private fun RestException.toAccountDeletionDataException(): AuthDataException {
    val errorCode = runCatching {
        Json.parseToJsonElement(error).jsonObject["error_code"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return mapAccountDeletionError(errorCode = errorCode, cause = this)
}

internal fun mapAccountDeletionError(errorCode: String?, cause: Throwable): AuthDataException = when (errorCode) {
    "organization_ownership_conflict" ->
        AuthDataException.Validation(AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY, cause)
    "storage_objects_conflict" ->
        AuthDataException.Validation(AUTH_ACCOUNT_DELETION_STORAGE_ERROR_KEY, cause)
    "reauthentication_failed", "identity_mismatch", "invalid_credential" ->
        AuthDataException.Validation(AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY, cause)
    else -> AuthDataException.Unexpected(cause)
}

private fun Throwable?.mergeWith(additionalFailure: Throwable): Throwable =
    this?.also { failure -> failure.addSuppressed(additionalFailure) } ?: additionalFailure
