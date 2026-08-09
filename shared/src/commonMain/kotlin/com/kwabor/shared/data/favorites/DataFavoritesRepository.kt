package com.kwabor.shared.data.favorites

import com.kwabor.shared.data.core.isValidUuid
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoriteMutation
import com.kwabor.shared.domain.favorites.FavoritesRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DataFavoritesRepository internal constructor(
    private val dataSource: FavoritesDataSource,
) : FavoritesRepository {
    private val favoriteMutationMutex = Mutex()
    private var clientMutationSequence = 0L

    override suspend fun listFavorites(
        filter: ListingType?,
        page: ListingPageRequest,
    ): DomainResult<FavoriteListingPage> = runFavoritesCall {
        if (page.cursor?.isValidFavoriteCursor() == false) {
            throw FavoritesDataException.Validation("error.favorites.cursor_invalid")
        }
        dataSource.listFavorites(filter = filter, page = page).toDomain(expectedType = filter)
    }

    override suspend fun setFavorite(listingId: String, favorited: Boolean): DomainResult<FavoriteMutation> =
        favoriteMutationMutex.withLock {
            val sequence = ++clientMutationSequence
            withContext(NonCancellable) {
                runFavoritesCall {
                    val requiredListingId = listingId.toRequiredFavoriteListingId()
                    dataSource.setFavorite(listingId = requiredListingId, favorited = favorited)
                        .toDomain(
                            expectedListingId = requiredListingId,
                            expectedFavorited = favorited,
                            clientMutationSequence = sequence,
                        )
                }
            }
        }
}

private inline fun <T> runFavoritesCall(block: () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: FavoritesDataException) {
    DomainResult.Failure(exception.domainError)
}

private fun String.toRequiredFavoriteListingId(): String {
    val canonical = trim().lowercase()
    if (!canonical.isValidUuid()) {
        throw FavoritesDataException.Validation("error.favorites.listing_id_invalid")
    }
    return canonical
}
