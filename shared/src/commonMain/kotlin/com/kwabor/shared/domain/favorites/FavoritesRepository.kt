package com.kwabor.shared.domain.favorites

import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult

interface FavoritesRepository {
    suspend fun listFavorites(
        filter: ListingType? = null,
        page: ListingPageRequest = ListingPageRequest(),
    ): DomainResult<FavoriteListingPage>

    suspend fun setFavorite(listingId: String, favorited: Boolean): DomainResult<FavoriteMutation>
}
