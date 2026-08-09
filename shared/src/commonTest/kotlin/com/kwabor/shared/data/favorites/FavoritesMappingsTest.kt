package com.kwabor.shared.data.favorites

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FavoritesMappingsTest {
    @Test
    fun listingRow_mapsThePrivatePublishedFavoriteContract() {
        val favorite = validFavoriteListingRow().toDomain(expectedType = ListingType.Establishment)

        assertEquals(FAVORITE_LISTING_ID_ONE, favorite.id)
        assertEquals(ListingType.Establishment, favorite.type)
        assertEquals(ListingClass.Commercial, favorite.listingClass)
        assertEquals("Cotonou", favorite.cityName)
        assertEquals(5_000, favorite.priceFromXof?.amount)
        assertEquals(4.5, favorite.ratingAverage)
        assertEquals(12, favorite.likesCount)
        assertEquals(true, favorite.likedByViewer)
        assertEquals(1_785_837_600_000, favorite.favoritedAtEpochMilliseconds)
        assertNull(favorite.eventStartAtEpochMilliseconds)
        assertEquals(false, favorite.isEventEnded)
    }

    @Test
    fun listingRow_mapsAnEndedEventWithoutDroppingIt() {
        val favorite = validFavoriteListingRow(type = "evenement").toDomain(ListingType.Event)

        assertEquals(ListingType.Event, favorite.type)
        assertEquals(ListingClass.Event, favorite.listingClass)
        assertEquals(1_785_578_400_000, favorite.eventStartAtEpochMilliseconds)
        assertEquals(1_785_585_600_000, favorite.eventEndAtEpochMilliseconds)
        assertEquals(true, favorite.isEventEnded)
    }

    @Test
    fun listingRow_rejectsInvalidUuidEnumsAndPrivateInvariants() {
        val invalidRows = listOf(
            "malformed UUID" to validFavoriteListingRow().copy(id = "not-a-uuid"),
            "uppercase UUID" to validFavoriteListingRow()
                .copy(id = FAVORITE_LISTING_ID_WITH_HEX_LETTERS.uppercase()),
            "unknown type" to validFavoriteListingRow().copy(type = "unsupported"),
            "unknown class" to validFavoriteListingRow().copy(listingClass = "unsupported"),
            "event with commercial class" to
                validFavoriteListingRow(type = "evenement").copy(listingClass = "commercial"),
            "establishment with heritage class" to
                validFavoriteListingRow(type = "etablissement").copy(listingClass = "patrimonial"),
            "unpublished listing" to validFavoriteListingRow().copy(status = "archive"),
            "missing viewer favorite" to validFavoriteListingRow().copy(favoritedByCurrentUser = false),
            "sponsored placement" to validFavoriteListingRow().copy(isSponsoredPlacement = true),
        )

        invalidRows.forEach { (caseName, row) ->
            assertFailsWith<FavoritesDataException.Unexpected>(message = caseName) { row.toDomain() }
        }
    }

    @Test
    fun listingRow_rejectsFilterMismatchAndInvalidScalarFields() {
        val invalidRows = listOf(
            validFavoriteListingRow().copy(name = " Maison Kwabor"),
            validFavoriteListingRow().copy(cityId = "not_a_canonical_identifier"),
            validFavoriteListingRow().copy(cityId = FAVORITE_CITY_ID.uppercase()),
            validFavoriteListingRow().copy(cityId = "a".repeat(101)),
            validFavoriteListingRow().copy(cityName = "Cotonou\n"),
            validFavoriteListingRow().copy(categoryId = "not_a_canonical_identifier"),
            validFavoriteListingRow().copy(categoryId = FAVORITE_CATEGORY_ID.uppercase()),
            validFavoriteListingRow().copy(priceFromXof = -1),
            validFavoriteListingRow().copy(ratingAverage = Double.NaN),
            validFavoriteListingRow().copy(ratingAverage = 5.01),
            validFavoriteListingRow().copy(likesCount = -1),
            validFavoriteListingRow().copy(favoritedAt = "not-a-timestamp"),
            validFavoriteListingRow().copy(rowCursor = "cursor with spaces"),
        )

        invalidRows.forEach { row ->
            assertFailsWith<FavoritesDataException.Unexpected> { row.toDomain() }
        }
        assertFailsWith<FavoritesDataException.Unexpected> {
            validFavoriteListingRow(type = "lieu").toDomain(expectedType = ListingType.Event)
        }
    }

    @Test
    fun listingRow_enforcesTheDatabaseNameBoundsInUnicodeCodePoints() {
        val astralCharacter = "\uD83D\uDE00"
        val exactlyEightyCodePoints = astralCharacter.repeat(80)

        assertEquals(
            exactlyEightyCodePoints,
            validFavoriteListingRow().copy(name = exactlyEightyCodePoints).toDomain().name,
        )
        listOf(
            "ab",
            "a".repeat(81),
            astralCharacter.repeat(81),
            "ab\uD800",
        ).forEach { invalidName ->
            assertFailsWith<FavoritesDataException.Unexpected> {
                validFavoriteListingRow().copy(name = invalidName).toDomain()
            }
        }
    }

    @Test
    fun listingRow_enforcesTheMobileSafeTimestampRange() {
        val minimum = validFavoriteListingRow()
            .copy(favoritedAt = "0001-01-01T00:00:00Z")
            .toDomain()
        assertEquals(-62_135_596_800_000L, minimum.favoritedAtEpochMilliseconds)

        listOf(
            "0000-12-31T23:59:59.999999999Z",
            "+10000-01-01T00:00:00Z",
        ).forEach { invalidTimestamp ->
            assertFailsWith<FavoritesDataException.Unexpected> {
                validFavoriteListingRow().copy(favoritedAt = invalidTimestamp).toDomain()
            }
        }
        assertFailsWith<FavoritesDataException.Unexpected> {
            validFavoriteListingRow(type = "evenement")
                .copy(eventStartAt = "+10000-01-01T00:00:00Z")
                .toDomain()
        }
    }

    @Test
    fun listingRow_requiresACompleteHttpsCoverPair() {
        val invalidRows = listOf(
            validFavoriteListingRow().copy(coverImageUrl = null),
            validFavoriteListingRow().copy(coverImageAlt = null),
            validFavoriteListingRow().copy(coverImageUrl = "http://cdn.kwabor.test/cover.jpg"),
            validFavoriteListingRow().copy(coverImageUrl = "https://localhost/cover.jpg"),
            validFavoriteListingRow().copy(coverImageUrl = "https://CDN.kwabor.test/cover.jpg"),
            validFavoriteListingRow().copy(coverImageAlt = ""),
        )

        invalidRows.forEach { row ->
            assertFailsWith<FavoritesDataException.Unexpected> { row.toDomain() }
        }

        val withoutCover = validFavoriteListingRow().copy(coverImageUrl = null, coverImageAlt = null).toDomain()
        assertNull(withoutCover.coverImageUrl)
        assertNull(withoutCover.coverImageAlt)
    }

    @Test
    fun listingRow_enforcesEventDateShape() {
        val invalidRows = listOf(
            validFavoriteListingRow().copy(eventStartAt = "2026-08-01T10:00:00Z"),
            validFavoriteListingRow(type = "evenement").copy(eventStartAt = null),
            validFavoriteListingRow(type = "evenement").copy(
                eventEndAt = "2026-08-01T09:00:00Z",
            ),
        )

        invalidRows.forEach { row ->
            assertFailsWith<FavoritesDataException.Unexpected> { row.toDomain() }
        }
    }

    @Test
    fun page_retainsTheLastVisibleCursorAndValidatesTheSentinel() {
        val page = listOf(
            validFavoriteListingRow(
                id = FAVORITE_LISTING_ID_ONE,
                cursor = "cursor-one",
                favoritedAt = "2026-08-04T10:00:00.000000003Z",
            ),
            validFavoriteListingRow(
                id = FAVORITE_LISTING_ID_TWO,
                cursor = "cursor-two",
                favoritedAt = "2026-08-04T10:00:00.000000002Z",
            ),
            validFavoriteListingRow(
                id = FAVORITE_LISTING_ID_THREE,
                cursor = "cursor-three",
                favoritedAt = "2026-08-04T10:00:00.000000001Z",
            ),
        ).toFavoriteListingPageDto(limit = 2).toDomain()

        assertEquals(listOf(FAVORITE_LISTING_ID_ONE, FAVORITE_LISTING_ID_TWO), page.items.map { it.id })
        assertEquals("cursor-two", page.nextCursor)

        assertFailsWith<FavoritesDataException.Unexpected> {
            listOf(
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_ONE),
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_TWO),
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_THREE).copy(status = "archive"),
            ).toFavoriteListingPageDto(limit = 2)
        }
    }

    @Test
    fun page_rejectsMoreThanOneSentinelAndDuplicateListings() {
        assertFailsWith<FavoritesDataException.Unexpected> {
            emptyList<FavoriteListingRowDto>().toFavoriteListingPageDto(limit = 0)
        }
        assertFailsWith<FavoritesDataException.Unexpected> {
            listOf(
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_ONE),
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_TWO),
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_THREE),
            ).toFavoriteListingPageDto(limit = 1)
        }
        assertFailsWith<FavoritesDataException.Unexpected> {
            listOf(
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_ONE, cursor = "cursor-one"),
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_ONE, cursor = "cursor-two"),
            ).toFavoriteListingPageDto(limit = 2)
        }
        assertFailsWith<FavoritesDataException.Unexpected> {
            FavoriteListingPageDto(
                items = listOf(validFavoriteListingRow(cursor = "cursor-one")),
                nextCursor = "different-cursor",
            ).toDomain()
        }
    }

    @Test
    fun page_rejectsDuplicateCursorsAndRowsOutsideStrictNewestFirstOrder() {
        assertFailsWith<FavoritesDataException.Unexpected> {
            listOf(
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_TWO, cursor = "same-cursor"),
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_ONE, cursor = "same-cursor"),
            ).toFavoriteListingPageDto(limit = 2)
        }
        assertFailsWith<FavoritesDataException.Unexpected> {
            listOf(
                validFavoriteListingRow(
                    id = FAVORITE_LISTING_ID_ONE,
                    favoritedAt = "2026-08-04T10:00:00.000000001Z",
                ),
                validFavoriteListingRow(
                    id = FAVORITE_LISTING_ID_TWO,
                    favoritedAt = "2026-08-04T10:00:00.000000002Z",
                ),
            ).toFavoriteListingPageDto(limit = 2)
        }
        assertFailsWith<FavoritesDataException.Unexpected> {
            listOf(
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_ONE, cursor = "cursor-one"),
                validFavoriteListingRow(id = FAVORITE_LISTING_ID_TWO, cursor = "cursor-two"),
            ).toFavoriteListingPageDto(limit = 2)
        }

        val correctlyTiedPage = listOf(
            validFavoriteListingRow(id = FAVORITE_LISTING_ID_TWO, cursor = "cursor-two"),
            validFavoriteListingRow(id = FAVORITE_LISTING_ID_ONE, cursor = "cursor-one"),
        ).toFavoriteListingPageDto(limit = 2)
        assertEquals(
            listOf(FAVORITE_LISTING_ID_TWO, FAVORITE_LISTING_ID_ONE),
            correctlyTiedPage.items.map(FavoriteListingRowDto::id),
        )
    }

    @Test
    fun mutation_mapsValidStateTimestampAndSequence() {
        val added = validFavoriteMutationRow().toDomain(
            expectedListingId = FAVORITE_LISTING_ID_ONE,
            expectedFavorited = true,
            clientMutationSequence = 7L,
        )
        val removed = validFavoriteMutationRow(favorited = false).toDomain(
            expectedListingId = FAVORITE_LISTING_ID_ONE,
            expectedFavorited = false,
            clientMutationSequence = 8L,
        )

        assertEquals(true, added.favorited)
        assertEquals(1_785_837_600_000, added.favoritedAtEpochMilliseconds)
        assertEquals(7L, added.clientMutationSequence)
        assertEquals(false, removed.favorited)
        assertNull(removed.favoritedAtEpochMilliseconds)
        assertEquals(8L, removed.clientMutationSequence)
    }

    @Test
    fun mutation_rejectsMismatchedShapeAndNonPositiveSequence() {
        val invalidRows = listOf(
            validFavoriteMutationRow(listingId = FAVORITE_LISTING_ID_TWO),
            validFavoriteMutationRow().copy(favoritedByCurrentUser = false),
            validFavoriteMutationRow().copy(favoritedAt = null),
            validFavoriteMutationRow().copy(favoritedAt = "invalid"),
        )
        invalidRows.forEach { row ->
            assertFailsWith<FavoritesDataException.Unexpected> {
                row.toDomain(
                    expectedListingId = FAVORITE_LISTING_ID_ONE,
                    expectedFavorited = true,
                    clientMutationSequence = 1L,
                )
            }
        }
        assertFailsWith<FavoritesDataException.Unexpected> {
            validFavoriteMutationRow().toDomain(
                expectedListingId = FAVORITE_LISTING_ID_ONE,
                expectedFavorited = true,
                clientMutationSequence = 0L,
            )
        }
    }
}
