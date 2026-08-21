package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.money.MoneyXof
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface CatalogDetailIntent {
    data class Open(
        val listingId: String,
        val openRequestId: CatalogDetailOpenRequestId? = null,
    ) : CatalogDetailIntent

    data object Retry : CatalogDetailIntent

    data object Close : CatalogDetailIntent

    data class SelectMedia(val index: Int) : CatalogDetailIntent

    data object ToggleDescription : CatalogDetailIntent
}

/**
 * Correlates one logical request to open the catalog detail sheet.
 *
 * Runtime-generated requests use even values while caller-correlated requests use odd values. This
 * prevents a notification ticket from matching content left behind by an unrelated open request.
 */
class CatalogDetailOpenRequestId private constructor(
    val value: Long,
) {
    init {
        require(value > 0L) { "Catalog detail open request id must be positive." }
    }

    override fun equals(other: Any?): Boolean = other is CatalogDetailOpenRequestId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "CatalogDetailOpenRequestId(value=$value)"

    companion object {
        fun correlated(sequence: Long): CatalogDetailOpenRequestId {
            require(sequence in 1L..MAX_SEQUENCE) { "Catalog detail correlation sequence is out of range." }
            return CatalogDetailOpenRequestId(sequence * 2L - 1L)
        }

        internal fun generated(sequence: Long): CatalogDetailOpenRequestId {
            require(sequence in 1L..MAX_SEQUENCE) { "Catalog detail generated sequence is out of range." }
            return CatalogDetailOpenRequestId(sequence * 2L)
        }

        internal const val MAX_SEQUENCE: Long = Long.MAX_VALUE / 2L
    }
}

internal class CatalogDetailOpenRequestIdGenerator(
    initialSequence: Long = 0L,
) {
    private val sequence = MutableStateFlow(initialSequence)

    init {
        require(initialSequence in 0L..CatalogDetailOpenRequestId.MAX_SEQUENCE) {
            "Catalog detail generated sequence is out of range."
        }
    }

    fun next(): CatalogDetailOpenRequestId? {
        while (true) {
            val current = sequence.value
            if (current == CatalogDetailOpenRequestId.MAX_SEQUENCE) return null
            val next = current + 1L
            if (sequence.compareAndSet(current, next)) return CatalogDetailOpenRequestId.generated(next)
        }
    }
}

sealed interface CatalogDetailUiState {
    data object Closed : CatalogDetailUiState

    data class Loading(
        val listingId: String,
        val openRequestId: CatalogDetailOpenRequestId,
    ) : CatalogDetailUiState

    data class Content(
        val model: CatalogDetailUiModel,
        val openRequestId: CatalogDetailOpenRequestId,
        val selectedMediaIndex: Int,
        val isDescriptionExpanded: Boolean = false,
    ) : CatalogDetailUiState

    data class NotFound(
        val listingId: String,
        val openRequestId: CatalogDetailOpenRequestId,
        val message: String,
    ) : CatalogDetailUiState

    data class OfflineFailure(
        val listingId: String,
        val openRequestId: CatalogDetailOpenRequestId,
        val message: String,
    ) : CatalogDetailUiState

    data class Failure(
        val listingId: String,
        val openRequestId: CatalogDetailOpenRequestId,
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
    val directions: CatalogDetailDirectionsUiModel?,
    val contact: CatalogDetailContactUiModel?,
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

data class CatalogDetailDirectionsUiModel(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

data class CatalogDetailContactUiModel(
    val phoneNumber: String?,
    val whatsappNumber: String?,
    val websiteUrl: String?,
    val emailAddress: String?,
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
        val menuUrl: String?,
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
    data class Free(val externalUrl: String?) : CatalogDetailTicketingUiModel

    data class Paid(
        val externalUrl: String,
        val tiers: List<CatalogDetailPricedItemUiModel>,
    ) : CatalogDetailTicketingUiModel
}
