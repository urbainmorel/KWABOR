package com.kwabor.shared.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.preferences.AppPreferences
import com.kwabor.shared.domain.preferences.AppPreferencesRepository
import kotlinx.coroutines.flow.first

internal val EXPLORE_CITY_ID_KEY = stringPreferencesKey("explore_city_id")
internal val APP_LOCALE_KEY = stringPreferencesKey("app_locale")
internal val DISPLAY_CURRENCY_KEY = stringPreferencesKey("display_currency")

internal class DataStoreAppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository {
    override suspend fun get(): DomainResult<AppPreferences> = try {
        DomainResult.Success(dataStore.data.first().toAppPreferences())
    } catch (_: IOException) {
        DomainResult.Failure(DomainError.LocalStorageUnavailable(PREFERENCES_STORAGE_ERROR_KEY))
    }

    override suspend fun setExploreCity(cityId: String?): DomainResult<AppPreferences> {
        val validated = AppPreferences.create(
            exploreCityId = cityId,
            locale = AppLocale.French,
            displayCurrency = KwaborCurrency.Xof,
        )
        val normalizedCityId = when (validated) {
            is DomainResult.Success -> validated.value.exploreCityId
            is DomainResult.Failure -> return validated
        }

        return update { preferences ->
            if (normalizedCityId == null) {
                preferences.remove(EXPLORE_CITY_ID_KEY)
            } else {
                preferences[EXPLORE_CITY_ID_KEY] = normalizedCityId
            }
        }
    }

    override suspend fun setLocale(locale: AppLocale): DomainResult<AppPreferences> {
        val validated = AppPreferences.create(
            exploreCityId = null,
            locale = locale,
            displayCurrency = KwaborCurrency.Xof,
        )
        if (validated is DomainResult.Failure) {
            return validated
        }

        return update { preferences -> preferences[APP_LOCALE_KEY] = locale.toStorageValue() }
    }

    override suspend fun setDisplayCurrency(currency: KwaborCurrency): DomainResult<AppPreferences> =
        update { preferences -> preferences[DISPLAY_CURRENCY_KEY] = currency.toStorageValue() }

    private suspend fun update(transform: (MutablePreferences) -> Unit): DomainResult<AppPreferences> = try {
        val updatedPreferences = dataStore.edit(transform)
        DomainResult.Success(updatedPreferences.toAppPreferences())
    } catch (_: IOException) {
        DomainResult.Failure(DomainError.LocalStorageUnavailable(PREFERENCES_STORAGE_ERROR_KEY))
    }
}

internal fun Preferences.toAppPreferences(): AppPreferences {
    val storedCityId = this[EXPLORE_CITY_ID_KEY].toValidExploreCityIdOrNull()
    val locale = this[APP_LOCALE_KEY].toDeliveredLocaleOrDefault()
    val displayCurrency = this[DISPLAY_CURRENCY_KEY].toCurrencyOrDefault()
    return when (
        val result = AppPreferences.create(
            exploreCityId = storedCityId,
            locale = locale,
            displayCurrency = displayCurrency,
        )
    ) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> AppPreferences.Default
    }
}

private fun String?.toValidExploreCityIdOrNull(): String? {
    if (this == null) {
        return null
    }
    return when (
        val result = AppPreferences.create(
            exploreCityId = this,
            locale = AppLocale.French,
            displayCurrency = KwaborCurrency.Xof,
        )
    ) {
        is DomainResult.Success -> result.value.exploreCityId
        is DomainResult.Failure -> null
    }
}

private fun String?.toDeliveredLocaleOrDefault(): AppLocale = when (this) {
    AppLocale.French.tag -> AppLocale.French
    else -> AppLocale.French
}

private fun AppLocale.toStorageValue(): String = when (this) {
    AppLocale.French -> AppLocale.French.tag
    AppLocale.English -> AppLocale.English.tag
    AppLocale.Portuguese -> AppLocale.Portuguese.tag
    AppLocale.German -> AppLocale.German.tag
    AppLocale.Spanish -> AppLocale.Spanish.tag
    AppLocale.Italian -> AppLocale.Italian.tag
}

private fun String?.toCurrencyOrDefault(): KwaborCurrency = when (this) {
    STORED_CURRENCY_XOF -> KwaborCurrency.Xof
    STORED_CURRENCY_NGN -> KwaborCurrency.Ngn
    STORED_CURRENCY_USD -> KwaborCurrency.Usd
    STORED_CURRENCY_EUR -> KwaborCurrency.Eur
    else -> KwaborCurrency.Xof
}

private fun KwaborCurrency.toStorageValue(): String = when (this) {
    KwaborCurrency.Xof -> STORED_CURRENCY_XOF
    KwaborCurrency.Ngn -> STORED_CURRENCY_NGN
    KwaborCurrency.Usd -> STORED_CURRENCY_USD
    KwaborCurrency.Eur -> STORED_CURRENCY_EUR
}

private const val STORED_CURRENCY_XOF = "xof"
private const val STORED_CURRENCY_NGN = "ngn"
private const val STORED_CURRENCY_USD = "usd"
private const val STORED_CURRENCY_EUR = "eur"
private const val PREFERENCES_STORAGE_ERROR_KEY = "error.preferences.storage_unavailable"
