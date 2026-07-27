package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_OWNERSHIP_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_REAUTHENTICATION_ERROR_KEY
import com.kwabor.shared.domain.auth.AUTH_ACCOUNT_DELETION_STORAGE_ERROR_KEY
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.Functions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class SupabaseAccountSecurityAuthDataSource(
    private val auth: Auth,
    private val functions: Functions,
    private val passwordRecoverySessionStore: PasswordRecoverySessionStore,
) : AccountSecurityAuthDataSource {
    override suspend fun deleteAccount(request: AccountDeletionRequest): Unit = runAuthRequest {
        try {
            functions.invoke(
                function = ACCOUNT_DELETE_FUNCTION,
                body = request.toEdgeFunctionBody(),
            )
        } catch (exception: RestException) {
            throw exception.toAccountDeletionDataException()
        }
        passwordRecoverySessionStore.clearPasswordRecovery()
        auth.clearSession()
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

private fun AccountDeletionRequest.toEdgeFunctionBody() = buildJsonObject {
    put("idempotency_key", idempotencyKey)
    put(
        "credential",
        buildJsonObject {
            when (val accountCredential = credential) {
                is AccountDeletionCredential.Password -> {
                    put("type", "password")
                    put("password", accountCredential.password)
                }
                is AccountDeletionCredential.Social -> {
                    put("type", "social")
                    put("provider", accountCredential.request.provider.name.lowercase())
                    put("id_token", accountCredential.request.idToken)
                    put("nonce", accountCredential.request.rawNonce)
                }
            }
        },
    )
}

private const val ACCOUNT_DELETE_FUNCTION = "account-delete"
