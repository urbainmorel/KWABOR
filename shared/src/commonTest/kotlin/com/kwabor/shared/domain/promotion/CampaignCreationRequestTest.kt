package com.kwabor.shared.domain.promotion

import com.kwabor.shared.domain.core.DomainResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CampaignCreationRequestTest {
    @Test
    fun create_rejectsEmptyTargetCities() {
        val result = CampaignCreationRequest.create(
            listingId = "listing-1",
            cityIds = emptyList(),
            startsAtEpochMilliseconds = 1_000,
            endsAtEpochMilliseconds = 2_000,
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun create_rejectsInvalidPeriod() {
        val result = CampaignCreationRequest.create(
            listingId = "listing-1",
            cityIds = listOf("cotonou"),
            startsAtEpochMilliseconds = 2_000,
            endsAtEpochMilliseconds = 1_000,
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun create_deduplicatesTargetCities() {
        val result = CampaignCreationRequest.create(
            listingId = "listing-1",
            cityIds = listOf("cotonou", "cotonou", "ouidah"),
            startsAtEpochMilliseconds = 1_000,
            endsAtEpochMilliseconds = 2_000,
        )

        val success = assertIs<DomainResult.Success<CampaignCreationRequest>>(result)
        assertEquals(listOf("cotonou", "ouidah"), success.value.cityIds)
    }
}
