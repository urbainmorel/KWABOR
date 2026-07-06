package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.i18n.AppLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        val summary = ListingSummaryDto(
            listing = listingDto(
                status = "publie",
                priceFromXof = 15_000,
                sponsoredUntil = "2026-07-03T10:15:30Z",
            ),
            coverImageUrl = "https://cdn.kwabor.test/cover.jpg",
        ).toDomain()

        assertEquals(ListingStatus.Published, summary.status)
        assertEquals(15_000, summary.priceFromXof?.amount)
        assertEquals(1_783_073_730_000, summary.sponsoredUntilEpochMilliseconds)
        assertEquals("https://cdn.kwabor.test/cover.jpg", summary.coverImageUrl)
    }

    @Test
    fun listingDetailDto_mapsLocaleGeoPointContactAndSortedMedia() {
        val detail = ListingDetailDto(
            listing = listingDto(
                contentLang = "fr",
                latitude = 6.370293,
                longitude = 2.391236,
                publishedAt = "2026-07-03T10:15:30Z",
            ),
            media = listOf(
                ListingMediaDto(
                    url = "https://cdn.kwabor.test/second.jpg",
                    alt = "Deuxieme photo",
                    displayOrder = 2,
                    isCover = false,
                ),
                ListingMediaDto(
                    url = "https://cdn.kwabor.test/cover.jpg",
                    alt = "Photo principale",
                    displayOrder = 1,
                    isCover = true,
                ),
            ),
        ).toDomain()

        assertEquals(AppLocale.French, detail.contentLocale)
        assertEquals(6.370293, detail.geoPoint?.latitude)
        assertEquals(2.391236, detail.geoPoint?.longitude)
        assertEquals("https://cdn.kwabor.test/cover.jpg", detail.summary.coverImageUrl)
        assertEquals("https://cdn.kwabor.test/cover.jpg", detail.media.first().url)
        assertEquals(1_783_073_730_000, detail.publishedAtEpochMilliseconds)
    }

    @Test
    fun listingDto_rejectsInvalidDatabaseEnum() {
        val dto = ListingSummaryDto(
            listing = listingDto(type = "invalid"),
            coverImageUrl = null,
        )

        assertFailsWith<IllegalStateException> {
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

        assertEquals("listing-1", interaction.listingId)
        assertEquals(true, interaction.likedByViewer)
        assertEquals(false, interaction.favoritedByViewer)
        assertEquals(7, interaction.likesCount)
    }

    @Test
    fun listingViewerInteractionDto_rejectsNegativeCount() {
        val dto = listingViewerInteractionDto(likesCount = -1)

        assertFailsWith<IllegalStateException> {
            dto.toDomain()
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

internal fun listingDto(
    id: String = "listing-1",
    type: String = "etablissement",
    listingClass: String = "commercial",
    status: String = "publie",
    contentLang: String = "fr",
    latitude: Double? = null,
    longitude: Double? = null,
    priceFromXof: Long? = null,
    sponsoredUntil: String? = null,
    publishedAt: String? = null,
): ListingDto = ListingDto(
    id = id,
    type = type,
    listingClass = listingClass,
    categoryId = "restaurants",
    ownerId = "owner-1",
    stewardId = null,
    status = status,
    name = "Restaurant Kwabor",
    slug = "restaurant-kwabor",
    description = "Restaurant de test pour verifier le mapping catalogue.",
    contentLang = contentLang,
    cityId = "cotonou",
    district = "Ganhi",
    address = "Rue de test",
    latitude = latitude,
    longitude = longitude,
    priceFromXof = priceFromXof,
    priceUnit = if (priceFromXof == null) "aucune" else "consommation",
    contactPhone = "+2290100000000",
    contactWhatsapp = "+2290100000000",
    externalUrl = "https://kwabor.test",
    email = "contact@kwabor.test",
    tags = listOf("benin", "restaurant"),
    verified = true,
    sponsoredUntil = sponsoredUntil,
    ratingAverage = 4.5,
    likesCount = 12,
    publishedAt = publishedAt,
)

internal fun listingViewerInteractionDto(
    listingId: String = "listing-1",
    likedByCurrentUser: Boolean = false,
    favoritedByCurrentUser: Boolean = false,
    likesCount: Int = 12,
): ListingViewerInteractionDto = ListingViewerInteractionDto(
    listingId = listingId,
    likedByCurrentUser = likedByCurrentUser,
    favoritedByCurrentUser = favoritedByCurrentUser,
    likesCount = likesCount,
)
