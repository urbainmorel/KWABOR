package com.kwabor.shared.data.notification

import com.kwabor.shared.domain.notification.NotificationPageRequest
import com.kwabor.shared.domain.notification.NotificationPreferenceFamily
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SupabaseNotificationDataSourceTest {
    @Test
    fun everyOwnerRpcCarriesTheExpectedAccountFenceAndUsesItsVersionedFunction() = runTest {
        val calledFunctions = mutableListOf<String>()
        val client = createNotificationTestClient { request ->
            val function = request.url.encodedPath.substringAfterLast('/')
            val parameters = Json.parseToJsonElement(assertNotificationByteArrayBody(request.body)).jsonObject
            assertEquals(ACCOUNT_ID, parameters.getValue("p_expected_account_id").jsonPrimitive.content)
            calledFunctions += function
            when (function) {
                "get_notification_inbox_status_v1",
                "mark_notification_inbox_seen_v1",
                -> STATUS_RESPONSE
                "mark_all_notifications_read_v1" -> MARK_ALL_READ_RESPONSE
                "list_notification_inbox_v1" -> {
                    assertEquals("cursor-before", parameters.getValue("p_cursor").jsonPrimitive.content)
                    assertEquals(2, parameters.getValue("p_limit").jsonPrimitive.content.toInt())
                    "[]"
                }
                "mark_notification_read_v1" -> {
                    assertEquals(NOTIFICATION_ID, parameters.getValue("p_notification_id").jsonPrimitive.content)
                    READ_MUTATION_RESPONSE
                }
                "hide_notification_v1" -> {
                    assertEquals(NOTIFICATION_ID, parameters.getValue("p_notification_id").jsonPrimitive.content)
                    HIDE_MUTATION_RESPONSE
                }
                "list_notification_preferences_v1" -> PREFERENCES_RESPONSE
                "set_notification_preference_v1" -> {
                    assertEquals("sponsored", parameters.getValue("p_family").jsonPrimitive.content)
                    assertEquals(true, parameters.getValue("p_enabled").jsonPrimitive.boolean)
                    SET_PREFERENCE_RESPONSE
                }
                else -> error("Unexpected notification RPC: $function")
            }
        }

        try {
            val source = SupabaseNotificationDataSource(client.postgrest)
            source.getStatus(ACCOUNT_ID)
            val emptyPage = source.listInbox(
                expectedAccountId = ACCOUNT_ID,
                page = NotificationPageRequest(cursor = "cursor-before", limit = 2),
            )
            source.markSeenThrough(ACCOUNT_ID, SNAPSHOT_SEQUENCE)
            source.markRead(ACCOUNT_ID, NOTIFICATION_ID)
            source.markAllReadThrough(ACCOUNT_ID, SNAPSHOT_SEQUENCE)
            source.hide(ACCOUNT_ID, NOTIFICATION_ID)
            source.listPreferences(ACCOUNT_ID)
            source.setPreference(ACCOUNT_ID, NotificationPreferenceFamily.Sponsored, true)

            assertEquals(emptyList(), emptyPage.items)
            assertNull(emptyPage.snapshotSequence)
            assertNull(emptyPage.nextCursor)
            assertEquals(EXPECTED_NOTIFICATION_FUNCTIONS, calledFunctions.toSet())
        } finally {
            client.close()
        }
    }

    @Test
    fun listInboxConsumesExactlyOneSentinelAndBuildsTheKeysetContinuation() = runTest {
        val response = Json.encodeToString(
            listOf(
                notificationRow(sequence = 2, notificationId = NOTIFICATION_ID_2, rowCursor = "cursor-2"),
                notificationRow(sequence = 1, notificationId = NOTIFICATION_ID, rowCursor = "cursor-1"),
            ),
        )
        val client = createNotificationTestClient { request ->
            assertEquals("/rest/v1/rpc/list_notification_inbox_v1", request.url.encodedPath)
            response
        }

        try {
            val page = SupabaseNotificationDataSource(client.postgrest).listInbox(
                expectedAccountId = ACCOUNT_ID,
                page = NotificationPageRequest(limit = 1),
            )

            assertEquals(listOf(2L), page.items.map(NotificationInboxRowDto::sequenceNumber))
            assertEquals(SNAPSHOT_SEQUENCE, page.snapshotSequence)
            assertEquals("cursor-2", page.nextCursor)
        } finally {
            client.close()
        }
    }

    @Test
    fun sourceFailsClosedOnAccountFenceMismatchAndSingletonContractDrift() = runTest {
        val mismatchClient = createNotificationTestClient(status = HttpStatusCode.Forbidden) {
            EXPECTED_ACCOUNT_MISMATCH_RESPONSE
        }
        try {
            assertFailsWith<NotificationDataException.AuthenticationRequired> {
                SupabaseNotificationDataSource(mismatchClient.postgrest).getStatus(ACCOUNT_ID)
            }
        } finally {
            mismatchClient.close()
        }

        listOf("[]", STATUS_RESPONSE.replace("\"latest_sequence\":", "\"unexpected\":true,\"latest_sequence\":"))
            .forEach { response ->
                val driftClient = createNotificationTestClient { response }
                try {
                    assertFailsWith<NotificationDataException.Unexpected> {
                        SupabaseNotificationDataSource(driftClient.postgrest).getStatus(ACCOUNT_ID)
                    }
                } finally {
                    driftClient.close()
                }
            }
    }
}

