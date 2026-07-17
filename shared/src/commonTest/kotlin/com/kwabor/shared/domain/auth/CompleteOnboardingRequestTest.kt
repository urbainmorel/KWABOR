package com.kwabor.shared.domain.auth

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CompleteOnboardingRequestTest {
    @Test
    fun create_requiresCityAndThreeLegalDocuments() {
        val result = CompleteOnboardingRequest.create(
            validValues().copy(cityId = "", ugcDocumentId = ""),
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun create_trimsUserProvidedValues() {
        val result = CompleteOnboardingRequest.create(
            validValues().copy(firstName = " Awa ", lastName = " Soglo ", cityId = " cotonou "),
        )

        val request = assertIs<DomainResult.Success<CompleteOnboardingRequest>>(result).value
        assertEquals("Awa", request.firstName)
        assertEquals("Soglo", request.lastName)
        assertEquals("cotonou", request.cityId)
    }

    @Test
    fun create_rejectsNamesLongerThanServerLimit() {
        val result = CompleteOnboardingRequest.create(
            validValues().copy(firstName = "A".repeat(MAX_ONBOARDING_NAME_LENGTH + 1)),
        )

        assertIs<DomainResult.Failure>(result)
        assertEquals("error.auth.name_too_long", result.error.messageKey)
    }

    @Test
    fun create_requiresThreeDistinctLegalDocumentRevisions() {
        val values = validValues()
        val result = CompleteOnboardingRequest.create(
            values.copy(ugcDocumentId = values.termsDocumentId),
        )

        assertIs<DomainResult.Failure>(result)
        assertEquals("error.auth.legal_acceptance_required", result.error.messageKey)
    }
}

private fun validValues(): CompleteOnboardingValues = CompleteOnboardingValues(
    firstName = "Awa",
    lastName = "Soglo",
    cityId = "cotonou",
    preferredLocale = AppLocale.French,
    preferredCurrency = KwaborCurrency.Xof,
    termsDocumentId = "00000000-0000-4000-8000-000000000001",
    privacyDocumentId = "00000000-0000-4000-8000-000000000002",
    ugcDocumentId = "00000000-0000-4000-8000-000000000003",
)
