package com.kwabor.shared.data.favorites

import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseFavoritesDataSourceTest {
    @Test
    fun listFavorites_callsVersionedRpcWithFilterAndBuildsKeysetPage() = runTest {
        val client = createFavoritesTestClient { request ->
            assertEquals("/rest/v1/rpc/list_favorite_listing_summaries_v1", request.url.encodedPath)
            val body = assertByteArrayBody(request.body)
            val parameters = Json.parseToJsonElement(body).jsonObject
            assertEquals("etablissement", parameters.getValue("p_listing_type").jsonPrimitive.content)
            assertEquals("cursor-before", parameters.getValue("p_cursor").jsonPrimitive.content)
            assertEquals(2, parameters.getValue("p_limit").jsonPrimitive.content.toInt())
            FAVORITES_RESPONSE
        }

        try {
            val page = SupabaseFavoritesDataSource(client.postgrest).listFavorites(
                filter = ListingType.Establishment,
                page = ListingPageRequest(cursor = "cursor-before", limit = 2),
            ).toDomain(expectedType = ListingType.Establishment)

            assertEquals(listOf(FAVORITE_LISTING_ID_ONE, FAVORITE_LISTING_ID_TWO), page.items.map { it.id })
            assertEquals("cursor-two", page.nextCursor)
        } finally {
            client.close()
        }
    }

    @Test
    fun listFavorites_sendsExplicitNullFilterAndCursor() = runTest {
        val client = createFavoritesTestClient { request ->
            val parameters = Json.parseToJsonElement(assertByteArrayBody(request.body)).jsonObject
            assertTrue(parameters.getValue("p_listing_type").jsonPrimitive.isString.not())
            assertTrue(parameters.getValue("p_cursor").jsonPrimitive.isString.not())
            "[]"
        }

        try {
            val page = SupabaseFavoritesDataSource(client.postgrest).listFavorites(
                filter = null,
                page = ListingPageRequest(),
            )

            assertEquals(emptyList(), page.items)
            assertNull(page.nextCursor)
        } finally {
            client.close()
        }
    }

    @Test
    fun setFavorite_callsIdempotentRpcAndRequiresExactlyOneConformingRow() = runTest {
        val client = createFavoritesTestClient { request ->
            assertEquals("/rest/v1/rpc/set_listing_favorite_v1", request.url.encodedPath)
            val parameters = Json.parseToJsonElement(assertByteArrayBody(request.body)).jsonObject
            assertEquals(FAVORITE_LISTING_ID_ONE, parameters.getValue("p_listing_id").jsonPrimitive.content)
            assertEquals(true, parameters.getValue("p_favorited").jsonPrimitive.boolean)
            FAVORITE_MUTATION_RESPONSE
        }

        try {
            val mutation = SupabaseFavoritesDataSource(client.postgrest).setFavorite(
                listingId = FAVORITE_LISTING_ID_ONE,
                favorited = true,
            ).toDomain(
                expectedListingId = FAVORITE_LISTING_ID_ONE,
                expectedFavorited = true,
                clientMutationSequence = 1L,
            )

            assertEquals(true, mutation.favorited)
            assertEquals(FAVORITE_LISTING_ID_ONE, mutation.listingId)
        } finally {
            client.close()
        }
    }

    @Test
    fun rpcRows_rejectUnknownFieldsInsteadOfSilentlyAcceptingContractDrift() = runTest {
        val client = createFavoritesTestClient {
            FAVORITE_MUTATION_RESPONSE.replace(
                "\"listing_id\":",
                "\"unexpected\":true,\"listing_id\":",
            )
        }

        try {
            assertFailsWith<FavoritesDataException.Unexpected> {
                SupabaseFavoritesDataSource(client.postgrest).setFavorite(FAVORITE_LISTING_ID_ONE, true)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun mutationRpc_rejectsZeroOrMultipleRowsAndMismatchedState() = runTest {
        val responses = listOf(
            "[]",
            "[$FAVORITE_MUTATION_OBJECT,$FAVORITE_MUTATION_OBJECT]",
            "[$FAVORITE_REMOVAL_OBJECT]",
        )

        responses.forEach { response ->
            val client = createFavoritesTestClient { response }
            try {
                assertFailsWith<FavoritesDataException.Unexpected> {
                    SupabaseFavoritesDataSource(client.postgrest).setFavorite(FAVORITE_LISTING_ID_ONE, true)
                }
            } finally {
                client.close()
            }
        }
    }
}

private fun createFavoritesTestClient(
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

private fun assertByteArrayBody(body: OutgoingContent): String =
    (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

private const val FAVORITES_RESPONSE = """
[
  {
    "id":"11111111-1111-4111-8111-111111111111","type":"etablissement","listing_class":"commercial",
    "status":"publie","name":"Maison Un","city_id":"cotonou","city_name":"Cotonou",
    "category_id":"restaurants","cover_image_url":"https://cdn.kwabor.test/one.jpg",
    "cover_image_alt":"Maison Un","price_from_xof":5000,"rating_avg":4.5,"likes_count":12,
    "verified":true,"liked_by_current_user":true,"favorited_by_current_user":true,
    "favorited_at":"2026-08-04T10:00:00Z","event_start_at":null,"event_end_at":null,
    "is_event_ended":false,"is_sponsored_placement":false,"row_cursor":"cursor-one"
  },
  {
    "id":"22222222-2222-4222-8222-222222222222","type":"etablissement","listing_class":"commercial",
    "status":"publie","name":"Maison Deux","city_id":"ouidah","city_name":"Ouidah",
    "category_id":"hotels","cover_image_url":null,"cover_image_alt":null,"price_from_xof":7000,
    "rating_avg":4.2,"likes_count":8,"verified":false,"liked_by_current_user":false,
    "favorited_by_current_user":true,"favorited_at":"2026-08-03T10:00:00Z","event_start_at":null,
    "event_end_at":null,"is_event_ended":false,"is_sponsored_placement":false,"row_cursor":"cursor-two"
  },
  {
    "id":"33333333-3333-4333-8333-333333333333","type":"etablissement","listing_class":"commercial",
    "status":"publie","name":"Maison Trois","city_id":"abomey","city_name":"Abomey",
    "category_id":"restaurants","cover_image_url":null,"cover_image_alt":null,"price_from_xof":null,
    "rating_avg":null,"likes_count":0,"verified":false,"liked_by_current_user":false,
    "favorited_by_current_user":true,"favorited_at":"2026-08-02T10:00:00Z","event_start_at":null,
    "event_end_at":null,"is_event_ended":false,"is_sponsored_placement":false,"row_cursor":"cursor-three"
  }
]
"""

private const val FAVORITE_MUTATION_OBJECT = """
{
  "listing_id":"11111111-1111-4111-8111-111111111111",
  "favorited_by_current_user":true,
  "favorited_at":"2026-08-04T10:00:00Z"
}
"""

private const val FAVORITE_REMOVAL_OBJECT = """
{
  "listing_id":"11111111-1111-4111-8111-111111111111",
  "favorited_by_current_user":false,
  "favorited_at":null
}
"""

private const val FAVORITE_MUTATION_RESPONSE = "[$FAVORITE_MUTATION_OBJECT]"
