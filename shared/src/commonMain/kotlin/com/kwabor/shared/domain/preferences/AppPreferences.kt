package com.kwabor.shared.domain.preferences

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency

const val MAX_EXPLORE_CITY_ID_LENGTH = 128

class AppPreferences private constructor(
    val exploreCityId: String?,
    val locale: AppLocale,
    val displayCurrency: KwaborCurrency,
) {
    companion object {
        val Default = AppPreferences(
            exploreCityId = null,
            locale = AppLocale.French,
            displayCurrency = KwaborCurrency.Xof,
        )

        fun create(
            exploreCityId: String?,
            locale: AppLocale,
            displayCurrency: KwaborCurrency,
        ): DomainResult<AppPreferences> {
            val normalizedCityId = exploreCityId?.trim()
            val validationError = when {
                normalizedCityId != null && !normalizedCityId.isValidExploreCityId() ->
                    DomainError.Validation("error.preferences.explore_city_invalid")
                locale != AppLocale.French ->
                    DomainError.Validation("error.preferences.locale_unavailable")
                else -> null
            }

            return if (validationError == null) {
                DomainResult.Success(
                    AppPreferences(
                        exploreCityId = normalizedCityId,
                        locale = locale,
                        displayCurrency = displayCurrency,
                    ),
                )
            } else {
                DomainResult.Failure(validationError)
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is AppPreferences &&
        exploreCityId == other.exploreCityId &&
        locale == other.locale &&
        displayCurrency == other.displayCurrency

    override fun hashCode(): Int {
        var result = exploreCityId?.hashCode() ?: 0
        result = 31 * result + locale.hashCode()
        result = 31 * result + displayCurrency.hashCode()
        return result
    }

    override fun toString(): String =
        "AppPreferences(exploreCityId=<redacted>, locale=$locale, displayCurrency=$displayCurrency)"
}

private fun String.isValidExploreCityId(): Boolean =
    isNotEmpty() && length <= MAX_EXPLORE_CITY_ID_LENGTH && EXPLORE_CITY_ID_PATTERN.matches(this)

private val EXPLORE_CITY_ID_PATTERN = Regex(pattern = "^[A-Za-z0-9][A-Za-z0-9_-]*$")
