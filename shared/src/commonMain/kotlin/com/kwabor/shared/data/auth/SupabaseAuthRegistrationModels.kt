package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.CompleteOnboardingRequest
import com.kwabor.shared.domain.auth.LegalDocumentRevision
import com.kwabor.shared.domain.auth.LegalDocumentType
import com.kwabor.shared.domain.i18n.AppLocale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal fun CompleteOnboardingRequest.toRpcDto(): CompleteOnboardingRpcDto = CompleteOnboardingRpcDto(
    firstName = firstName,
    lastName = lastName,
    cityId = cityId,
    preferredLocale = preferredLocale.tag,
    preferredCurrency = preferredCurrency.name.uppercase(),
    termsDocumentId = termsDocumentId,
    privacyDocumentId = privacyDocumentId,
    ugcDocumentId = ugcDocumentId,
)

internal fun LegalDocumentRevisionDto.toDomain(): LegalDocumentRevision = LegalDocumentRevision(
    id = id,
    type = documentType.toDomainType(),
    version = version,
    locale = locale.toAppLocale(),
    url = contentUrl,
    effectiveAtEpochMilliseconds = effectiveAt.toEpochMilliseconds(),
)

private fun String.toDomainType(): LegalDocumentType = when (this) {
    "terms" -> LegalDocumentType.Terms
    "privacy_policy" -> LegalDocumentType.PrivacyPolicy
    "ugc_license" -> LegalDocumentType.UgcLicense
    else -> invalidDatabaseValue("legal_documents.document_type", this)
}

private fun String.toAppLocale(): AppLocale = AppLocale.entries.firstOrNull { locale -> locale.tag == this }
    ?: invalidDatabaseValue("legal_documents.locale", this)

@Serializable
internal data class LegalDocumentRevisionDto(
    @SerialName("id")
    val id: String,
    @SerialName("document_type")
    val documentType: String,
    @SerialName("version")
    val version: String,
    @SerialName("locale")
    val locale: String,
    @SerialName("content_url")
    val contentUrl: String,
    @SerialName("effective_at")
    val effectiveAt: String,
)

@Serializable
internal data class CompleteOnboardingRpcDto(
    @SerialName("p_first_name")
    val firstName: String,
    @SerialName("p_last_name")
    val lastName: String,
    @SerialName("p_city_id")
    val cityId: String,
    @SerialName("p_preferred_locale")
    val preferredLocale: String,
    @SerialName("p_preferred_currency")
    val preferredCurrency: String,
    @SerialName("p_terms_document_id")
    val termsDocumentId: String,
    @SerialName("p_privacy_document_id")
    val privacyDocumentId: String,
    @SerialName("p_ugc_document_id")
    val ugcDocumentId: String,
)
