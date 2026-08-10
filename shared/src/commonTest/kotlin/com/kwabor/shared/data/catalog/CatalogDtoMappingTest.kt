package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogDayHours
import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogEventTicketing
import com.kwabor.shared.domain.catalog.CatalogOpeningHours
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.i18n.AppLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CatalogDtoMappingTest {
    @Test
    fun cityDto_mapsBeninCity() {
        val city = CityDto(id = "cotonou", name = "Cotonou").toDomain()

        assertEquals("cotonou", city.id)
        assertEquals("BJ", city.countryCode)
    }

    @Test
    fun categoryDto_mapsDatabaseEnums() {
        val category = CategoryDto(
            id = "restaurants",
            listingType = "etablissement",
            nameKey = "category.restaurants",
            defaultListingClass = "commercial",
        ).toDomain()

        assertEquals(ListingType.Establishment, category.listingType)
        assertEquals(ListingClass.Commercial, category.defaultListingClass)
    }

    @Test
    fun listingSummaryDto_mapsMoneyStatusAndSponsorDate() {
        val summary = catalogSummaryDto(
            priceFromXof = 15_000,
            sponsoredUntil = "2026-07-03T10:15:30Z",
            coverImageUrl = "https://cdn.kwabor.test/cover.jpg",
        ).toDomain()

        assertEquals(ListingStatus.Published, summary.status)
        assertEquals(15_000L, summary.priceFromXof?.amount)
        assertEquals(1_783_073_730_000L, summary.sponsoredUntilEpochMilliseconds)
        assertEquals(true, summary.isSponsoredPlacement)
        assertEquals("https://cdn.kwabor.test/cover.jpg", summary.coverImageUrl)
    }

    @Test
    fun catalogSummaryRpc_decodesRowsAndEncodesStableParameterNames() {
        val row = Json.decodeFromString<List<ListingSummaryDto>>(CATALOG_SUMMARY_RPC_RESPONSE).single()
        val parameters = ListingSummaryPageRpcDto(
            cityId = "cotonou",
            categoryId = null,
            listingType = "etablissement",
            listingClass = "commercial",
            searchQuery = null,
            cursor = "cursor-current",
            limit = 20,
        )
        val encodedParameters = Json.encodeToJsonElement(ListingSummaryPageRpcDto.serializer(), parameters).jsonObject

        assertEquals(CATALOG_LISTING_ID_ONE, row.id)
        assertEquals(5_000L, row.priceFromXof)
        assertEquals(4.5, row.ratingAverage)
        assertEquals(true, row.isSponsoredPlacement)
        assertEquals("cursor-next", row.rowCursor)
        assertEquals(
            setOf(
                "p_city_id",
                "p_category_id",
                "p_listing_type",
                "p_listing_class",
                "p_search_query",
                "p_cursor",
                "p_limit",
            ),
            encodedParameters.keys,
        )
        assertEquals(JsonNull, encodedParameters.getValue("p_category_id"))
        assertEquals(JsonNull, encodedParameters.getValue("p_search_query"))
    }

    @Test
    fun catalogSearchRpc_encodesTheVersionedSearchContract() {
        val parameters = CatalogSearchSummaryPageRpcDto(
            searchQuery = "restaurant cotonou",
            cityId = "cotonou",
            categoryId = "commercial-restaurant",
            listingType = "etablissement",
            listingClass = "commercial",
            cursor = "cursor-current",
            limit = 20,
        )
        val encodedParameters = Json.encodeToJsonElement(
            CatalogSearchSummaryPageRpcDto.serializer(),
            parameters,
        ).jsonObject

        assertEquals(
            setOf(
                "p_search_query",
                "p_city_id",
                "p_category_id",
                "p_listing_type",
                "p_listing_class",
                "p_cursor",
                "p_limit",
            ),
            encodedParameters.keys,
        )
        assertEquals("restaurant cotonou", encodedParameters.getValue("p_search_query").jsonPrimitive.content)
        assertEquals("commercial-restaurant", encodedParameters.getValue("p_category_id").jsonPrimitive.content)
    }

    @Test
    fun catalogDetailRpc_encodesOnlyTheVersionedListingParameter() {
        val parameters = CatalogDetailRpcParametersDto(listingId = CATALOG_LISTING_ID_ONE)
        val encodedParameters = Json.encodeToJsonElement(
            CatalogDetailRpcParametersDto.serializer(),
            parameters,
        ).jsonObject

        assertEquals(setOf("p_listing_id"), encodedParameters.keys)
        assertEquals(CATALOG_LISTING_ID_ONE, encodedParameters.getValue("p_listing_id").jsonPrimitive.content)
    }

    @Test
    fun catalogDetailPayload_mapsStrictFoodScheduleSocialsAndOfficialMedia() {
        val detail = assertIs<CatalogDetail.Establishment.Food>(catalogDetailPayloadDto().toDomain())

        assertEquals(AppLocale.French, detail.common.contentLocale)
        assertEquals(6.370293, detail.common.location.geoPoint?.latitude)
        assertEquals("https://cdn.kwabor.test/cover.jpg", detail.common.media.single().url)
        assertEquals(1_783_073_730_000, detail.common.publishedAtEpochMilliseconds)
        assertEquals(listOf("beninoise"), detail.cuisines)
        assertEquals(7, assertIs<CatalogOpeningHours.Weekly>(detail.common.openingHours).days.size)
        assertIs<CatalogDayHours.Open24Hours>(
            assertIs<CatalogOpeningHours.Weekly>(detail.common.openingHours).days[5].hours,
        )
        assertEquals("https://instagram.com/kwabor", detail.common.socialLinks.single().url)
        assertEquals(true, detail.common.isClaimable)
    }

    @Test
    fun catalogDetailPayload_mapsPaidEventVenueAndTicketTier() {
        val payload = catalogDetailPayloadDto(
            type = "evenement",
            subtype = "culture",
            listingClass = "evenementiel",
            openingHours = jsonObject("{}"),
            detail = eventDetailJson(),
        ).copy(price = CatalogDetailPriceDto(fromXof = 5_000, unit = "par_entree"))

        val detail = assertIs<CatalogDetail.Event>(payload.toDomain())

        assertEquals("11111111-1111-1111-1111-111111111111", detail.venue?.id)
        val ticketing = assertIs<CatalogEventTicketing.Paid>(detail.ticketing)
        assertEquals(5_000L, ticketing.tiers.single().price.amount)
    }

    @Test
    fun catalogDetailPayload_mapsEveryNonEventVariant() {
        val place = catalogDetailPayloadDto(
            type = "lieu",
            subtype = "heritage-historique",
            listingClass = "patrimonial",
            detail = placeDetailJson(),
        ).copy(
            price = CatalogDetailPriceDto(fromXof = null, unit = "aucune"),
            isClaimable = false,
        )
        val commercialPlace = catalogDetailPayloadDto(
            type = "lieu",
            subtype = "parc-attractions",
            listingClass = "commercial",
            detail = jsonObject(
                """
                {
                  "variant":"place",
                  "place_category":"parc-attractions",
                  "is_free":true,
                  "entry_fee_xof":null,
                  "fee_note":null
                }
                """.trimIndent(),
            ),
        ).copy(price = CatalogDetailPriceDto(fromXof = null, unit = "aucune"))
        val lodging = catalogDetailPayloadDto(
            subtype = "hotel",
            detail = lodgingDetailJson(),
        ).copy(price = CatalogDetailPriceDto(fromXof = 15_000, unit = "par_nuit"))
        val nightlife = catalogDetailPayloadDto(
            subtype = "club",
            detail = nightlifeDetailJson(),
        ).copy(price = CatalogDetailPriceDto(fromXof = 10_000, unit = "consommation"))
        val guide = catalogDetailPayloadDto(
            subtype = "guide",
            detail = guideDetailJson(),
        ).copy(price = CatalogDetailPriceDto(fromXof = 20_000, unit = "par_personne"))

        assertIs<CatalogDetail.Place>(place.toDomain())
        assertEquals(true, assertIs<CatalogDetail.Place>(commercialPlace.toDomain()).common.isClaimable)
        assertEquals(14 * 60, assertIs<CatalogDetail.Establishment.Lodging>(lodging.toDomain()).checkInMinute)
        assertEquals(18, assertIs<CatalogDetail.Establishment.Nightlife>(nightlife.toDomain()).minimumAge)
        assertEquals(
            listOf("fr", "fon"),
            assertIs<CatalogDetail.Establishment.Guide>(guide.toDomain()).languages,
        )
    }

    @Test
    fun catalogDetailPayload_rejectsTypeVariantMismatch() {
        val payload = catalogDetailPayloadDto(type = "lieu", listingClass = "patrimonial")

        assertFailsWith<CatalogDataException.Unexpected> {
            payload.toDomain()
        }
    }

    @Test
    fun catalogDetailPayload_rejectsIncompleteOpeningWeek() {
        val payload = catalogDetailPayloadDto(
            openingHours = jsonObject(
                """{"monday":{"status":"closed","periods":[]}}""",
            ),
        )

        assertFailsWith<CatalogDataException.Unexpected> {
            payload.toDomain()
        }
    }

    @Test
    fun catalogDetailPayload_rejectsMalformedOrPrivateHttpsUrls() {
        val urlAboveUtf8Limit = "https://host.test/aa" + "🐕".repeat(508)
        val invalidUrls = listOf(
            "https://host:abc/path",
            "https://host.test:99999/path",
            "https://host.test:8443/path",
            "https://host.test:0443/path",
            "https://host.test:00443/path",
            "https://host.test\\path",
            "https://[::::]/path",
            "https://127.0.0.1/path",
            "https://10.0.0.1/path",
            "https://home.arpa/path",
            "https://media.foo.lan/path",
            "https://media.home.arpa/path",
            "https://CDN.kwabor.test/path",
            "https://média.kwabor.test/path",
            "https://host.test/path%",
            "https://host.test/path%2",
            "https://host.test/path%GG",
            "https://host.test/path#",
            urlAboveUtf8Limit,
        )

        invalidUrls.forEach { url ->
            val payload = catalogDetailPayloadDto().copy(
                contact = CatalogDetailContactDto(externalUrl = url),
            )
            assertFailsWith<CatalogDataException.Unexpected>(url) {
                payload.toDomain()
            }
        }
    }

    @Test
    fun catalogDetailPayload_acceptsCanonicalEscapesAndExactUtf8UrlLimit() {
        val urls = listOf(
            "https://host.test/path%25",
            "https://host.test/%F0%9F%90%95?q=%25",
            "https://host.test/aa" + "🐕".repeat(507),
        )

        urls.forEach { url ->
            val detail = catalogDetailPayloadDto().copy(
                contact = CatalogDetailContactDto(externalUrl = url),
            ).toDomain()

            assertEquals(url, detail.common.contact.externalUrl)
        }
    }

    @Test
    fun listingDto_rejectsInvalidDatabaseEnum() {
        val dto = catalogSummaryDto(type = "invalid")

        assertFailsWith<CatalogDataException.Unexpected> {
            dto.toDomain()
        }
    }

    @Test
    fun listingViewerInteractionDto_mapsStateAndCount() {
        val interaction = listingViewerInteractionDto(
            likedByCurrentUser = true,
            favoritedByCurrentUser = false,
            likesCount = 7,
        ).toDomain()

        assertEquals(CATALOG_LISTING_ID_ONE, interaction.listingId)
        assertEquals(true, interaction.likedByViewer)
        assertEquals(false, interaction.favoritedByViewer)
        assertEquals(7, interaction.likesCount)
    }

    @Test
    fun listingViewerInteractionDto_rejectsNegativeCount() {
        val dto = listingViewerInteractionDto(likesCount = -1)

        assertFailsWith<CatalogDataException.Unexpected> {
            dto.toDomain()
        }
    }

    @Test
    fun listingLikeMutationDto_preservesNullableCountAndMapsServerClock() {
        val mutation = ListingLikeMutationDto(
            listingId = CATALOG_LISTING_ID_ONE,
            liked = false,
            likesCount = null,
            mutatedAt = "2026-08-09T20:00:00Z",
        ).toDomain(expectedListingId = CATALOG_LISTING_ID_ONE, expectedLiked = false)

        assertEquals(false, mutation.liked)
        assertEquals(null, mutation.likesCount)
        assertEquals(1_786_305_600_000L, mutation.mutatedAtEpochMilliseconds)
    }

    @Test
    fun listingLikeMutationDto_rejectsMismatchedTargetAndInvalidCount() {
        val mismatched = ListingLikeMutationDto(
            listingId = CATALOG_LISTING_ID_ONE,
            liked = false,
            likesCount = 1,
            mutatedAt = "2026-08-09T20:00:00Z",
        )
        val invalidCount = mismatched.copy(liked = true, likesCount = -1)

        assertFailsWith<CatalogDataException.Unexpected> {
            mismatched.toDomain(expectedListingId = CATALOG_LISTING_ID_ONE, expectedLiked = true)
        }
        assertFailsWith<CatalogDataException.Unexpected> {
            invalidCount.toDomain(expectedListingId = CATALOG_LISTING_ID_ONE, expectedLiked = true)
        }
    }

    @Test
    fun listingTypeAndClass_serializeToDatabaseValues() {
        assertEquals("lieu", ListingType.Place.toDatabaseValue())
        assertEquals("etablissement", ListingType.Establishment.toDatabaseValue())
        assertEquals("evenement", ListingType.Event.toDatabaseValue())
        assertEquals("patrimonial", ListingClass.Heritage.toDatabaseValue())
        assertEquals("commercial", ListingClass.Commercial.toDatabaseValue())
        assertEquals("evenementiel", ListingClass.Event.toDatabaseValue())
    }
}

