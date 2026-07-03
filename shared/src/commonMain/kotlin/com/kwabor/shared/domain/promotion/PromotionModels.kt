package com.kwabor.shared.domain.promotion

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

data class Payment(
    val id: String,
    val campaignId: String,
    val amountXof: MoneyXof,
    val status: PaymentStatus,
    val providerReference: String?,
)
