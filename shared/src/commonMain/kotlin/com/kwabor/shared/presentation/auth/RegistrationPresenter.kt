package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AccountSetupStatus
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.CompleteOnboardingValues
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.KwaborStrings

private const val OTP_RESEND_DELAY_MILLISECONDS = 30_000L
private const val MINIMUM_PASSWORD_LENGTH = 8

class RegistrationPresenter(
    private val authRepository: AuthRepository,
    private val catalogRepository: CatalogRepository,
    private val clockProvider: ClockProvider,
    val reducer: RegistrationReducer,
) {
    suspend fun requestOtp(state: RegistrationUiState, strings: KwaborStrings): RegistrationUiState {
        val now = clockProvider.nowEpochMilliseconds()
        if (!state.canResendOtp(now)) {
            return state.copy(errorMessage = strings.registrationOtpWait)
        }
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (val result = authRepository.requestEmailOtp(state.email)) {
            is DomainResult.Success -> loadingState.copy(
                step = RegistrationStep.Otp,
                method = RegistrationMethod.Email,
                resendAvailableAtEpochMilliseconds = now + OTP_RESEND_DELAY_MILLISECONDS,
                isLoading = false,
                noticeMessage = strings.authOtpSent,
            )
            is DomainResult.Failure -> loadingState.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
    }

    suspend fun verifyOtp(state: RegistrationUiState, otpCode: String, strings: KwaborStrings): RegistrationUiState {
        val loadingState = state.copy(isLoading = true, errorMessage = null, noticeMessage = null)
        return when (val result = authRepository.verifyEmailOtp(state.email, otpCode)) {
            is DomainResult.Success -> loadingState.copy(
                step = if (result.value.accountSetupStatus == AccountSetupStatus.Complete) {
                    RegistrationStep.Completed
                } else {
                    RegistrationStep.Password
                },
                currentSession = result.value,
                isLoading = false,
            )
            is DomainResult.Failure -> loadingState.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
    }

    suspend fun setInitialPassword(
        state: RegistrationUiState,
        password: String,
        strings: KwaborStrings,
    ): RegistrationUiState {
        if (password.length < MINIMUM_PASSWORD_LENGTH) {
            return state.copy(errorMessage = strings.registrationPasswordTooShort)
        }
        val loadingState = state.copy(isLoading = true, errorMessage = null)
        return when (val result = authRepository.setInitialPassword(password)) {
            is DomainResult.Success -> loadingState.copy(step = RegistrationStep.Profile, isLoading = false)
            is DomainResult.Failure -> loadingState.copy(
                isLoading = false,
                errorMessage = result.error.toAuthMessage(strings),
            )
        }
    }

    suspend fun loadRequirements(state: RegistrationUiState, strings: KwaborStrings): RegistrationUiState {
        val loadingState = state.copy(
            requirementsStatus = RegistrationRequirementsStatus.Loading,
            requirementsErrorMessage = null,
        )
        val cities = when (val result = catalogRepository.listCities()) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return loadingState.copy(
                requirementsStatus = RegistrationRequirementsStatus.Failed,
                requirementsErrorMessage = result.error.toAuthMessage(strings),
            )
        }
        val documents = when (val result = authRepository.listActiveLegalDocuments(AppLocale.French)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return loadingState.copy(
                cities = cities,
                requirementsStatus = RegistrationRequirementsStatus.Failed,
                requirementsErrorMessage = result.error.toAuthMessage(strings),
            )
        }
        return loadingState.withLoadedRequirements(
            cities = cities,
            documents = documents,
            strings = strings,
        )
    }

    suspend fun completeOnboarding(state: RegistrationUiState, strings: KwaborStrings): RegistrationUiState {
        return when (val request = state.toCompleteOnboardingRequest()) {
            is DomainResult.Failure -> state.copy(errorMessage = request.error.toAuthMessage(strings))
            is DomainResult.Success -> {
                val loadingState = state.copy(isLoading = true, errorMessage = null)
                when (val result = authRepository.completeOnboarding(request.value)) {
                    is DomainResult.Success -> loadingState.copy(
                        step = RegistrationStep.Completed,
                        currentSession = result.value,
                        isLoading = false,
                        noticeMessage = strings.registrationComplete,
                    )
                    is DomainResult.Failure -> loadingState.copy(
                        isLoading = false,
                        errorMessage = result.error.toAuthMessage(strings),
                    )
                }
            }
        }
    }
}

private fun RegistrationUiState.withLoadedRequirements(
    cities: List<City>,
    documents: List<LegalDocumentRevision>,
    strings: KwaborStrings,
): RegistrationUiState {
    val terms = documents.singleRevisionOfType(LegalDocumentType.Terms)
    val privacy = documents.singleRevisionOfType(LegalDocumentType.PrivacyPolicy)
    val ugc = documents.singleRevisionOfType(LegalDocumentType.UgcLicense)
    if (terms == null || privacy == null || ugc == null) {
        return copy(
            cities = cities,
            requirementsStatus = RegistrationRequirementsStatus.Failed,
            requirementsErrorMessage = strings.registrationLegalUnavailable,
        )
    }
    val suggestedCityId = startContext.suggestedCityId
        ?.takeIf { cityId -> cities.any { city -> city.id == cityId } }
    return copy(
        cities = cities,
        selectedCityId = selectedCityId ?: suggestedCityId,
        termsDocument = terms,
        privacyDocument = privacy,
        ugcDocument = ugc,
        requirementsStatus = RegistrationRequirementsStatus.Ready,
        requirementsErrorMessage = null,
    )
}

private fun List<LegalDocumentRevision>.singleRevisionOfType(type: LegalDocumentType): LegalDocumentRevision? =
    filter { revision -> revision.type == type }.singleOrNull()

private fun RegistrationUiState.toCompleteOnboardingRequest(): DomainResult<CompleteOnboardingRequest> {
    if (!termsAccepted || !privacyAccepted || !ugcAccepted) {
        return DomainResult.Failure(
            com.kwabor.shared.domain.core.DomainError.Validation("error.auth.legal_acceptance_required"),
        )
    }
    return CompleteOnboardingRequest.create(
        CompleteOnboardingValues(
            firstName = firstName,
            lastName = lastName,
            cityId = selectedCityId.orEmpty(),
            preferredLocale = AppLocale.French,
            preferredCurrency = preferredCurrency,
            termsDocumentId = termsDocument?.id.orEmpty(),
            privacyDocumentId = privacyDocument?.id.orEmpty(),
            ugcDocumentId = ugcDocument?.id.orEmpty(),
        ),
    )
}
