package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.favorites.FavoritesRepository

internal class RecordingExploreFavoritesRepository(
    var mutationResult: DomainResult<FavoriteMutation>? = null,
    var nextClientMutationSequence: Long = 1L,
) : FavoritesRepository {
    val mutations = mutableListOf<Pair<String, Boolean>>()

    override suspend fun listFavorites(
        filter: ListingType?,
        page: ListingPageRequest,
    ): DomainResult<FavoriteListingPage> = DomainResult.Success(FavoriteListingPage(emptyList(), null))

    override suspend fun setFavorite(listingId: String, favorited: Boolean): DomainResult<FavoriteMutation> {
        mutations += listingId to favorited
        val clientMutationSequence = nextClientMutationSequence++
        return mutationResult ?: DomainResult.Success(
            FavoriteMutation(
                listingId = listingId,
                favorited = favorited,
                favoritedAtEpochMilliseconds = if (favorited) 1_000L else null,
                clientMutationSequence = clientMutationSequence,
            ),
        )
    }
}

internal val AuthenticationRequiredFavoritesRepository: FavoritesRepository = RecordingExploreFavoritesRepository(
    mutationResult = DomainResult.Failure(DomainError.AuthenticationRequired()),
)

internal val OfflineFavoritesRepository: FavoritesRepository = RecordingExploreFavoritesRepository(
    mutationResult = DomainResult.Failure(DomainError.NetworkUnavailable()),
)
