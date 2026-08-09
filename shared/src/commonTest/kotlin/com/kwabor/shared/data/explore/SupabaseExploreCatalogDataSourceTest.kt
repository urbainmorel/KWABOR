package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.explore.ExploreCatalogRequest
import com.kwabor.shared.domain.explore.ExploreEventWindow
import com.kwabor.shared.domain.explore.ExploreSort
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

class SupabaseExploreCatalogDataSourceTest {
    @Test
    fun listCatalog_callsV2WithElevenCanonicalParametersAndKeepsLastRetainedCursor() = runTest {
        val client = createExploreTestClient { request ->
            assertEquals("/rest/v1/rpc/list_catalog_summaries_v2", request.url.encodedPath)
            val body = assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString()
            val parameters = Json.parseToJsonElement(body).jsonObject
            assertEquals(EXPECTED_PARAMETER_KEYS, parameters.keys)
            assertEquals("etablissement", parameters.getValue("p_listing_type").jsonPrimitive.content)
            assertEquals("porto-novo", parameters.getValue("p_city_id").jsonPrimitive.content)
            assertEquals("commercial-restaurant", parameters.getValue("p_category_id").jsonPrimitive.content)
            assertEquals("commercial", parameters.getValue("p_listing_class").jsonPrimitive.content)
            assertEquals("popularity", parameters.getValue("p_sort").jsonPrimitive.content)
            assertEquals(1_000L, parameters.getValue("p_price_min_xof").jsonPrimitive.content.toLong())
            assertEquals(9_000L, parameters.getValue("p_price_max_xof").jsonPrimitive.content.toLong())
            assertIs<JsonNull>(parameters.getValue("p_event_window_start"))
            assertIs<JsonNull>(parameters.getValue("p_event_window_end"))
            assertEquals("opaque-before", parameters.getValue("p_cursor").jsonPrimitive.content)
            assertEquals(2, parameters.getValue("p_limit").jsonPrimitive.content.toInt())
            EXPLORE_ESTABLISHMENT_RESPONSE
        }
        try {
            val pageDto = SupabaseExploreCatalogDataSource(client.postgrest).listCatalog(
                establishmentRequest(cursor = "opaque-before"),
            )
            val page = pageDto.toDomain()

            assertEquals(listOf(ID_ONE, ID_TWO), page.items.map { item -> item.id })
            assertEquals("cursor-two", page.nextCursor)
            val snapshot = Instant.parse(SNAPSHOT)
            val expectedMicros = snapshot.toEpochMilliseconds() * 1_000 + 456
            assertEquals(expectedMicros, page.snapshotAtEpochMicroseconds)
            assertEquals("Photo du restaurant un", page.items.first().coverImageAlt)
            assertEquals(1_200L, page.items.first().viewsCount)
            assertEquals(ListingStatus.Published, page.items.first().status)
            assertEquals(true, page.items.first().isSponsoredPlacement)
            assertEquals(null, page.items.first().eventStartAtEpochMilliseconds)
        } finally {
            client.close()
        }
    }

    @Test
    fun listCatalog_encodesConcreteHalfOpenEventWindowAsUtcInstants() = runTest {
        val client = createExploreTestClient { request ->
            val body = assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes().decodeToString()
            val parameters = Json.parseToJsonElement(body).jsonObject
            assertEquals("2026-08-09T15:00:00Z", parameters.getValue("p_event_window_start").jsonPrimitive.content)
            assertEquals("2026-08-10T15:00:00Z", parameters.getValue("p_event_window_end").jsonPrimitive.content)
            "[]"
        }
        try {
            val page = SupabaseExploreCatalogDataSource(client.postgrest).listCatalog(
                ExploreCatalogRequest(
                    listingType = ListingType.Event,
                    sort = ExploreSort.TemporalProximity,
                    eventWindow = ExploreEventWindow(
                        startAtEpochMilliseconds = Instant.parse("2026-08-09T15:00:00Z").toEpochMilliseconds(),
                        endExclusiveAtEpochMilliseconds = Instant.parse("2026-08-10T15:00:00Z").toEpochMilliseconds(),
                    ),
                ),
            )

            assertEquals(emptyList(), page.items)
            assertEquals(null, page.nextCursor)
            assertEquals(null, page.snapshotAtEpochMicroseconds)
        } finally {
            client.close()
        }
    }

