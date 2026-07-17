package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.observability.ObservabilityConsent

sealed interface RegistrationIntent {
    sealed interface Field : RegistrationIntent

    data class UpdateEmail(val email: String) : Field

    data class UpdateFirstName(val firstName: String) : Field

    data class UpdateLastName(val lastName: String) : Field

    data class SelectCity(val cityId: String) : Field

    data class SelectCurrency(val currency: KwaborCurrency) : Field

    data class UpdateLegalAcceptance(
        val type: LegalDocumentType,
        val accepted: Boolean,
    ) : Field

    data class UpdateObservabilityConsent(val consent: ObservabilityConsent) : Field

    sealed interface Navigation : RegistrationIntent

    data object ContinueFromIdentity : Navigation

    data object ContinueFromCity : Navigation

    data object ContinueFromCurrency : Navigation

    data object ContinueFromLegal : Navigation

    data object FinishNotificationPriming : Navigation

    data object GoBack : Navigation
}

enum class RegistrationStep {
    Email,
    Otp,
    Password,
    Identity,
    City,
    Currency,
    Legal,
    Observability,
    NotificationPriming,
    Completed,
}

data class RegistrationUiState(
    val step: RegistrationStep = RegistrationStep.Email,
    val email: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val cities: List<City> = emptyList(),
    val selectedCityId: String? = null,
    val preferredCurrency: KwaborCurrency = KwaborCurrency.Xof,
    val termsDocument: LegalDocumentRevision? = null,
    val privacyDocument: LegalDocumentRevision? = null,
    val ugcDocument: LegalDocumentRevision? = null,
    val termsAccepted: Boolean = false,
    val privacyAccepted: Boolean = false,
    val ugcAccepted: Boolean = false,
    val observabilityConsent: ObservabilityConsent = ObservabilityConsent(),
    val currentSession: AuthSession? = null,
    val resendAvailableAtEpochMilliseconds: Long? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
) {
    fun canResendOtp(nowEpochMilliseconds: Long): Boolean =
        resendAvailableAtEpochMilliseconds?.let { availableAt -> nowEpochMilliseconds >= availableAt } ?: true
}

fun initialRegistrationUiState(): RegistrationUiState = RegistrationUiState()
