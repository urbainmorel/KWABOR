package com.kwabor.shared.domain.auth

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency

const val MAX_ONBOARDING_NAME_LENGTH = 80
const val AUTH_OTP_EXPIRED_ERROR_KEY = "error.auth.otp_expired"
const val AUTH_INVALID_CREDENTIALS_ERROR_KEY = "error.auth.invalid_credentials"
const val AUTH_EMAIL_NOT_CONFIRMED_ERROR_KEY = "error.auth.email_not_confirmed"
const val AUTH_RATE_LIMITED_ERROR_KEY = "error.auth.rate_limited"
const val AUTH_PASSWORD_TOO_WEAK_ERROR_KEY = "error.auth.password_too_weak"
const val AUTH_PASSWORD_SAME_ERROR_KEY = "error.auth.password_same"
const val AUTH_PASSWORD_RECOVERY_REQUIRED_ERROR_KEY = "error.auth.password_recovery_required"

enum class SocialAuthProvider {
    Google,
    Apple,
}

enum class AccountSetupStatus {
    OnboardingRequired,
    Complete,
}

enum class AuthSessionPurpose {
    Standard,
    PasswordRecovery,
}

data class AuthSession(
    val userId: String,
    val email: String?,
    val expiresAtEpochMilliseconds: Long,
    val accountSetupStatus: AccountSetupStatus,
    val purpose: AuthSessionPurpose = AuthSessionPurpose.Standard,
) {
    val requiresAccountSetup: Boolean
        get() = accountSetupStatus == AccountSetupStatus.OnboardingRequired

    val isAccountSetupComplete: Boolean
        get() = accountSetupStatus == AccountSetupStatus.Complete
}

enum class LegalDocumentType {
    Terms,
    PrivacyPolicy,
    UgcLicense,
}

data class LegalDocumentRevision(
    val id: String,
    val type: LegalDocumentType,
    val version: String,
    val locale: AppLocale,
    val url: String,
    val effectiveAtEpochMilliseconds: Long,
)

data class CompleteOnboardingValues(
    val firstName: String,
    val lastName: String,
    val cityId: String,
    val preferredLocale: AppLocale,
    val preferredCurrency: KwaborCurrency,
    val termsDocumentId: String,
    val privacyDocumentId: String,
    val ugcDocumentId: String,
)

class CompleteOnboardingRequest private constructor(
    private val values: CompleteOnboardingValues,
) {
    val firstName: String get() = values.firstName
    val lastName: String get() = values.lastName
    val cityId: String get() = values.cityId
    val preferredLocale: AppLocale get() = values.preferredLocale
    val preferredCurrency: KwaborCurrency get() = values.preferredCurrency
    val termsDocumentId: String get() = values.termsDocumentId
    val privacyDocumentId: String get() = values.privacyDocumentId
    val ugcDocumentId: String get() = values.ugcDocumentId

    companion object {
        fun create(values: CompleteOnboardingValues): DomainResult<CompleteOnboardingRequest> {
            val normalizedValues = values.normalized()
            val validationError = normalizedValues.validationError()
            return if (validationError == null) {
                DomainResult.Success(CompleteOnboardingRequest(normalizedValues))
            } else {
                DomainResult.Failure(validationError)
            }
        }
    }
}

private fun CompleteOnboardingValues.normalized(): CompleteOnboardingValues = copy(
    firstName = firstName.trim(),
    lastName = lastName.trim(),
    cityId = cityId.trim(),
    termsDocumentId = termsDocumentId.trim(),
    privacyDocumentId = privacyDocumentId.trim(),
    ugcDocumentId = ugcDocumentId.trim(),
)

private fun CompleteOnboardingValues.validationError(): DomainError.Validation? {
    val legalDocumentIds = listOf(termsDocumentId, privacyDocumentId, ugcDocumentId)
    return when {
        firstName.isBlank() || lastName.isBlank() -> DomainError.Validation("error.auth.name_required")
        firstName.length > MAX_ONBOARDING_NAME_LENGTH || lastName.length > MAX_ONBOARDING_NAME_LENGTH ->
            DomainError.Validation("error.auth.name_too_long")
        cityId.isBlank() -> DomainError.Validation("error.auth.city_required")
        legalDocumentIds.any(String::isBlank) || legalDocumentIds.toSet().size != legalDocumentIds.size ->
            DomainError.Validation("error.auth.legal_acceptance_required")
        else -> null
    }
}

class SocialSignInRequest(
    val provider: SocialAuthProvider,
    val idToken: String,
) {
    override fun toString(): String = "SocialSignInRequest(provider=$provider, idToken=<redacted>)"
}

class PromoterActivationRequest(
    val inviteToken: String,
    val password: String?,
    val socialSignInRequest: SocialSignInRequest?,
) {
    override fun toString(): String =
        "PromoterActivationRequest(inviteToken=<redacted>, password=<redacted>, socialSignInRequest=<redacted>)"
}