    @Test
    fun listCatalog_rejectsUnknownOrMissingKeysFromTheExactTwentyOneKeyProjection() = runTest {
        val invalidResponses = listOf(
            EXPLORE_SINGLE_ROW_RESPONSE.replace(
                "\"row_cursor\":\"cursor-one\"",
                "\"row_cursor\":\"cursor-one\",\"unexpected\":true",
            ),
            EXPLORE_SINGLE_ROW_RESPONSE.replace("\"cover_image_alt\":null,", ""),
        )

        invalidResponses.forEach { response ->
            val client = createExploreTestClient { response }
            try {
                assertFailsWith<ExploreCatalogDataException.Unexpected> {
                    SupabaseExploreCatalogDataSource(client.postgrest).listCatalog(establishmentRequest())
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun pageContract_rejectsExtraSentinelsDuplicateIdsCursorsAndSnapshots() {
        val request = establishmentRequest(limit = 1)
        val first = establishmentRow(id = ID_ONE, rowCursor = "cursor-one")
        val second = establishmentRow(id = ID_TWO, rowCursor = "cursor-two")
        val third = establishmentRow(id = ID_THREE, rowCursor = "cursor-three")

        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(first, second, third).toExploreCatalogPage(request)
        }
        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(first, second.copy(id = ID_ONE)).toExploreCatalogPage(request)
        }
        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(first, second.copy(rowCursor = first.rowCursor)).toExploreCatalogPage(request)
        }
        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(first, second.copy(snapshotAt = "2026-08-09T15:00:01.123456Z"))
                .toExploreCatalogPage(request)
        }
    }

    @Test
    fun pageContract_validatesSentinelAndNeverUsesItsCursor() {
        val request = establishmentRequest(limit = 1)
        val retained = establishmentRow(id = ID_ONE, rowCursor = "cursor-retained")
        val invalidSentinel = establishmentRow(
            id = ID_TWO,
            rowCursor = "cursor-sentinel",
            status = "brouillon",
        )

        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(retained, invalidSentinel).toExploreCatalogPage(request)
        }
        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(
                retained,
                establishmentRow(
                    id = ID_TWO,
                    rowCursor = "cursor-sentinel",
                    isSponsoredPlacement = false,
                ).copy(sponsoredUntil = "invalid"),
            ).toExploreCatalogPage(request)
        }

        val page = listOf(
            retained,
            establishmentRow(id = ID_TWO, rowCursor = "cursor-sentinel"),
        ).toExploreCatalogPage(request)
        assertEquals(listOf(ID_ONE), page.items.map { item -> item.id })
        assertEquals("cursor-retained", page.nextCursor)
    }

    @Test
    fun pageContract_enforcesPublishedTypeClassEventAndSponsorAuthority() {
        val establishmentRequest = establishmentRequest(limit = 2)
        val organic = establishmentRow(id = ID_ONE, rowCursor = "cursor-one", isSponsoredPlacement = false)
        val sponsorAfterOrganic = establishmentRow(id = ID_TWO, rowCursor = "cursor-two")
        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(organic, sponsorAfterOrganic).toExploreCatalogPage(establishmentRequest)
        }

        val eventRequest = ExploreCatalogRequest(
            listingType = ListingType.Event,
            listingClass = ListingClass.Event,
            sort = ExploreSort.TemporalProximity,
            eventWindow = ExploreEventWindow(
                startAtEpochMilliseconds = Instant.parse("2026-08-09T14:00:00Z").toEpochMilliseconds(),
                endExclusiveAtEpochMilliseconds = Instant.parse("2026-08-09T18:00:00Z").toEpochMilliseconds(),
            ),
            limit = 2,
        )
        val endedEvent = eventRow()
        val eventPage = listOf(endedEvent).toExploreCatalogPage(eventRequest).toDomain()
        assertEquals(true, eventPage.items.single().isEventEnded)
        assertEquals(
            Instant.parse("2026-08-09T15:00:00Z").toEpochMilliseconds(),
            eventPage.items.single().eventEndAtEpochMilliseconds,
        )

        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(endedEvent.copy(isEventEnded = false)).toExploreCatalogPage(eventRequest)
        }
        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(endedEvent.copy(isSponsoredPlacement = true)).toExploreCatalogPage(eventRequest)
        }
    }

    @Test
    fun pageContract_countsUnicodeCodePointsAndEnforcesMobileMicrosecondInstants() {
        val request = establishmentRequest(limit = 1)
        val eightyCodePointName = "🎉".repeat(80)
        listOf(establishmentRow(ID_ONE, "cursor-one").copy(name = eightyCodePointName))
            .toExploreCatalogPage(request)

        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(establishmentRow(ID_ONE, "cursor-one").copy(name = "$eightyCodePointName🎉"))
                .toExploreCatalogPage(request)
        }
        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(establishmentRow(ID_ONE, "cursor-one").copy(snapshotAt = "+10000-01-01T00:00:00Z"))
                .toExploreCatalogPage(request)
        }
        assertFailsWith<ExploreCatalogDataException.Unexpected> {
            listOf(establishmentRow(ID_ONE, "cursor-one").copy(snapshotAt = "2026-08-09T15:00:00.1234567Z"))
                .toExploreCatalogPage(request)
        }
    }

    @Test
    fun pageContract_rejectsNonCanonicalPublicCoverUrls() {
        val request = establishmentRequest(limit = 1)
        val validRow = establishmentRow(ID_ONE, "cursor-one")

        NON_CANONICAL_PUBLIC_HTTPS_URLS.forEach { url ->
            assertFailsWith<ExploreCatalogDataException.Unexpected> {
                listOf(
                    validRow.copy(
                        coverImageUrl = url,
                        coverImageAlt = "Photo du restaurant",
                    ),
                ).toExploreCatalogPage(request)
            }
        }
    }

    @Test
    fun listCatalog_mapsTransientGatewayFailureToNetworkUnavailable() = runTest {
        val client = createExploreTestClient(status = HttpStatusCode.ServiceUnavailable) {
            """{"code":"TEMPORARY","message":"Service unavailable"}"""
        }
        try {
            assertFailsWith<ExploreCatalogDataException.NetworkUnavailable> {
                SupabaseExploreCatalogDataSource(client.postgrest).listCatalog(establishmentRequest())
            }
        } finally {
            client.close()
        }
    }
}