private fun catalogSummaryDto(
    type: String = "etablissement",
    priceFromXof: Long? = null,
    sponsoredUntil: String? = null,
    coverImageUrl: String? = null,
    isSponsoredPlacement: Boolean = true,
): ListingSummaryDto = ListingSummaryDto(
    id = CATALOG_LISTING_ID_ONE,
    type = type,
    listingClass = "commercial",
    status = "publie",
    name = "Restaurant Kwabor",
    cityId = "cotonou",
    categoryId = "restaurants",
    coverImageUrl = coverImageUrl,
    priceFromXof = priceFromXof,
    ratingAverage = 4.5,
    likesCount = 12,
    verified = true,
    sponsoredUntil = sponsoredUntil,
    isSponsoredPlacement = isSponsoredPlacement,
    rowCursor = "cursor-listing-1",
)

internal fun listingViewerInteractionDto(
    listingId: String = CATALOG_LISTING_ID_ONE,
    likedByCurrentUser: Boolean = false,
    favoritedByCurrentUser: Boolean = false,
    likesCount: Int = 12,
): ListingViewerInteractionDto = ListingViewerInteractionDto(
    listingId = listingId,
    likedByCurrentUser = likedByCurrentUser,
    favoritedByCurrentUser = favoritedByCurrentUser,
    likesCount = likesCount,
)

private const val CATALOG_SUMMARY_RPC_RESPONSE = """
[
  {
    "id": "11111111-1111-4111-8111-111111111111",
    "type": "etablissement",
    "listing_class": "commercial",
    "status": "publie",
    "name": "Restaurant Kwabor",
    "city_id": "cotonou",
    "category_id": "restaurants",
    "cover_image_url": "https://cdn.kwabor.test/cover.jpg",
    "price_from_xof": 5000,
    "rating_avg": 4.50,
    "likes_count": 12,
    "verified": true,
    "sponsored_until": "2026-08-03T10:15:30+00:00",
    "is_sponsored_placement": true,
    "row_cursor": "cursor-next"
  }
]
"""
