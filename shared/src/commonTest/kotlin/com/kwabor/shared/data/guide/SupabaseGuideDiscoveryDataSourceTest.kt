package com.kwabor.shared.data.guide

import com.kwabor.shared.domain.guide.GuideDiscoveryFilters
import com.kwabor.shared.domain.guide.GuidePageRequest
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SupabaseGuideDiscoveryDataSourceTest {
    @Test
    fun listFacets_callsTheVersionedRpcAndDecodesStrictRows() = runTest {
        val client = createGuideTestClient { request ->
            assertEquals("/rest/v1/rpc/list_guide_facets_v1", request.url.encodedPath)
            FACETS_RESPONSE
        }

        try {
            val facets = SupabaseGuideDiscoveryDataSource(client.postgrest).listFacets().toDomainFacets()

            assertEquals(listOf("cotonou", "francais", "histoire"), facets.map { facet -> facet.id })
        } finally {
            client.close()
        }
    }

    @Test
    fun listServices_sendsAllFiltersAndBuildsAKeysetPage() = runTest {
        val client = createGuideTestClient { request ->
            assertEquals("/rest/v1/rpc/list_guide_services_v1", request.url.encodedPath)
            val body = assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString()
            val parameters = Json.parseToJsonElement(body).jsonObject
            assertEquals("ouidah", parameters.getValue("p_city_id").jsonPrimitive.content)
            assertEquals("francais", parameters.getValue("p_language_id").jsonPrimitive.content)
            assertEquals("histoire", parameters.getValue("p_specialty_id").jsonPrimitive.content)
            assertEquals("cursor-before", parameters.getValue("p_cursor").jsonPrimitive.content)
            assertEquals(2, parameters.getValue("p_limit").jsonPrimitive.content.toInt())
            SERVICES_RESPONSE
        }

        try {
            val page = SupabaseGuideDiscoveryDataSource(client.postgrest).listServices(
                filters = GuideDiscoveryFilters(
                    cityId = "ouidah",
                    languageId = "francais",
                    specialtyId = "histoire",
                ),
                page = GuidePageRequest(cursor = "cursor-before", limit = 2),
            ).toDomain()

            assertEquals(listOf(GUIDE_ID_ONE, GUIDE_ID_TWO), page.items.map { item -> item.id })
            assertEquals("cursor-two", page.nextCursor)
        } finally {
            client.close()
        }
    }

    @Test
    fun rpcRows_rejectUnknownFieldsInsteadOfSilentlyAcceptingContractDrift() = runTest {
        val client = createGuideTestClient {
            FACETS_RESPONSE.replace(
                "\"schema_version\": 1,",
                "\"schema_version\": 1, \"unexpected\": true,",
            )
        }

        try {
            assertFailsWith<GuideDiscoveryDataException.Unexpected> {
                SupabaseGuideDiscoveryDataSource(client.postgrest).listFacets()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun missingRpcContract_isUnexpectedRatherThanAnEmptyResult() = runTest {
        val client = createGuideTestClient(
            status = HttpStatusCode.NotFound,
            responseProvider = { MISSING_RPC_RESPONSE },
        )

        try {
            assertFailsWith<GuideDiscoveryDataException.Unexpected> {
                SupabaseGuideDiscoveryDataSource(client.postgrest).listFacets()
            }
        } finally {
            client.close()
        }
    }
}

private fun createGuideTestClient(
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

private const val FACETS_RESPONSE = """
[
  {"schema_version": 1, "facet_type": "city", "facet_id": "cotonou", "label": "Cotonou"},
  {"schema_version": 1, "facet_type": "language", "facet_id": "francais", "label": "Français"},
  {"schema_version": 1, "facet_type": "specialty", "facet_id": "histoire", "label": "Histoire"}
]
"""

private const val SERVICES_RESPONSE = """
[
  {
    "schema_version": 1,
    "id": "11111111-1111-4111-8111-111111111111",
    "name": "Guide Un",
    "base_city_id": "cotonou",
    "base_city_name": "Cotonou",
    "cover_image_url": "https://cdn.kwabor.test/guide-one.jpg",
    "cover_image_alt": "Portrait du guide un",
    "languages": [{"id": "francais", "label": "Français"}],
    "coverage_cities": [{"id": "ouidah", "label": "Ouidah"}],
    "specialties": [{"id": "histoire", "label": "Histoire"}],
    "indicative_price_xof": 10000,
    "rating_avg": 4.8,
    "rating_count": 20,
    "verified": true,
    "row_cursor": "cursor-one"
  },
  {
    "schema_version": 1,
    "id": "22222222-2222-4222-8222-222222222222",
    "name": "Guide Deux",
    "base_city_id": "porto-novo",
    "base_city_name": "Porto-Novo",
    "cover_image_url": "https://cdn.kwabor.test/guide-two.jpg",
    "cover_image_alt": "Portrait du guide deux",
    "languages": [{"id": "francais", "label": "Français"}],
    "coverage_cities": [{"id": "ouidah", "label": "Ouidah"}],
    "specialties": [{"id": "histoire", "label": "Histoire"}],
    "indicative_price_xof": 15000,
    "rating_avg": 4.5,
    "rating_count": 12,
    "verified": true,
    "row_cursor": "cursor-two"
  },
  {
    "schema_version": 1,
    "id": "33333333-3333-4333-8333-333333333333",
    "name": "Guide Trois",
    "base_city_id": "abomey",
    "base_city_name": "Abomey",
    "cover_image_url": "https://cdn.kwabor.test/guide-three.jpg",
    "cover_image_alt": "Portrait du guide trois",
    "languages": [{"id": "francais", "label": "Français"}],
    "coverage_cities": [{"id": "ouidah", "label": "Ouidah"}],
    "specialties": [{"id": "histoire", "label": "Histoire"}],
    "indicative_price_xof": 20000,
    "rating_avg": null,
    "rating_count": 0,
    "verified": false,
    "row_cursor": "cursor-three"
  }
]
"""

private const val MISSING_RPC_RESPONSE = """
{
  "code": "PGRST202",
  "details": "Function public.list_guide_facets_v1 was not found in the schema cache.",
  "hint": null,
  "message": "Could not find the function public.list_guide_facets_v1"
}
"""
