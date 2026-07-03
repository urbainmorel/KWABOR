package com.kwabor.shared.domain.promotion

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.MoneyXof

enum class CampaignStatus {
    Draft,
    PendingPayment,
    Active,
    Finished,
}

enum class PaymentStatus {
    InProgress,
    Succeeded,
    Failed,
}

data class Campaign(
    val id: String,
    val listingId: String,
    val ownerId: String,
    val cityIds: List<String>,
    val costXof: MoneyXof,
    val status: CampaignStatus,
    val startsAtEpochMilliseconds: Long,
    val endsAtEpochMilliseconds: Long,
)

class CampaignCreationRequest private constructor(
    val listingId: String,
    val cityIds: List<String>,
    val startsAtEpochMilliseconds: Long,
    val endsAtEpochMilliseconds: Long,
) {
    companion object {
        fun create(
            listingId: String,
            cityIds: List<String>,
            startsAtEpochMilliseconds: Long,
            endsAtEpochMilliseconds: Long,
        ): DomainResult<CampaignCreationRequest> {
            if (listingId.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.campaign.listing_required"))
            }

            if (cityIds.isEmpty()) {
                return DomainResult.Failure(DomainError.Validation("error.campaign.target_city_required"))
            }

            if (endsAtEpochMilliseconds <= startsAtEpochMilliseconds) {
                return DomainResult.Failure(DomainError.Validation("error.campaign.invalid_period"))
            }

            return DomainResult.Success(
                CampaignCreationRequest(
                    listingId = listingId,
                    cityIds = cityIds.distinct(),
                    startsAtEpochMilliseconds = startsAtEpochMilliseconds,
                    endsAtEpochMilliseconds = endsAtEpochMilliseconds,
                ),
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is CampaignCreationRequest &&
        listingId == other.listingId &&
        cityIds == other.cityIds &&
        startsAtEpochMilliseconds == other.startsAtEpochMilliseconds &&
        endsAtEpochMilliseconds == other.endsAtEpochMilliseconds

    override fun hashCode(): Int {
        var result = listingId.hashCode()
        result = 31 * result + cityIds.hashCode()
        result = 31 * result + startsAtEpochMilliseconds.hashCode()
        result = 31 * result + endsAtEpochMilliseconds.hashCode()
        return result
    }

    override fun toString(): String =
        "CampaignCreationRequest(listingId=$listingId, cityIds=$cityIds, startsAt=$startsAtEpochMilliseconds, " +
            "endsAt=$endsAtEpochMilliseconds)"
}

data class CampaignQuote(
    val request: CampaignCreationRequest,
    val estimatedCostXof: MoneyXof,
)

data class Payment(
    val id: String,
    val campaignId: String,
    val amountXof: MoneyXof,
    val status: PaymentStatus,
    val providerReference: String?,
)