private fun createNotificationTestClient(
    status: HttpStatusCode = HttpStatusCode.OK,
    responseProvider: (io.ktor.client.request.HttpRequestData) -> String,
) = createSupabaseClient(
    supabaseUrl = "https://example.invalid",
    supabaseKey = "publishable-test-key",
) {
    httpEngine = MockEngine { request ->
        respond(
            content = responseProvider(request),
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }
    install(Postgrest)
}

private fun assertNotificationByteArrayBody(body: OutgoingContent): String =
    (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

private val EXPECTED_NOTIFICATION_FUNCTIONS = setOf(
    "get_notification_inbox_status_v1",
    "list_notification_inbox_v1",
    "mark_notification_inbox_seen_v1",
    "mark_notification_read_v1",
    "mark_all_notifications_read_v1",
    "hide_notification_v1",
    "list_notification_preferences_v1",
    "set_notification_preference_v1",
)

private const val NOTIFICATION_ID_2 = "20000000-0000-4000-8000-000000000002"

private const val STATUS_RESPONSE = """
[
  {
    "latest_sequence":100,
    "seen_through_sequence":100,
    "unseen_count":0,
    "unread_count":0
  }
]
"""

private const val READ_MUTATION_RESPONSE = """
[
  {
    "notification_id":"20000000-0000-4000-8000-000000000001",
    "sequence_number":1,
    "seen_at":"2026-08-10T10:00:00Z",
    "read_at":"2026-08-10T10:00:00Z",
    "hidden_at":null
  }
]
"""

private const val MARK_ALL_READ_RESPONSE = """
[
  {
    "latest_sequence":100,
    "seen_through_sequence":100,
    "unseen_count":0,
    "unread_count":0,
    "mutation_at":"2026-08-10T10:00:00Z"
  }
]
"""

private const val HIDE_MUTATION_RESPONSE = """
[
  {
    "notification_id":"20000000-0000-4000-8000-000000000001",
    "sequence_number":1,
    "seen_at":"2026-08-10T10:00:00Z",
    "read_at":null,
    "hidden_at":"2026-08-10T10:00:00Z"
  }
]
"""

private const val PREFERENCES_RESPONSE = """
[
  {"family":"suggestion","enabled":false,"updated_at":null},
  {"family":"sponsored","enabled":false,"updated_at":null},
  {"family":"new_listing","enabled":false,"updated_at":null},
  {"family":"event_alert","enabled":false,"updated_at":null}
]
"""

private const val SET_PREFERENCE_RESPONSE = """
[
  {"family":"sponsored","enabled":true,"updated_at":"2026-08-10T10:00:00Z"}
]
"""

private const val EXPECTED_ACCOUNT_MISMATCH_RESPONSE =
    """{"code":"42501","details":null,"hint":null,"message":"expected account mismatch"}"""
