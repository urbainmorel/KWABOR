package com.kwabor.shared.data.auth

import com.kwabor.shared.data.config.KwaborEnvironment
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.logging.LogLevel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun interface AccountDeletionStepUpSessionFactory {
    fun create(): AccountDeletionStepUpSession
}

internal interface AccountDeletionStepUpSession {
    suspend fun reauthenticate(email: String?, credential: AccountDeletionCredential): String?

    suspend fun invokeDeletion(idempotencyKey: String)

    suspend fun close()
}

internal class SupabaseAccountDeletionStepUpSessionFactory(
    private val environment: KwaborEnvironment,
) : AccountDeletionStepUpSessionFactory {
    override fun create(): AccountDeletionStepUpSession = SupabaseAccountDeletionStepUpSession(
        client = createAccountDeletionStepUpSupabaseClient(environment),
    )
}

internal class SupabaseAccountDeletionStepUpSession(
    private val client: SupabaseClient,
) : AccountDeletionStepUpSession {
    override suspend fun reauthenticate(email: String?, credential: AccountDeletionCredential): String? {
        client.auth.awaitInitialization()
        when (credential) {
            is AccountDeletionCredential.Password -> {
                val currentEmail = email?.takeIf(String::isNotBlank) ?: return null
                client.auth.signInWith(Email, redirectUrl = null) {
                    this.email = currentEmail
                    password = credential.password
                }
            }

            is AccountDeletionCredential.Social -> client.auth.signInWith(IDToken, redirectUrl = null) {
                idToken = credential.request.idToken
                nonce = credential.request.rawNonce
                provider = credential.request.provider.toSupabaseProvider()
            }
        }
        return client.auth.currentSessionOrNull()?.user?.id
    }

    override suspend fun invokeDeletion(idempotencyKey: String) {
        val response = client.functions.invoke(
            function = ACCOUNT_DELETE_FUNCTION,
            body = buildJsonObject {
                put("idempotency_key", idempotencyKey)
            },
            headers = headersOf(
                HttpHeaders.ContentType,
                ContentType.Application.Json.toString(),
            ),
        )
        if (response.status != HttpStatusCode.NoContent) {
            throw AuthDataException.Unexpected(
                IllegalStateException("Unexpected account deletion response status"),
            )
        }
    }

    override suspend fun close() {
        var failure: Throwable? = runCatching {
            client.auth.signOut(SignOutScope.LOCAL)
        }.exceptionOrNull()
        runCatching {
            client.auth.clearSession()
        }.exceptionOrNull()?.let { cleanupFailure ->
            failure = failure.mergeWith(cleanupFailure)
        }
        runCatching {
            client.close()
        }.exceptionOrNull()?.let { closeFailure ->
            failure = failure.mergeWith(closeFailure)
        }
        failure?.let { cleanupFailure -> throw cleanupFailure }
    }
}

@OptIn(SupabaseExperimental::class)
internal fun createAccountDeletionStepUpSupabaseClient(environment: KwaborEnvironment): SupabaseClient =
    createSupabaseClient(
        supabaseUrl = environment.supabaseUrl,
        supabaseKey = environment.supabasePublishableKey,
    ) {
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            minimalConfig()
        }
        install(Functions) {
            requireValidSession = true
        }
    }

private fun Throwable?.mergeWith(additionalFailure: Throwable): Throwable =
    this?.also { failure -> failure.addSuppressed(additionalFailure) } ?: additionalFailure

private const val ACCOUNT_DELETE_FUNCTION = "account-delete"
