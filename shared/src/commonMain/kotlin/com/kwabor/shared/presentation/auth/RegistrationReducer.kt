package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.auth.MAX_ONBOARDING_NAME_LENGTH
import com.kwabor.shared.i18n.KwaborStrings

class RegistrationReducer {
    fun reduce(state: RegistrationUiState, intent: RegistrationIntent, strings: KwaborStrings): RegistrationUiState =
        when (intent) {
            is RegistrationIntent.Field -> reduceField(state, intent)
            is RegistrationIntent.Navigation -> reduceNavigation(state, intent, strings)
        }

    private fun reduceField(state: RegistrationUiState, intent: RegistrationIntent.Field): RegistrationUiState =
        when (intent) {
            is RegistrationIntent.UpdateEmail -> state.copy(
                email = intent.email,
                errorMessage = null,
                noticeMessage = null,
            )
            is RegistrationIntent.UpdateFirstName -> state.copy(firstName = intent.firstName, errorMessage = null)
            is RegistrationIntent.UpdateLastName -> state.copy(lastName = intent.lastName, errorMessage = null)
            is RegistrationIntent.SelectCity -> state.selectCity(intent.cityId)
            is RegistrationIntent.SelectCurrency -> state.copy(
                preferredCurrency = intent.currency,
                errorMessage = null,
            )
            is RegistrationIntent.UpdateLegalAcceptance -> state.updateLegalAcceptance(intent.type, intent.accepted)
            is RegistrationIntent.UpdateObservabilityConsent -> state.copy(
                observabilityConsent = intent.consent,
                errorMessage = null,
            )
        }

    private fun reduceNavigation(
        state: RegistrationUiState,
        intent: RegistrationIntent.Navigation,
        strings: KwaborStrings,
    ): RegistrationUiState = when (intent) {
        RegistrationIntent.ContinueFromIdentity -> state.continueFromIdentity(strings)
        RegistrationIntent.ContinueFromCity -> state.continueFromCity(strings)
        RegistrationIntent.ContinueFromCurrency -> state.copy(step = RegistrationStep.Legal, errorMessage = null)
        RegistrationIntent.ContinueFromLegal -> state.continueFromLegal(strings)
        RegistrationIntent.FinishNotificationPriming -> state.copy(
            step = RegistrationStep.Completed,
            errorMessage = null,
        )
        RegistrationIntent.GoBack -> state.goBack()
    }
}

private fun RegistrationUiState.selectCity(cityId: String): RegistrationUiState =
    if (cities.any { city -> city.id == cityId }) {
        copy(selectedCityId = cityId, errorMessage = null)
    } else {
        this
    }

private fun RegistrationUiState.updateLegalAcceptance(
    type: LegalDocumentType,
    accepted: Boolean,
): RegistrationUiState = when (type) {
    LegalDocumentType.Terms -> copy(termsAccepted = accepted, errorMessage = null)
    LegalDocumentType.PrivacyPolicy -> copy(privacyAccepted = accepted, errorMessage = null)
    LegalDocumentType.UgcLicense -> copy(ugcAccepted = accepted, errorMessage = null)
}

private fun RegistrationUiState.continueFromIdentity(strings: KwaborStrings): RegistrationUiState = when {
    firstName.isBlank() || lastName.isBlank() -> copy(errorMessage = strings.registrationNameRequired)
    firstName.trim().length > MAX_ONBOARDING_NAME_LENGTH ||
        lastName.trim().length > MAX_ONBOARDING_NAME_LENGTH -> copy(
        errorMessage = strings.registrationNameTooLong,
    )
    else -> copy(step = RegistrationStep.City, errorMessage = null)
}

private fun RegistrationUiState.continueFromCity(strings: KwaborStrings): RegistrationUiState =
    if (selectedCityId == null) {
        copy(errorMessage = strings.registrationCityRequired)
    } else {
        copy(step = RegistrationStep.Currency, errorMessage = null)
    }

private fun RegistrationUiState.continueFromLegal(strings: KwaborStrings): RegistrationUiState =
    if (!termsAccepted || !privacyAccepted || !ugcAccepted) {
        copy(errorMessage = strings.registrationLegalRequired)
    } else {
        copy(step = RegistrationStep.Observability, errorMessage = null)
    }

private fun RegistrationUiState.goBack(): RegistrationUiState = copy(
    step = when (step) {
        RegistrationStep.Email -> RegistrationStep.Email
        RegistrationStep.Otp -> RegistrationStep.Email
        RegistrationStep.Password -> RegistrationStep.Otp
        RegistrationStep.Identity -> RegistrationStep.Password
        RegistrationStep.City -> RegistrationStep.Identity
        RegistrationStep.Currency -> RegistrationStep.City
        RegistrationStep.Legal -> RegistrationStep.Currency
        RegistrationStep.Observability -> RegistrationStep.Legal
        RegistrationStep.NotificationPriming -> RegistrationStep.NotificationPriming
        RegistrationStep.Completed -> RegistrationStep.Completed
    },
    errorMessage = null,
)
