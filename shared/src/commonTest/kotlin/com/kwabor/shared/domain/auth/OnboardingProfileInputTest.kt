package com.kwabor.shared.domain.auth

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import kotlin.test.Test
import kotlin.test.assertIs

class OnboardingProfileInputTest {
    @Test
    fun create_requiresLegalAcceptance() {
        val result = OnboardingProfileInput.create(
            firstName = "Awa",
            lastName = "Soglo",
            cityId = "cotonou",
            preferredLocale = AppLocale.French,
            preferredCurrency = KwaborCurrency.Xof,
            termsAccepted = true,
            privacyPolicyAccepted = true,
            ugcLicenseAccepted = false,
        )

        assertIs<DomainResult.Failure>(result)
    }

    @Test
    fun create_acceptsCompleteOnboardingProfile() {
        val result = OnboardingProfileInput.create(
            firstName = "Awa",
            lastName = "Soglo",
            cityId = "cotonou",
            preferredLocale = AppLocale.French,
            preferredCurrency = KwaborCurrency.Xof,
            termsAccepted = true,
            privacyPolicyAccepted = true,
            ugcLicenseAccepted = true,
        )

        assertIs<DomainResult.Success<OnboardingProfileInput>>(result)
    }
}
