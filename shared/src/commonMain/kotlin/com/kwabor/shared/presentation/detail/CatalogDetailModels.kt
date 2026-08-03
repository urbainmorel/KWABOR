package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.money.MoneyXof

sealed interface CatalogDetailIntent {
    data class Open(val listingId: String) : CatalogDetailIntent

    data object Retry : CatalogDetailIntent

    data object Close : CatalogDetailIntent

    data class SelectMedia(val index: Int) : CatalogDetailIntent

    data object ToggleDescription : CatalogDetailIntent
}

sealed interface CatalogDetailUiState {
    data object Closed : CatalogDetailUiState

    data class Loading(val listingId: String) : CatalogDetailUiState

    data class Content(
        val model: CatalogDetailUiModel,
        val selectedMediaIndex: Int,
        val isDescriptionExpanded: Boolean = false,
    ) : CatalogDetailUiState

    data class NotFound(
        val listingId: String,
        val message: String,
    ) : CatalogDetailUiState

    data class OfflineFailure(
        val listingId: String,
        val message: String,
    ) : CatalogDetailUiState

    data class Failure(
        val listingId: String,
        val message: String,
    ) : CatalogDetailUiState
}

data class CatalogDetailUiModel(
    val id: String,
    val title: String,
    val contextLabel: String,
    val description: String,
    val verified: Boolean,
    val isClaimable: Boolean,
    val media: List<CatalogDetailMediaUiModel>,
    val metrics: CatalogDetailMetricsUiModel,
    val price: CatalogDetailPriceUiModel,
    val openingStatusLabel: String?,
    val openingHours: List<CatalogDetailOpeningDayUiModel>,
    val amenities: List<String>,
    val location: CatalogDetailLocationUiModel,
    val tags: List<String>,
    val content: CatalogDetailContentUiModel,
)

data class CatalogDetailMediaUiModel(
    val url: String,
    val alt: String,
    val isCover: Boolean,
)

data class CatalogDetailMetricsUiModel(
    val ratingLabel: String?,
    val ratingCount: Int,
    val viewsCount: Int,
    val likesCount: Int,
)

data class CatalogDetailPriceUiModel(
    val amount: MoneyXof?,
    val prefixLabel: String?,
    val unitLabel: String?,
)

data class CatalogDetailOpeningDayUiModel(
    val dayLabel: String,
    val hoursLabel: String,
)

data class CatalogDetailLocationUiModel(
    val cityLabel: String,
    val districtLabel: String?,
    val addressLabel: String?,
    val latitude: Double?,
    val longitude: Double?,
)

data class CatalogDetailFactUiModel(
    val label: String,
    val value: String,
)

data class CatalogDetailPricedItemUiModel(
    val label: String,
    val price: MoneyXof,
)

sealed interface CatalogDetailContentUiModel {
    val heading: String

    data class Place(
        override val heading: String,
        val placeCategoryLabel: String,
        val entryFee: MoneyXof?,
        val feeNote: String?,
    ) : CatalogDetailContentUiModel

    data class Lodging(
        override val heading: String,
        val facts: List<CatalogDetailFactUiModel>,
        val roomTypes: List<CatalogDetailPricedItemUiModel>,
    ) : CatalogDetailContentUiModel

    data class Food(
        override val heading: String,
        val cuisines: List<String>,
        val meals: List<String>,
        val reservationLabel: String,
        val menuAvailable: Boolean,
    ) : CatalogDetailContentUiModel

    data class Nightlife(
        override val heading: String,
        val facts: List<CatalogDetailFactUiModel>,
    ) : CatalogDetailContentUiModel

    data class Guide(
        override val heading: String,
        val languages: List<String>,
        val zones: List<String>,
        val specialties: List<String>,
        val facts: List<CatalogDetailFactUiModel>,
        val indicativePrice: MoneyXof?,
    ) : CatalogDetailContentUiModel

    data class Event(
        override val heading: String,
        val startsAtLabel: String,
        val endsAtLabel: String?,
        val venueLabel: String,
        val organizerLabel: String,
        val capacityLabel: String?,
        val ticketing: CatalogDetailTicketingUiModel,
        val isEnded: Boolean,
    ) : CatalogDetailContentUiModel
}

sealed interface CatalogDetailTicketingUiModel {
    data class Free(val registrationAvailable: Boolean) : CatalogDetailTicketingUiModel

    data class Paid(val tiers: List<CatalogDetailPricedItemUiModel>) : CatalogDetailTicketingUiModel
}
