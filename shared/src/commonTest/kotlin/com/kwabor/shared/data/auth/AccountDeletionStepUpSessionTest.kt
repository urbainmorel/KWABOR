package com.kwabor.shared.data.auth

import com.kwabor.shared.data.config.KwaborEnvironment
import com.kwabor.shared.data.config.KwaborEnvironmentTier
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(SupabaseExperimental::class)
class AccountDeletionStepUpSessionTest {
    @Test
    fun productionClientIsMemoryOnlySilentAndRequiresAnAuthSessionForFunctions() = runTest {
        val client = createAccountDeletionStepUpSupabaseClient(testEnvironment())

        try {
            assertEquals(LogLevel.NONE, client.config.loggingConfig.defaultLogLevel)
            assertFalse(client.auth.config.alwaysAutoRefresh)
            assertFalse(client.auth.config.autoLoadFromStorage)
            assertFalse(client.auth.config.autoSaveToStorage)
            assertFalse(client.auth.config.enableLifecycleCallbacks)
            assertIs<MemorySessionManager>(client.auth.sessionManager)
            assertTrue(client.functions.config.requireValidSession)
        } finally {
            client.close()
        }
    }

    @Test
    fun passwordAndSocialSecretsAreSentOnlyToAuthNeverToTheEdgeBody() = runTest {
        val credentials = listOf(
            AccountDeletionCredential.Password(PASSWORD),
            AccountDeletionCredential.Social(
                SocialSignInRequest(
                    provider = SocialAuthProvider.Google,
                    idToken = ID_TOKEN,
                    rawNonce = NONCE,
                ),
            ),
        )

        credentials.forEach { credential ->
            val requests = mutableListOf<CapturedRequest>()
            val client = createStepUpTestClient(requests)
            val session = SupabaseAccountDeletionStepUpSession(client)
            var sessionClosed = false

            try {
                assertEquals(USER_ID, session.reauthenticate(USER_EMAIL, credential))
                session.invokeDeletion(IDEMPOTENCY_KEY)
                session.close()
                sessionClosed = true

                val authRequest = requests.single { request -> request.path == "/auth/v1/token" }
                val edgeRequest = requests.single { request -> request.path == "/functions/v1/account-delete" }
                val edgeBody = Json.parseToJsonElement(edgeRequest.body).jsonObject

                assertEquals(setOf("idempotency_key"), edgeBody.keys)
                assertEquals("Bearer temporary-access-token", edgeRequest.authorization)
                assertEquals(ContentType.Application.Json.toString(), edgeRequest.contentType)
                assertTrue(authRequest.body.contains(credential.expectedAuthSecret()))
                assertFalse(edgeRequest.body.contains(PASSWORD))
                assertFalse(edgeRequest.body.contains(ID_TOKEN))
                assertFalse(edgeRequest.body.contains(NONCE))
                assertFalse(edgeRequest.body.contains(USER_EMAIL))
            } finally {
                if (!sessionClosed) runCatching { client.close() }
            }
        }
    }
}

private fun createStepUpTestClient(requests: MutableList<CapturedRequest>) = createSupabaseClient(
    supabaseUrl = "https://kwabor.test",
    supabaseKey = "publishable-test-key",
) {
    defaultLogLevel = LogLevel.NONE
    httpEngine = MockEngine { request ->
        requests += request.capture()
        when (request.url.encodedPath) {
            "/auth/v1/token" -> respond(
                content = SESSION_RESPONSE,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

            "/functions/v1/account-delete",
            "/auth/v1/logout",
            -> respond(content = "", status = HttpStatusCode.NoContent)

            else -> error("Unexpected test request path: ${request.url.encodedPath}")
        }
    }
    install(Auth) {
        minimalConfig()
    }
    install(Functions) {
        requireValidSession = true
    }
}

private fun HttpRequestData.capture(): CapturedRequest {
    val outgoingContent = body
    val bodyText = when (outgoingContent) {
        is OutgoingContent.ByteArrayContent -> outgoingContent.bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        else -> error("Unexpected test request body: ${outgoingContent::class.simpleName}")
    }
    return CapturedRequest(
        path = url.encodedPath,
        body = bodyText,
        authorization = headers[HttpHeaders.Authorization],
        contentType = outgoingContent.contentType?.toString() ?: headers[HttpHeaders.ContentType],
    )
}

private fun AccountDeletionCredential.expectedAuthSecret(): String = when (this) {
    is AccountDeletionCredential.Password -> PASSWORD
    is AccountDeletionCredential.Social -> ID_TOKEN
}

private fun testEnvironment() = KwaborEnvironment(
    tier = KwaborEnvironmentTier.Development,
    supabaseUrl = "https://kwabor.test",
    supabasePublishableKey = "publishable-test-key",
)

private data class CapturedRequest(
    val path: String,
    val body: String,
    val authorization: String?,
    val contentType: String?,
)

private const val USER_ID = "11111111-1111-4111-8111-111111111111"
private const val USER_EMAIL = "user@kwabor.test"
private const val IDEMPOTENCY_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val PASSWORD = "password-secret-test"
private const val ID_TOKEN = "header.payload.signature-secret-test"
private const val NONCE = "abcdefghijklmnopqrstuvwxyzABCDEF"
private const val SESSION_RESPONSE = """
{
  "access_token": "temporary-access-token",
  "refresh_token": "temporary-refresh-token",
  "expires_in": 3600,
  "token_type": "bearer",
  "user": {
    "aud": "authenticated",
    "id": "11111111-1111-4111-8111-111111111111",
    "email": "user@kwabor.test"
  }
}
"""
