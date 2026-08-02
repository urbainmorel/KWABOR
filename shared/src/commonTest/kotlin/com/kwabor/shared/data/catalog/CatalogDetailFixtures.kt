package com.kwabor.shared.data.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal const val CATALOG_LISTING_ID_ONE = "11111111-1111-4111-8111-111111111111"
internal const val CATALOG_LISTING_ID_TWO = "22222222-2222-4222-8222-222222222222"

internal fun catalogDetailPayloadDto(
    type: String = "etablissement",
    subtype: String = "restaurant",
    listingClass: String = "commercial",
    openingHours: JsonObject = validOpeningHoursJson(),
    detail: JsonObject = foodDetailJson(),
): CatalogDetailPayloadDto = CatalogDetailPayloadDto(
    schemaVersion = 1,
    id = CATALOG_LISTING_ID_ONE,
    type = type,
    subtype = subtype,
    listingClass = listingClass,
    name = "Restaurant Kwabor",
    slug = "restaurant-kwabor",
    description = "Restaurant de test avec une description assez complete pour le contrat detail.",
    contentLang = "fr",
    city = CatalogDetailCityDto(id = "cotonou", name = "Cotonou"),
    category = CatalogDetailCategoryDto(id = "restaurant", labelKey = "category.restaurant"),
    location = CatalogDetailLocationDto(
        district = "Ganhi",
        address = "Rue de test",
        latitude = 6.370293,
        longitude = 2.391236,
    ),
    price = CatalogDetailPriceDto(fromXof = 5_000, unit = "par_personne", tier = 2),
    openingHours = openingHours,
    contact = CatalogDetailContactDto(
        phone = "+2290100000000",
        whatsapp = "+2290100000000",
        externalUrl = "https://kwabor.test/contact",
        email = "contact@kwabor.test",
    ),
    socials = jsonObject("""{"instagram":"https://instagram.com/kwabor"}"""),
    tags = listOf("restaurant", "benin"),
    verified = true,
    isClaimable = true,
    metrics = CatalogDetailMetricsDto(
        ratingAverage = 4.5,
        ratingCount = 24,
        viewsCount = 1_200,
        likesCount = 12,
    ),
    publishedAt = "2026-07-03T10:15:30Z",
    media = listOf(
        CatalogDetailMediaDto(
            kind = "image",
            url = "https://cdn.kwabor.test/cover.jpg",
            alt = "Photo principale du restaurant",
            displayOrder = 0,
            isCover = true,
        ),
    ),
    amenities = listOf(
        CatalogDetailAmenityDto(id = "wifi", labelKey = "amenity.wifi", displayOrder = 0),
    ),
    detail = detail,
)

internal fun validOpeningHoursJson(): JsonObject = jsonObject(
    """
    {
      "monday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":1080,"closes_next_day":false}]},
      "tuesday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":1080,"closes_next_day":false}]},
      "wednesday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":1080,"closes_next_day":false}]},
      "thursday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":1080,"closes_next_day":false}]},
      "friday":{"status":"periods","periods":[{"opens_minute":480,"closes_minute":120,"closes_next_day":true}]},
      "saturday":{"status":"open_24_hours","periods":[]},
      "sunday":{"status":"closed","periods":[]}
    }
    """.trimIndent(),
)

internal fun foodDetailJson(): JsonObject = jsonObject(
    """
    {
      "variant":"food",
      "cuisines":["beninoise"],
      "meals":["dejeuner","diner"],
      "reservation":true,
      "menu_url":"https://kwabor.test/menu"
    }
    """.trimIndent(),
)

internal fun placeDetailJson(): JsonObject = jsonObject(
    """
    {
      "variant":"place",
      "place_category":"heritage-historique",
      "is_free":true,
      "entry_fee_xof":null,
      "fee_note":"Acces libre"
    }
    """.trimIndent(),
)

internal fun lodgingDetailJson(): JsonObject = jsonObject(
    """
    {
      "variant":"lodging",
      "star_rating":4,
      "room_count":24,
      "checkin_time":"14:00:00",
      "checkout_time":"11:00:00",
      "room_types":[{"name":"Standard","price_xof":15000,"display_order":0}]
    }
    """.trimIndent(),
)

internal fun nightlifeDetailJson(): JsonObject = jsonObject(
    """{"variant":"nightlife","venue_kind":"club","min_age":18}""",
)

internal fun guideDetailJson(): JsonObject = jsonObject(
    """
    {
      "variant":"guide",
      "languages":["fr","fon"],
      "zones":["Ouidah"],
      "specialties":["patrimoine"],
      "indicative_price_xof":20000,
      "accreditation":"GUIDE-BJ-001",
      "experience_years":8
    }
    """.trimIndent(),
)

internal fun eventDetailJson(): JsonObject = jsonObject(
    """
    {
      "variant":"event",
      "category":"culture",
      "start_at":"2026-08-10T18:00:00Z",
      "end_at":"2026-08-10T22:00:00Z",
      "venue_listing":{
        "id":"11111111-1111-1111-1111-111111111111",
        "type":"lieu",
        "subtype":"heritage-historique",
        "name":"Place de l'Etoile Rouge",
        "city":{"id":"cotonou","name":"Cotonou"},
        "address":"Cotonou",
        "latitude":6.3800,
        "longitude":2.3900
      },
      "organizer":{"name":"Kwabor Culture","contact":"+2290100000000"},
      "ticketing":{
        "type":"payant",
        "url":"https://tickets.kwabor.test/event",
        "tiers":[{"label":"Standard","price_xof":5000,"display_order":0}]
      },
      "capacity":500
    }
    """.trimIndent(),
)

internal fun jsonObject(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