private fun createExploreTestClient(
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

private fun establishmentRequest(cursor: String? = null, limit: Int = 2): ExploreCatalogRequest = ExploreCatalogRequest(
    listingType = ListingType.Establishment,
    cityId = "porto-novo",
    categoryId = "commercial-restaurant",
    listingClass = ListingClass.Commercial,
    sort = ExploreSort.Popularity,
    priceMinXof = 1_000,
    priceMaxXof = 9_000,
    cursor = cursor,
    limit = limit,
)

private fun establishmentRow(
    id: String,
    rowCursor: String,
    status: String = "publie",
    isSponsoredPlacement: Boolean = true,
): ExploreCatalogRowDto = ExploreCatalogRowDto(
    id = id,
    type = "etablissement",
    listingClass = "commercial",
    status = status,
    name = "Restaurant Kwabor",
    cityId = "porto-novo",
    categoryId = "commercial-restaurant",
    coverImageUrl = null,
    coverImageAlt = null,
    priceFromXof = 5_000,
    ratingAverage = 4.5,
    viewsCount = 1_200,
    likesCount = 30,
    verified = true,
    sponsoredUntil = "2026-08-10T15:00:00Z",
    eventStartAt = null,
    eventEndAt = null,
    isEventEnded = false,
    isSponsoredPlacement = isSponsoredPlacement,
    snapshotAt = SNAPSHOT,
    rowCursor = rowCursor,
)

private fun eventRow(): ExploreCatalogRowDto = ExploreCatalogRowDto(
    id = ID_ONE,
    type = "evenement",
    listingClass = "evenementiel",
    status = "publie",
    name = "Festival Kwabor",
    cityId = "porto-novo",
    categoryId = "commercial-restaurant",
    coverImageUrl = null,
    coverImageAlt = null,
    priceFromXof = null,
    ratingAverage = null,
    viewsCount = 20,
    likesCount = 3,
    verified = true,
    sponsoredUntil = "2026-08-10T15:00:00Z",
    eventStartAt = "2026-08-09T14:00:00Z",
    eventEndAt = "2026-08-09T15:00:00Z",
    isEventEnded = true,
    isSponsoredPlacement = false,
    snapshotAt = SNAPSHOT,
    rowCursor = "cursor-event",
)

private val EXPECTED_PARAMETER_KEYS = setOf(
    "p_listing_type",
    "p_city_id",
    "p_category_id",
    "p_listing_class",
    "p_sort",
    "p_price_min_xof",
    "p_price_max_xof",
    "p_event_window_start",
    "p_event_window_end",
    "p_cursor",
    "p_limit",
)

private const val ID_ONE = "10000000-0000-4000-8000-000000000001"
private const val ID_TWO = "10000000-0000-4000-8000-000000000002"
private const val ID_THREE = "10000000-0000-4000-8000-000000000003"
private const val SNAPSHOT = "2026-08-09T15:00:00.123456Z"
private const val OVERSIZED_PUBLIC_URL_CODE_POINT_COUNT = 600
private val NON_CANONICAL_PUBLIC_HTTPS_URLS = listOf(
    "https://",
    "https://user@cdn.kwabor.test/cover.jpg",
    "https://CDN.kwabor.test/cover.jpg",
    "https://cdn.kwabor.test/cover.jpg#fragment",
    "https://cdn.kwabor.test\\cover.jpg",
    "https://cdn.kwabor.test/%zz",
    "https://cdn.kwabor.test/${"🐕".repeat(OVERSIZED_PUBLIC_URL_CODE_POINT_COUNT)}",
)

private const val EXPLORE_SINGLE_ROW_RESPONSE = """
[
  {
    "id":"10000000-0000-4000-8000-000000000001",
    "type":"etablissement",
    "listing_class":"commercial",
    "status":"publie",
    "name":"Restaurant Kwabor",
    "city_id":"porto-novo",
    "category_id":"commercial-restaurant",
    "cover_image_url":null,
    "cover_image_alt":null,
    "price_from_xof":5000,
    "rating_avg":4.5,
    "views_count":1200,
    "likes_count":30,
    "verified":true,
    "sponsored_until":"2026-08-10T15:00:00Z",
    "event_start_at":null,
    "event_end_at":null,
    "is_event_ended":false,
    "is_sponsored_placement":true,
    "snapshot_at":"2026-08-09T15:00:00.123456Z",
    "row_cursor":"cursor-one"
  }
]
"""

private const val EXPLORE_ESTABLISHMENT_RESPONSE = """
[
  {
    "id":"10000000-0000-4000-8000-000000000001",
    "type":"etablissement","listing_class":"commercial","status":"publie",
    "name":"Restaurant Kwabor Un","city_id":"porto-novo","category_id":"commercial-restaurant",
    "cover_image_url":"https://cdn.kwabor.test/one.jpg","cover_image_alt":"Photo du restaurant un",
    "price_from_xof":5000,"rating_avg":4.5,"views_count":1200,"likes_count":30,"verified":true,
    "sponsored_until":"2026-08-10T15:00:00Z","event_start_at":null,"event_end_at":null,
    "is_event_ended":false,"is_sponsored_placement":true,
    "snapshot_at":"2026-08-09T15:00:00.123456Z","row_cursor":"cursor-one"
  },
  {
    "id":"10000000-0000-4000-8000-000000000002",
    "type":"etablissement","listing_class":"commercial","status":"publie",
    "name":"Restaurant Kwabor Deux","city_id":"porto-novo","category_id":"commercial-restaurant",
    "cover_image_url":null,"cover_image_alt":null,
    "price_from_xof":6000,"rating_avg":4.2,"views_count":900,"likes_count":25,"verified":true,
    "sponsored_until":"2026-08-10T15:00:00Z","event_start_at":null,"event_end_at":null,
    "is_event_ended":false,"is_sponsored_placement":true,
    "snapshot_at":"2026-08-09T15:00:00.123456Z","row_cursor":"cursor-two"
  },
  {
    "id":"10000000-0000-4000-8000-000000000003",
    "type":"etablissement","listing_class":"commercial","status":"publie",
    "name":"Restaurant Kwabor Trois","city_id":"porto-novo","category_id":"commercial-restaurant",
    "cover_image_url":null,"cover_image_alt":null,
    "price_from_xof":7000,"rating_avg":4.0,"views_count":700,"likes_count":20,"verified":false,
    "sponsored_until":null,"event_start_at":null,"event_end_at":null,
    "is_event_ended":false,"is_sponsored_placement":false,
    "snapshot_at":"2026-08-09T15:00:00.123456Z","row_cursor":"cursor-three"
  }
]
"""
