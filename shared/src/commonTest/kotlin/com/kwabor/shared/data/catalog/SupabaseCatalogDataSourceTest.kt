package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SupabaseCatalogDataSourceTest {
    @Test
    fun searchListings_callsTheVersionedRpcWithCanonicalParametersAndBuildsAKeysetPage() = runTest {
        val client = createCatalogSearchTestClient { request ->
            assertEquals("/rest/v1/rpc/search_catalog_summaries_v1", request.url.encodedPath)
            val body = assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString()
            val parameters = Json.parseToJsonElement(body).jsonObject
            assertEquals("restaurant", parameters.getValue("p_search_query").jsonPrimitive.content)
            assertEquals("cotonou", parameters.getValue("p_city_id").jsonPrimitive.content)
            assertEquals("commercial-restaurant", parameters.getValue("p_category_id").jsonPrimitive.content)
            assertEquals("etablissement", parameters.getValue("p_listing_type").jsonPrimitive.content)
            assertEquals("commercial", parameters.getValue("p_listing_class").jsonPrimitive.content)
            assertEquals("cursor-before", parameters.getValue("p_cursor").jsonPrimitive.content)
            assertEquals(2, parameters.getValue("p_limit").jsonPrimitive.content.toInt())
            CATALOG_SEARCH_RPC_RESPONSE
        }

        try {
            val page = SupabaseCatalogDataSource(client.postgrest).searchListings(
                query = ListingSearchQuery(
                    text = "restaurant",
                    filters = ListingFilters(
                        cityId = "cotonou",
                        categoryId = "commercial-restaurant",
                        listingType = ListingType.Establishment,
                        listingClass = ListingClass.Commercial,
                    ),
                ),
                page = ListingPageRequest(cursor = "cursor-before", limit = 2),
            ).toDomain()

            assertEquals(listOf("listing-1", "listing-2"), page.items.map { item -> item.id })
            assertEquals("cursor-2", page.nextCursor)
        } finally {
            client.close()
        }
    }

    @Test
    fun searchListings_mapsTransientGatewayFailuresToNetworkUnavailable() = runTest {
        val client = createCatalogSearchTestClient(status = HttpStatusCode.ServiceUnavailable) {
            """{"code":"TEMPORARY","message":"Service unavailable"}"""
        }

        try {
            assertFailsWith<CatalogDataException.NetworkUnavailable> {
                SupabaseCatalogDataSource(client.postgrest).searchListings(
                    query = ListingSearchQuery(text = "restaurant"),
                    page = ListingPageRequest(),
                )
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun getListingDetail_decodesTheVersionedRpcWrapperAndMapsItsDomainContract() = runTest {
        val client = createCatalogTestClient(CATALOG_DETAIL_RPC_RESPONSE, HttpStatusCode.OK)

        try {
            val detail = SupabaseCatalogDataSource(client.postgrest)
                .getListingDetail(VALID_LISTING_ID)
                .toDomain()

            val food = assertIs<CatalogDetail.Establishment.Food>(detail)
            assertEquals(VALID_LISTING_ID, food.common.id)
            assertEquals(true, food.common.isClaimable)
            assertEquals(1_783_073_730_000L, food.common.publishedAtEpochMilliseconds)
            assertEquals(listOf("beninoise"), food.cuisines)
        } finally {
            client.close()
        }
    }

    @Test
    fun getListingDetail_mapsAnEmptyRpcResponseToNotFound() = runTest {
        val client = createCatalogTestClient("[]", HttpStatusCode.OK)

        try {
            assertFailsWith<CatalogDataException.NotFound> {
                SupabaseCatalogDataSource(client.postgrest).getListingDetail(VALID_LISTING_ID)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun getListingDetail_keepsAMissingRpcContractAsUnexpected() = runTest {
        val client = createCatalogTestClient(MISSING_RPC_RESPONSE, HttpStatusCode.NotFound)

        try {
            assertFailsWith<CatalogDataException.Unexpected> {
                SupabaseCatalogDataSource(client.postgrest).getListingDetail(VALID_LISTING_ID)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun getListingDetail_rejectsUnknownPayloadFieldsAtEveryDepth() = runTest {
        val invalidResponses = listOf(
            CATALOG_DETAIL_RPC_RESPONSE.replace(
                "\"schema_version\": 1,",
                "\"schema_version\": 1, \"unexpected_root\": true,",
            ),
            CATALOG_DETAIL_RPC_RESPONSE.replace(
                "\"city\": {\"id\": \"cotonou\", \"name\": \"Cotonou\"}",
                "\"city\": {\"id\": \"cotonou\", \"name\": \"Cotonou\", \"unexpected_nested\": true}",
            ),
        )

        invalidResponses.forEach { response ->
            val client = createCatalogTestClient(response, HttpStatusCode.OK)
            try {
                assertFailsWith<CatalogDataException.Unexpected> {
                    SupabaseCatalogDataSource(client.postgrest).getListingDetail(VALID_LISTING_ID)
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun setListingLike_callsVersionedTargetStateRpcAndKeepsNullableCount() = runTest {
        val client = createCatalogLikeTestClient { request ->
            assertEquals("/rest/v1/rpc/set_listing_like_v2", request.url.encodedPath)
            val body = assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString()
            val parameters = Json.parseToJsonElement(body).jsonObject
            assertEquals(VALID_ACCOUNT_ID, parameters.getValue("p_expected_account_id").jsonPrimitive.content)
            assertEquals(VALID_LISTING_ID, parameters.getValue("p_listing_id").jsonPrimitive.content)
            assertEquals("false", parameters.getValue("p_liked").jsonPrimitive.content)
            CATALOG_LIKE_RPC_RESPONSE
        }

        try {
            val mutation = SupabaseCatalogDataSource(client.postgrest)
                .setListingLike(
                    expectedAccountId = VALID_ACCOUNT_ID,
                    listingId = VALID_LISTING_ID,
                    liked = false,
                )
                .toDomain(expectedListingId = VALID_LISTING_ID, expectedLiked = false)

            assertEquals(false, mutation.liked)
            assertEquals(null, mutation.likesCount)
            assertEquals(1_786_305_600_000L, mutation.mutatedAtEpochMilliseconds)
        } finally {
            client.close()
        }
    }

    @Test
    fun setListingLike_rejectsMissingOrDuplicateConfirmationRows() = runTest {
        listOf("[]", "[$CATALOG_LIKE_RPC_ROW,$CATALOG_LIKE_RPC_ROW]").forEach { response ->
            val client = createCatalogLikeTestClient { response }
            try {
                assertFailsWith<CatalogDataException.Unexpected> {
                    SupabaseCatalogDataSource(client.postgrest)
                        .setListingLike(
                            expectedAccountId = VALID_ACCOUNT_ID,
                            listingId = VALID_LISTING_ID,
                            liked = false,
                        )
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun setListingLike_mapsExpectedAccountMismatchToAuthenticationRequiredWithoutV1Fallback() = runTest {
        val client = createCatalogLikeTestClient(status = HttpStatusCode.Forbidden) { request ->
            assertEquals("/rest/v1/rpc/set_listing_like_v2", request.url.encodedPath)
            EXPECTED_ACCOUNT_MISMATCH_RESPONSE
        }

        try {
            assertFailsWith<CatalogDataException.AuthenticationRequired> {
                SupabaseCatalogDataSource(client.postgrest).setListingLike(
                    expectedAccountId = VALID_ACCOUNT_ID,
                    listingId = VALID_LISTING_ID,
                    liked = true,
                )
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun toSummaryPage_usesLastRetainedRowCursorWhenSentinelExists() {
        val rows = listOf(
            summaryRow(id = "listing-1", rowCursor = "cursor-1"),
            summaryRow(id = "listing-2", rowCursor = "cursor-2"),
            summaryRow(id = "listing-3", rowCursor = "cursor-3"),
        )

        val page = rows.toSummaryPage(limit = 2)

        assertEquals(listOf("listing-1", "listing-2"), page.items.map { item -> item.id })
        assertEquals("cursor-2", page.nextCursor)
    }

    @Test
    fun toSummaryPage_returnsNoCursorWhenPageExactlyFillsLimit() {
        val rows = listOf(
            summaryRow(id = "listing-1", rowCursor = "cursor-1"),
            summaryRow(id = "listing-2", rowCursor = "cursor-2"),
        )

        val page = rows.toSummaryPage(limit = 2)

        assertEquals(rows, page.items)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun toSummaryPage_rejectsBlankContinuationCursor() {
        val rows = listOf(
            summaryRow(id = "listing-1", rowCursor = "cursor-1"),
            summaryRow(id = "listing-2", rowCursor = "   "),
            summaryRow(id = "listing-3", rowCursor = "cursor-3"),
        )

        assertFailsWith<CatalogDataException.Unexpected> {
            rows.toSummaryPage(limit = 2)
        }
    }
}

private fun createCatalogTestClient(responseBody: String, status: HttpStatusCode) = createSupabaseClient(
    supabaseUrl = "https://example.invalid",
    supabaseKey = "publishable-test-key",
) {
    httpEngine = MockEngine { request ->
        assertEquals("/rest/v1/rpc/get_catalog_detail_v1", request.url.encodedPath)
        respond(
            content = responseBody,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }
    install(Postgrest)
}

private fun createCatalogSearchTestClient(
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

private fun createCatalogLikeTestClient(
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

private const val VALID_LISTING_ID = "11111111-1111-4111-8111-111111111111"
private const val VALID_ACCOUNT_ID = "99999999-9999-4999-8999-999999999999"
private const val MISSING_RPC_RESPONSE = """
{
  "code": "PGRST202",
  "details": "Searched for the function public.get_catalog_detail_v1 in the schema cache.",
  "hint": null,
  "message": "Could not find the function public.get_catalog_detail_v1 in the schema cache"
}
"""

private const val CATALOG_LIKE_RPC_ROW = """
{
  "listing_id":"11111111-1111-4111-8111-111111111111",
  "liked":false,
  "likes_count":null,
  "mutated_at":"2026-08-09T20:00:00Z"
}
"""

private const val CATALOG_LIKE_RPC_RESPONSE = "[$CATALOG_LIKE_RPC_ROW]"
private const val EXPECTED_ACCOUNT_MISMATCH_RESPONSE =
    """{"code":"42501","details":null,"hint":null,"message":"expected account mismatch"}"""

private const val CATALOG_DETAIL_RPC_RESPONSE = """
[
  {
    "payload": {
      "schema_version": 1,
      "id": "11111111-1111-4111-8111-111111111111",
      "type": "etablissement",
      "subtype": "restaurant",
      "listing_class": "commercial",
      "name": "Restaurant Kwabor",
      "slug": "restaurant-kwabor",
      "description": "Restaurant de test avec une description assez complete pour le contrat detail.",
      "content_lang": "fr",
      "city": {"id": "cotonou", "name": "Cotonou"},
      "category": {"id": "restaurant", "label_key": "category.restaurant"},
      "location": {
        "district": "Ganhi",
        "address": "Rue de test",
        "latitude": 6.370293,
        "longitude": 2.391236
      },
      "price": {"from_xof": 5000, "unit": "par_personne", "tier": 2},
      "opening_hours": {
        "monday": {"status": "periods", "periods": [{"opens_minute": 480, "closes_minute": 1080, "closes_next_day": false}]},
        "tuesday": {"status": "periods", "periods": [{"opens_minute": 480, "closes_minute": 1080, "closes_next_day": false}]},
        "wednesday": {"status": "periods", "periods": [{"opens_minute": 480, "closes_minute": 1080, "closes_next_day": false}]},
        "thursday": {"status": "periods", "periods": [{"opens_minute": 480, "closes_minute": 1080, "closes_next_day": false}]},
        "friday": {"status": "periods", "periods": [{"opens_minute": 480, "closes_minute": 120, "closes_next_day": true}]},
        "saturday": {"status": "open_24_hours", "periods": []},
        "sunday": {"status": "closed", "periods": []}
      },
      "contact": {
        "phone": "+2290100000000",
        "whatsapp": null,
        "external_url": null,
        "email": null
      },
      "socials": {},
      "tags": ["restaurant", "benin"],
      "verified": true,
      "is_claimable": true,
      "metrics": {
        "rating_average": 4.5,
        "rating_count": 24,
        "views_count": 1200,
        "likes_count": 12
      },
      "published_at": "2026-07-03T11:15:30+01:00",
      "media": [
        {
          "kind": "image",
          "url": "https://cdn.kwabor.test/cover.jpg",
          "alt": "Photo principale du restaurant",
          "display_order": 0,
          "is_cover": true
        }
      ],
      "amenities": [
        {"id": "wifi", "label_key": "amenity.wifi", "display_order": 0}
      ],
      "detail": {
        "variant": "food",
        "cuisines": ["beninoise"],
        "meals": ["dejeuner"],
        "reservation": true,
        "menu_url": null
      }
    }
  }
]
"""

private const val CATALOG_SEARCH_RPC_RESPONSE = """
[
  {
    "id":"listing-1","type":"etablissement","listing_class":"commercial","status":"publie",
    "name":"Restaurant 1","city_id":"cotonou","category_id":"commercial-restaurant",
    "cover_image_url":null,"price_from_xof":5000,"rating_avg":4.5,"likes_count":12,
    "verified":true,"sponsored_until":null,"is_sponsored_placement":false,"row_cursor":"cursor-1"
  },
  {
    "id":"listing-2","type":"etablissement","listing_class":"commercial","status":"publie",
    "name":"Restaurant 2","city_id":"cotonou","category_id":"commercial-restaurant",
    "cover_image_url":null,"price_from_xof":7000,"rating_avg":4.2,"likes_count":8,
    "verified":true,"sponsored_until":null,"is_sponsored_placement":false,"row_cursor":"cursor-2"
  },
  {
    "id":"listing-3","type":"etablissement","listing_class":"commercial","status":"publie",
    "name":"Restaurant 3","city_id":"cotonou","category_id":"commercial-restaurant",
    "cover_image_url":null,"price_from_xof":9000,"rating_avg":4.0,"likes_count":4,
    "verified":false,"sponsored_until":null,"is_sponsored_placement":false,"row_cursor":"cursor-3"
  }
]
"""

private fun summaryRow(id: String, rowCursor: String): ListingSummaryDto = ListingSummaryDto(
    id = id,
    type = "etablissement",
    listingClass = "commercial",
    status = "publie",
    name = "Restaurant Kwabor",
    cityId = "cotonou",
    categoryId = "restaurants",
    coverImageUrl = "https://cdn.kwabor.test/$id.jpg",
    priceFromXof = 5_000,
    ratingAverage = 4.5,
    likesCount = 12,
    verified = true,
    sponsoredUntil = null,
    isSponsoredPlacement = false,
    rowCursor = rowCursor,
)
