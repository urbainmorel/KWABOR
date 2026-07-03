package com.kwabor.shared.domain.auth

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency

enum class SocialAuthProvider {
    Google,
    Apple,
}

data class AuthSession(
    val userId: String,
    val email: String?,
    val expiresAtEpochMilliseconds: Long,
)

class OnboardingProfileInput private constructor(
    val firstName: String,
    val lastName: String,
    val cityId: String?,
    val preferredLocale: AppLocale,
    val preferredCurrency: KwaborCurrency,
    val termsAccepted: Boolean,
    val privacyPolicyAccepted: Boolean,
    val ugcLicenseAccepted: Boolean,
) {
    companion object {
        fun create(
            firstName: String,
            lastName: String,
            cityId: String?,
            preferredLocale: AppLocale,
            preferredCurrency: KwaborCurrency,
            termsAccepted: Boolean,
            privacyPolicyAccepted: Boolean,
            ugcLicenseAccepted: Boolean,
        ): DomainResult<OnboardingProfileInput> {
            if (firstName.isBlank() || lastName.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.auth.name_required"))
            }

            if (!termsAccepted || !privacyPolicyAccepted || !ugcLicenseAccepted) {
                return DomainResult.Failure(DomainError.Validation("error.auth.legal_acceptance_required"))
            }

            return DomainResult.Success(
                OnboardingProfileInput(
                    firstName = firstName,
                    lastName = lastName,
                    cityId = cityId,
                    preferredLocale = preferredLocale,
                    preferredCurrency = preferredCurrency,
                    termsAccepted = termsAccepted,
                    privacyPolicyAccepted = privacyPolicyAccepted,
                    ugcLicenseAccepted = ugcLicenseAccepted,
                ),
            )
        }
    }
}

data class EmailSignUpRequest(
    val email: String,
    val password: String,
    val otpCode: String,
    val onboarding: OnboardingProfileInput,
)

data class SocialSignInRequest(
    val provider: SocialAuthProvider,
    val idToken: String,
    val onboarding: OnboardingProfileInput?,
)

data class PromoterActivationRequest(
    val inviteToken: String,
    val password: String?,
    val socialSignInRequest: SocialSignInRequest?,
)
