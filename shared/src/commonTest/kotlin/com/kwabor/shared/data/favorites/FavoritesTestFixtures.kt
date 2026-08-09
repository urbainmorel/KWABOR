package com.kwabor.shared.data.favorites

internal const val FAVORITE_LISTING_ID_ONE = "11111111-1111-4111-8111-111111111111"
internal const val FAVORITE_LISTING_ID_TWO = "22222222-2222-4222-8222-222222222222"
internal const val FAVORITE_LISTING_ID_THREE = "33333333-3333-4333-8333-333333333333"
internal const val FAVORITE_CITY_ID = "cotonou"
internal const val FAVORITE_CATEGORY_ID = "commercial-restaurant"

internal fun validFavoriteListingRow(
    id: String = FAVORITE_LISTING_ID_ONE,
    type: String = "etablissement",
    cursor: String = "cursor-$id",
    favoritedAt: String = "2026-08-04T10:00:00Z",
): FavoriteListingRowDto = FavoriteListingRowDto(
    id = id,
    type = type,
    listingClass = if (type == "lieu") "patrimonial" else if (type == "evenement") "evenementiel" else "commercial",
    status = "publie",
    name = "Maison Kwabor",
    cityId = FAVORITE_CITY_ID,
    cityName = "Cotonou",
    categoryId = FAVORITE_CATEGORY_ID,
    coverImageUrl = "https://cdn.kwabor.test/listings/$id.jpg",
    coverImageAlt = "Façade de Maison Kwabor",
    priceFromXof = 5_000,
    ratingAverage = 4.5,
    likesCount = 12,
    verified = true,
    likedByCurrentUser = true,
    favoritedByCurrentUser = true,
    favoritedAt = favoritedAt,
    eventStartAt = if (type == "evenement") "2026-08-01T10:00:00Z" else null,
    eventEndAt = if (type == "evenement") "2026-08-01T12:00:00Z" else null,
    isEventEnded = type == "evenement",
    isSponsoredPlacement = false,
    rowCursor = cursor,
)

internal fun validFavoriteMutationRow(
    listingId: String = FAVORITE_LISTING_ID_ONE,
    favorited: Boolean = true,
): FavoriteMutationRowDto = FavoriteMutationRowDto(
    listingId = listingId,
    favoritedByCurrentUser = favorited,
    favoritedAt = if (favorited) "2026-08-04T10:00:00Z" else null,
)
