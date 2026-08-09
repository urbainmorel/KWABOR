package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.FavoritesStrings
import com.kwabor.shared.presentation.session.ViewerSessionScope

enum class FavoritesFilter {
    All,
    Places,
    Events,
    HotelsRestaurants,
}

fun FavoritesFilter.label(strings: FavoritesStrings): String = when (this) {
    FavoritesFilter.All -> strings.allFilter
    FavoritesFilter.Places -> strings.placesFilter
    FavoritesFilter.Events -> strings.eventsFilter
    FavoritesFilter.HotelsRestaurants -> strings.hotelsRestaurantsFilter
}

internal fun FavoritesFilter.toListingType(): ListingType? = when (this) {
    FavoritesFilter.All -> null
    FavoritesFilter.Places -> ListingType.Place
    FavoritesFilter.Events -> ListingType.Event
    FavoritesFilter.HotelsRestaurants -> ListingType.Establishment
}

data class FavoriteListingItem(
    val id: String,
    val type: ListingType,
    val listingClass: ListingClass,
    val title: String,
    val cityLabel: String,
    val coverImageUrl: String?,
    val coverImageAlt: String?,
    val price: MoneyXof?,
    val ratingLabel: String?,
    val likesCount: Int,
    val verified: Boolean,
    val liked: Boolean,
    val favoritedAtEpochMilliseconds: Long,
    val eventStartAtEpochMilliseconds: Long?,
    val eventEndAtEpochMilliseconds: Long?,
    val isEventEnded: Boolean,
)

data class FavoritesUiState(
    val selectedFilter: FavoritesFilter = FavoritesFilter.All,
    val items: List<FavoriteListingItem> = emptyList(),
    val nextCursor: String? = null,
    val isAccountReady: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
    val refreshMessage: String? = null,
    val appendErrorMessage: String? = null,
    val mutationMessage: String? = null,
    val removingListingIds: Set<String> = emptySet(),
    internal val mutationMessageListingId: String? = null,
    internal val mutationMessageIsOffline: Boolean = false,
    internal val contentIsOffline: Boolean = false,
    internal val viewerScope: ViewerSessionScope = ViewerSessionScope.InitialGuest,
) {
    val isEmpty: Boolean
        get() = isAccountReady &&
            !isLoading &&
            !isRefreshing &&
            errorMessage == null &&
            items.isEmpty() &&
            nextCursor == null

    val canLoadMore: Boolean
        get() = isAccountReady && nextCursor != null && !isLoading && !isRefreshing && !isAppending
}

sealed interface FavoritesIntent {
    sealed interface Lifecycle : FavoritesIntent

    sealed interface Page : FavoritesIntent

    sealed interface ListingAction : FavoritesIntent

    data object ScreenAppeared : Lifecycle

    data object ScreenDisappeared : Lifecycle

    data class ViewerContextChanged(val scope: ViewerSessionScope) : FavoritesIntent

    data class ExternalFavoriteStateChanged(
        val listingId: String,
        val favorited: Boolean,
        val clientMutationSequence: Long,
        val scope: ViewerSessionScope,
    ) : FavoritesIntent

    data class SelectFilter(val filter: FavoritesFilter) : Page

    data object Retry : Page

    data object Refresh : Page

    data object LoadNext : Page

    data class RemoveFavorite(val listingId: String) : ListingAction

    data class OpenListing(val listingId: String) : ListingAction
}

sealed interface FavoritesEffect {
    val scope: ViewerSessionScope

    data class OpenCatalogDetail(
        val listingId: String,
        override val scope: ViewerSessionScope,
    ) : FavoritesEffect

    data class FavoriteChanged(
        val listingId: String,
        val favorited: Boolean,
        val clientMutationSequence: Long,
        override val scope: ViewerSessionScope,
    ) : FavoritesEffect
}
