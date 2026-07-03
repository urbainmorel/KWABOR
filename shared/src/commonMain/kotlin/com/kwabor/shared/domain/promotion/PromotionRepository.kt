package com.kwabor.shared.domain.promotion

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult

interface PromotionRepository {
    suspend fun listCampaigns(page: PageRequest = PageRequest()): DomainResult<PageResult<Campaign>>

    suspend fun createCampaign(campaign: Campaign): DomainResult<Campaign>

    suspend fun getPayment(paymentId: String): DomainResult<Payment>
}
