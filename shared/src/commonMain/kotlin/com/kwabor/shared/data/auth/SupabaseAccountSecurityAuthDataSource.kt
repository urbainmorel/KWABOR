package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_STORAGE_ERROR_KEY
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class SupabaseAccountSecurityAuthDataSource(
    private val auth: Auth,
    private val stepUpSessionFactory: AccountDeletionStepUpSessionFactory,
    private val passwordRecoverySessionStore: PasswordRecoverySessionStore,
) : AccountSecurityAuthDataSource {
    override suspend fun deleteAccount(request: AccountDeletionRequest): Unit = runAuthRequest {
        auth.awaitInitialization()
        val currentUser = auth.currentSessionOrNull()?.user
            ?: throw AuthDataException.AuthenticationRequired()
        val stepUpSession = stepUpSessionFactory.create()
        val executionFailure = runCatching {
            val reauthenticatedUserId = stepUpSession.reauthenticate(
                email = currentUser.email,
                credential = request.credential,
            )
            if (reauthenticatedUserId != currentUser.id) {
                throw AuthDataException.Validation(AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY)
            }
            try {
                stepUpSession.invokeDeletion(request.idempotencyKey)
            } catch (exception: RestException) {
                throw exception.toAccountDeletionDataException()
            }
        }.exceptionOrNull()

        var finalFailure = executionFailure
        withContext(NonCancellable) {
            runCatching {
                stepUpSession.close()
            }.exceptionOrNull()?.let { cleanupFailure ->
                if (executionFailure != null) {
                    finalFailure = finalFailure.mergeWith(cleanupFailure)
                }
            }
            if (executionFailure == null) {
                runCatching {
                    clearDeletedAccountLocalState()
                }
            }
        }
        finalFailure?.let { failure -> throw failure }
    }

    private suspend fun clearDeletedAccountLocalState() {
        var failure: Throwable? = runCatching {
            passwordRecoverySessionStore.clearPasswordRecovery()
        }.exceptionOrNull()
        runCatching {
            auth.clearSession()
        }.exceptionOrNull()?.let { cleanupFailure ->
            failure = failure.mergeWith(cleanupFailure)
        }
        failure?.let { cleanupFailure -> throw cleanupFailure }
    }
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
