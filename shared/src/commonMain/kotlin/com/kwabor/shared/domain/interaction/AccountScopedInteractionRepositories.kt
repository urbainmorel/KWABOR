package com.kwabor.shared.domain.interaction

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteMutation

data class ListingLikeMutation(
    val listingId: String,
    val liked: Boolean,
    val likesCount: Int?,
    val mutatedAtEpochMilliseconds: Long,
)

interface AccountScopedListingLikeRepository {
    suspend fun setListingLike(
        expectedAccountId: String,
        listingId: String,
        liked: Boolean,
    ): DomainResult<ListingLikeMutation>
}

interface AccountScopedFavoriteMutationRepository {
    suspend fun setFavorite(
        expectedAccountId: String,
        listingId: String,
        favorited: Boolean,
    ): DomainResult<FavoriteMutation>
}
