package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.favorites.FavoriteListing
import com.kwabor.shared.domain.favorites.FavoriteListingPage
import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.i18n.FavoritesStrings
import kotlin.math.roundToInt

private const val FAVORITES_PAGE_SIZE = 20
private const val RATING_DECIMAL_SCALE = 10
private const val RATING_DECIMAL_DIVISOR = 10.0

class FavoritesPresenter(
    private val repository: FavoritesRepository,
) {
    suspend fun load(filter: FavoritesFilter, strings: FavoritesStrings): FavoritesUiState =
        when (val result = requestPage(filter = filter, cursor = null)) {
            is DomainResult.Success -> result.value.toLoadedState(filter = filter, strings = strings)
            is DomainResult.Failure -> FavoritesUiState(
                selectedFilter = filter,
                isAccountReady = true,
                isOffline = result.error is DomainError.NetworkUnavailable,
                contentIsOffline = result.error is DomainError.NetworkUnavailable,
                errorMessage = strings.loadFailed,
            )
        }

    suspend fun refresh(state: FavoritesUiState, strings: FavoritesStrings): FavoritesUiState =
        when (val result = requestPage(filter = state.selectedFilter, cursor = null)) {
            is DomainResult.Success -> result.value.toLoadedState(
                filter = state.selectedFilter,
                strings = strings,
            )
            is DomainResult.Failure -> if (state.items.isEmpty()) {
                state.copy(
                    isLoading = false,
                    isRefreshing = false,
                    isAppending = false,
                    isOffline = result.error is DomainError.NetworkUnavailable || state.mutationMessageIsOffline,
                    contentIsOffline = result.error is DomainError.NetworkUnavailable,
                    errorMessage = strings.loadFailed,
                    refreshMessage = null,
                )
            } else {
                state.copy(
                    isLoading = false,
                    isRefreshing = false,
                    isAppending = false,
                    isOffline = result.error is DomainError.NetworkUnavailable || state.mutationMessageIsOffline,
                    contentIsOffline = result.error is DomainError.NetworkUnavailable,
                    errorMessage = null,
                    refreshMessage = strings.refreshFailed,
                )
            }
        }

    suspend fun append(state: FavoritesUiState, strings: FavoritesStrings): FavoritesUiState {
        val cursor = state.nextCursor ?: return state.copy(isAppending = false)
        return when (val result = requestPage(filter = state.selectedFilter, cursor = cursor)) {
            is DomainResult.Success -> state.appendPage(
                page = result.value,
                requestedCursor = cursor,
                strings = strings,
            )
            is DomainResult.Failure -> state.copy(
                isAppending = false,
                isOffline = result.error is DomainError.NetworkUnavailable || state.mutationMessageIsOffline,
                contentIsOffline = result.error is DomainError.NetworkUnavailable,
                appendErrorMessage = strings.loadMoreFailed,
            )
        }
    }

    suspend fun removeFavorite(listingId: String, strings: FavoritesStrings): FavoriteRemovalOutcome =
        when (val result = repository.setFavorite(listingId = listingId, favorited = false)) {
            is DomainResult.Success -> if (
                result.value.listingId == listingId &&
                !result.value.favorited &&
                result.value.favoritedAtEpochMilliseconds == null
            ) {
                FavoriteRemovalOutcome.Removed(listingId)
            } else {
                FavoriteRemovalOutcome.Failed(
                    message = strings.removeFailed,
                    isOffline = false,
                )
            }
            is DomainResult.Failure -> FavoriteRemovalOutcome.Failed(
                message = strings.removeFailed,
                isOffline = result.error is DomainError.NetworkUnavailable,
            )
        }

    private suspend fun requestPage(filter: FavoritesFilter, cursor: String?): DomainResult<FavoriteListingPage> =
        repository.listFavorites(
            filter = filter.toListingType(),
            page = ListingPageRequest(cursor = cursor, limit = FAVORITES_PAGE_SIZE),
        )
}

sealed interface FavoriteRemovalOutcome {
    data class Removed(val listingId: String) : FavoriteRemovalOutcome

    data class Failed(
        val message: String,
        val isOffline: Boolean,
    ) : FavoriteRemovalOutcome
}

private fun FavoriteListingPage.toLoadedState(filter: FavoritesFilter, strings: FavoritesStrings): FavoritesUiState {
    if (!isValidFor(filter)) {
        return FavoritesUiState(
            selectedFilter = filter,
            isAccountReady = true,
            errorMessage = strings.loadFailed,
        )
    }
    return FavoritesUiState(
        selectedFilter = filter,
        items = items.map(FavoriteListing::toUiModel),
        nextCursor = nextCursor,
        isAccountReady = true,
    )
}

private fun FavoritesUiState.appendPage(
    page: FavoriteListingPage,
    requestedCursor: String,
    strings: FavoritesStrings,
): FavoritesUiState {
    val visibleIds = items.mapTo(mutableSetOf(), FavoriteListingItem::id)
    if (
        !page.isValidFor(selectedFilter) ||
        page.nextCursor == requestedCursor ||
        page.items.any { listing -> listing.id in visibleIds }
    ) {
        return copy(
            isAppending = false,
            isOffline = contentIsOffline || mutationMessageIsOffline,
            appendErrorMessage = strings.loadMoreFailed,
        )
    }
    return copy(
        items = items + page.items.map(FavoriteListing::toUiModel),
        nextCursor = page.nextCursor,
        isAppending = false,
        isOffline = mutationMessageIsOffline,
        contentIsOffline = false,
        appendErrorMessage = null,
    )
}

private fun FavoriteListingPage.isValidFor(filter: FavoritesFilter): Boolean {
    val expectedType = filter.toListingType()
    return (nextCursor == null || nextCursor.isNotBlank()) &&
        items.distinctBy(FavoriteListing::id).size == items.size &&
        (expectedType == null || items.all { listing -> listing.type == expectedType })
}

private fun FavoriteListing.toUiModel(): FavoriteListingItem = FavoriteListingItem(
    id = id,
    type = type,
    listingClass = listingClass,
    title = name,
    cityLabel = cityName,
    coverImageUrl = coverImageUrl,
    coverImageAlt = coverImageAlt,
    price = priceFromXof,
    ratingLabel = ratingAverage?.toRatingLabel(),
    likesCount = likesCount,
    verified = verified,
    liked = likedByViewer,
    favoritedAtEpochMilliseconds = favoritedAtEpochMilliseconds,
    eventStartAtEpochMilliseconds = eventStartAtEpochMilliseconds,
    eventEndAtEpochMilliseconds = eventEndAtEpochMilliseconds,
    isEventEnded = isEventEnded,
)

private fun Double.toRatingLabel(): String {
    val rounded = (this * RATING_DECIMAL_SCALE).roundToInt() / RATING_DECIMAL_DIVISOR
    return rounded.toString().replace(oldChar = '.', newChar = ',')
}
