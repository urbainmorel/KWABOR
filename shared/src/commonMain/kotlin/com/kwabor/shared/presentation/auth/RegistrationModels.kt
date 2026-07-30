package com.kwabor.shared.presentation.auth

import com.kwabor.shared.domain.auth.AuthSession
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.money.KwaborCurrency

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

    sealed interface Navigation : RegistrationIntent

    data object CompleteProfile : Navigation

    data object GoBack : Navigation
}

enum class RegistrationStep {
    Email,
    Otp,
    Password,
    Profile,
    Completed,
}

enum class RegistrationMethod {
    Email,
    Federated,
}

enum class RegistrationRequirementsStatus {
    Idle,
    Loading,
    Ready,
    Failed,
}

data class RegistrationStartContext(
    val suggestedCityId: String? = null,
)

data class RegistrationProgress(
    val current: Int,
    val total: Int,
)

data class RegistrationUiState(
    val step: RegistrationStep = RegistrationStep.Email,
    val method: RegistrationMethod? = null,
    val startContext: RegistrationStartContext = RegistrationStartContext(),
    val requirementsStatus: RegistrationRequirementsStatus = RegistrationRequirementsStatus.Idle,
    val requirementsErrorMessage: String? = null,
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
    val currentSession: AuthSession? = null,
    val resendAvailableAtEpochMilliseconds: Long? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
) {
    val progress: RegistrationProgress?
        get() = when (step) {
            RegistrationStep.Email -> null
            RegistrationStep.Otp -> RegistrationProgress(current = 2, total = EMAIL_REGISTRATION_STEP_COUNT)
            RegistrationStep.Password -> RegistrationProgress(current = 3, total = EMAIL_REGISTRATION_STEP_COUNT)
            RegistrationStep.Profile -> when (method) {
                RegistrationMethod.Email -> RegistrationProgress(
                    current = EMAIL_REGISTRATION_STEP_COUNT,
                    total = EMAIL_REGISTRATION_STEP_COUNT,
                )
                RegistrationMethod.Federated -> RegistrationProgress(current = 1, total = 1)
                null -> null
            }
            RegistrationStep.Completed -> null
        }

    val requirementsReady: Boolean
        get() = requirementsStatus == RegistrationRequirementsStatus.Ready &&
            cities.isNotEmpty() &&
            termsDocument != null &&
            privacyDocument != null &&
            ugcDocument != null

    fun canResendOtp(nowEpochMilliseconds: Long): Boolean =
        resendAvailableAtEpochMilliseconds?.let { availableAt -> nowEpochMilliseconds >= availableAt } ?: true
}

fun initialRegistrationUiState(context: RegistrationStartContext = RegistrationStartContext()): RegistrationUiState =
    RegistrationUiState(startContext = context)

fun RegistrationUiState.mergeRequirementsFrom(source: RegistrationUiState): RegistrationUiState = copy(
    requirementsStatus = source.requirementsStatus,
    requirementsErrorMessage = source.requirementsErrorMessage,
    cities = source.cities,
    selectedCityId = selectedCityId ?: source.selectedCityId,
    termsDocument = source.termsDocument,
    privacyDocument = source.privacyDocument,
    ugcDocument = source.ugcDocument,
)

private const val EMAIL_REGISTRATION_STEP_COUNT = 4
