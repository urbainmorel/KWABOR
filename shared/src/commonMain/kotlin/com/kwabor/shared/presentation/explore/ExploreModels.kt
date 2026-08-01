package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.KwaborStrings

private const val SAMPLE_MAIN_PRICE_XOF = 25_000L
private const val SAMPLE_SECONDARY_PRICE_XOF = 5_000L

enum class ExploreTab {
    Places,
    Events,
    HotelsRestaurants,
}

data class ExploreChip(
    val id: String,
    val label: String,
)

data class ExploreCityOption(
    val id: String,
    val label: String,
)

data class ExploreLoadRequest(
    val selectedTab: ExploreTab = ExploreTab.Places,
    val selectedChipId: String? = null,
    val selectedCityId: String? = null,
)

data class ExploreListingItem(
    val id: String,
    val title: String,
    val cityLabel: String,
    val coverImageUrl: String?,
    val price: MoneyXof?,
    val ratingLabel: String? = null,
    val likesCount: Int = 0,
    val sponsored: Boolean = false,
    val liked: Boolean = false,
    val favorited: Boolean = false,
)

enum class ExploreInteractionKind {
    Like,
    Favorite,
}

data class QueuedExploreInteraction(
    val listingId: String,
    val kind: ExploreInteractionKind,
    val selected: Boolean,
    val queuedAtEpochMilliseconds: Long,
)

data class PendingExploreAuthInteraction(
    val listingId: String,
    val kind: ExploreInteractionKind,
)

data class ExploreUiState(
    val cityLabel: String,
    val selectedCityId: String? = null,
    val availableCities: List<ExploreCityOption> = emptyList(),
    val selectedTab: ExploreTab,
    val selectedChipId: String?,
    val chips: List<ExploreChip>,
    val listings: List<ExploreListingItem>,
    val currency: KwaborCurrency = KwaborCurrency.Xof,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val isOffline: Boolean = false,
    val isLocalCacheUnavailable: Boolean = false,
    val nextCursor: String? = null,
    val feedSnapshot: ExploreFeedSnapshot? = null,
    val errorMessage: String? = null,
    val refreshMessage: String? = null,
    val appendErrorMessage: String? = null,
    val isCitySelectorOpen: Boolean = false,
    val isLocating: Boolean = false,
    val locationMessage: String? = null,
    val interactionMessage: String? = null,
    val pendingAuthInteraction: PendingExploreAuthInteraction? = null,
    val queuedInteractions: List<QueuedExploreInteraction> = emptyList(),
) {
    val hasError: Boolean
        get() = errorMessage != null

    val isEmpty: Boolean
        get() = !isLoading && !isRefreshing && !hasError && listings.isEmpty()

    val hasQueuedInteractions: Boolean
        get() = queuedInteractions.isNotEmpty()

    val canLoadMore: Boolean
        get() = nextCursor != null && !isLoading && !isRefreshing && !isAppending && !isOffline
}

fun ExploreTab.label(strings: KwaborStrings): String = when (this) {
    ExploreTab.Places -> strings.places
    ExploreTab.Events -> strings.events
    ExploreTab.HotelsRestaurants -> strings.hotelsRestaurants
}

fun ExploreTab.toListingType(): ListingType = when (this) {
    ExploreTab.Places -> ListingType.Place
    ExploreTab.Events -> ListingType.Event
    ExploreTab.HotelsRestaurants -> ListingType.Establishment
}

fun ExploreTab.defaultChips(strings: KwaborStrings): List<ExploreChip> = when (this) {
    ExploreTab.Places -> listOf(
        ExploreChip(id = "heritage-historique", label = strings.history),
        ExploreChip(id = "heritage-nature", label = strings.nature),
        ExploreChip(id = "commercial-marche", label = strings.markets),
    )
    ExploreTab.Events -> listOf(
        ExploreChip(id = "event-culture", label = strings.culture),
    )
    ExploreTab.HotelsRestaurants -> listOf(
        ExploreChip(id = "commercial-restaurant", label = strings.restaurants),
        ExploreChip(id = "commercial-hotel", label = strings.hotels),
        ExploreChip(id = "guide-touristique", label = strings.touristGuides),
    )
}

fun initialExploreUiState(strings: KwaborStrings, request: ExploreLoadRequest = ExploreLoadRequest()): ExploreUiState =
    ExploreUiState(
        cityLabel = strings.currentCity,
        selectedCityId = request.selectedCityId,
        selectedTab = request.selectedTab,
        selectedChipId = request.selectedChipId,
        chips = emptyList(),
        listings = emptyList(),
    )

fun loadingExploreUiState(strings: KwaborStrings, request: ExploreLoadRequest): ExploreUiState =
    initialExploreUiState(strings = strings, request = request).copy(isLoading = true)

fun sampleExploreUiState(strings: KwaborStrings): ExploreUiState = ExploreUiState(
    cityLabel = strings.currentCity,
    selectedTab = ExploreTab.Places,
    selectedChipId = "heritage-historique",
    chips = ExploreTab.Places.defaultChips(strings),
    listings = sampleExploreListings(),
)

private fun sampleExploreListings(): List<ExploreListingItem> = listOf(
    ExploreListingItem(
        id = "ganhihouse",
        title = "Maison Ganhi",
        cityLabel = "Cotonou",
        coverImageUrl = null,
        price = money(SAMPLE_MAIN_PRICE_XOF),
        ratingLabel = "4,7",
        sponsored = true,
        liked = true,
        favorited = true,
    ),
    ExploreListingItem(
        id = "ouidahmuseum",
        title = "Musée de Ouidah",
        cityLabel = "Ouidah",
        coverImageUrl = null,
        price = null,
        ratingLabel = "4,5",
    ),
    ExploreListingItem(
        id = "ganvie",
        title = "Ganvié",
        cityLabel = "Abomey-Calavi",
        coverImageUrl = null,
        price = money(SAMPLE_SECONDARY_PRICE_XOF),
        ratingLabel = "4,8",
        liked = true,
    ),
    ExploreListingItem(
        id = "fidjrosse",
        title = "Plage de Fidjrossè",
        cityLabel = "Cotonou",
        coverImageUrl = null,
        price = null,
        ratingLabel = "4,4",
        favorited = true,
    ),
)

private fun money(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("Invalid sample money")
}
