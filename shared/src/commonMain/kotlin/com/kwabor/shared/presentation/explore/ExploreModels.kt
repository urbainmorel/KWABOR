package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.KwaborStrings

enum class ExploreTab {
    Places,
    Events,
    HotelsRestaurants,
}

data class ExploreChip(
    val id: String,
    val label: String,
)

data class ExploreLoadRequest(
    val selectedTab: ExploreTab = ExploreTab.Places,
    val selectedChipId: String? = null,
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
    val selectedTab: ExploreTab,
    val selectedChipId: String?,
    val chips: List<ExploreChip>,
    val listings: List<ExploreListingItem>,
    val currency: KwaborCurrency = KwaborCurrency.Xof,
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
    val interactionMessage: String? = null,
    val pendingAuthInteraction: PendingExploreAuthInteraction? = null,
    val queuedInteractions: List<QueuedExploreInteraction> = emptyList(),
) {
    val hasError: Boolean
        get() = errorMessage != null

    val isEmpty: Boolean
        get() = !isLoading && !hasError && listings.isEmpty()

    val hasQueuedInteractions: Boolean
        get() = queuedInteractions.isNotEmpty()
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
        ExploreChip(id = "beaches", label = strings.beaches),
        ExploreChip(id = "history", label = strings.history),
        ExploreChip(id = "markets", label = strings.markets),
        ExploreChip(id = "nature", label = strings.nature),
    )
    ExploreTab.Events -> listOf(
        ExploreChip(id = "concerts", label = strings.concerts),
        ExploreChip(id = "festivals", label = strings.festivals),
        ExploreChip(id = "conferences", label = strings.conferences),
        ExploreChip(id = "hikes", label = strings.hikes),
    )
    ExploreTab.HotelsRestaurants -> listOf(
        ExploreChip(id = "hotels", label = strings.hotels),
        ExploreChip(id = "restaurants", label = strings.restaurants),
        ExploreChip(id = "maquis", label = strings.maquis),
        ExploreChip(id = "bars", label = strings.bars),
        ExploreChip(id = "cafes", label = strings.cafes),
    )
}

fun initialExploreUiState(strings: KwaborStrings, request: ExploreLoadRequest = ExploreLoadRequest()): ExploreUiState =
    ExploreUiState(
        cityLabel = strings.currentCity,
        selectedTab = request.selectedTab,
        selectedChipId = request.selectedChipId,
        chips = request.selectedTab.defaultChips(strings),
        listings = emptyList(),
    )

fun loadingExploreUiState(strings: KwaborStrings, request: ExploreLoadRequest): ExploreUiState =
    initialExploreUiState(strings = strings, request = request).copy(isLoading = true)

fun sampleExploreUiState(strings: KwaborStrings): ExploreUiState = ExploreUiState(
    cityLabel = strings.currentCity,
    selectedTab = ExploreTab.Places,
    selectedChipId = "history",
    chips = ExploreTab.Places.defaultChips(strings),
    listings = listOf(
        ExploreListingItem(
            id = "ganhihouse",
            title = "Maison Ganhi",
            cityLabel = "Cotonou",
            coverImageUrl = null,
            price = money(25_000),
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
            price = money(5_000),
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
    ),
)

private fun money(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("Invalid sample money")
}
