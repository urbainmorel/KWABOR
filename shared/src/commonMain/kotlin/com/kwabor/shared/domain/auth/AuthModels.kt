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

data class OnboardingProfileValues(
    val firstName: String,
    val lastName: String,
    val cityId: String?,
    val preferredLocale: AppLocale,
    val preferredCurrency: KwaborCurrency,
    val termsAccepted: Boolean,
    val privacyPolicyAccepted: Boolean,
    val ugcLicenseAccepted: Boolean,
)

class OnboardingProfileInput private constructor(
    private val values: OnboardingProfileValues,
) {
    val firstName: String get() = values.firstName
    val lastName: String get() = values.lastName
    val cityId: String? get() = values.cityId
    val preferredLocale: AppLocale get() = values.preferredLocale
    val preferredCurrency: KwaborCurrency get() = values.preferredCurrency
    val termsAccepted: Boolean get() = values.termsAccepted
    val privacyPolicyAccepted: Boolean get() = values.privacyPolicyAccepted
    val ugcLicenseAccepted: Boolean get() = values.ugcLicenseAccepted

    companion object {
        fun create(values: OnboardingProfileValues): DomainResult<OnboardingProfileInput> {
            if (values.firstName.isBlank() || values.lastName.isBlank()) {
                return DomainResult.Failure(DomainError.Validation("error.auth.name_required"))
            }

            if (!values.termsAccepted || !values.privacyPolicyAccepted || !values.ugcLicenseAccepted) {
                return DomainResult.Failure(DomainError.Validation("error.auth.legal_acceptance_required"))
            }

            return DomainResult.Success(OnboardingProfileInput(values))
        }
    }
}

data class EmailSignUpRequest(
    val email: String,
    val password: String,
    val otpCode: String,
    val onboarding: OnboardingProfileInput,
)

data class EmailOtpProfileRequest(
    val email: String,
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
