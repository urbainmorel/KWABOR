package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogDetail
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SupabaseCatalogDataSourceTest {
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

private const val VALID_LISTING_ID = "11111111-1111-4111-8111-111111111111"
private const val MISSING_RPC_RESPONSE = """
{
  "code": "PGRST202",
  "details": "Searched for the function public.get_catalog_detail_v1 in the schema cache.",
  "hint": null,
  "message": "Could not find the function public.get_catalog_detail_v1 in the schema cache"
}
"""

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
