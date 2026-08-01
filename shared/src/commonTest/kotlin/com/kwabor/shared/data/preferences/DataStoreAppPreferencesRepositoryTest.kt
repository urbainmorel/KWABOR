package com.kwabor.shared.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.preferences.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DataStoreAppPreferencesRepositoryTest {
    @Test
    fun get_mapsStoredPreferencesExplicitly() = runTest {
        val dataStore = FakePreferencesDataStore(
            mutablePreferencesOf(
                EXPLORE_CITY_ID_KEY to "ouidah",
                APP_LOCALE_KEY to "fr",
                DISPLAY_CURRENCY_KEY to "eur",
            ),
        )
        val repository = DataStoreAppPreferencesRepository(dataStore)

        val result = repository.get()

        val preferences = assertIs<DomainResult.Success<AppPreferences>>(result).value
        assertEquals("ouidah", preferences.exploreCityId)
        assertEquals(AppLocale.French, preferences.locale)
        assertEquals(KwaborCurrency.Eur, preferences.displayCurrency)
    }

    @Test
    fun get_fallsBackSafelyForUnknownOrInvalidValues() = runTest {
        val dataStore = FakePreferencesDataStore(
            mutablePreferencesOf(
                EXPLORE_CITY_ID_KEY to "../invalid",
                APP_LOCALE_KEY to "unknown",
                DISPLAY_CURRENCY_KEY to "btc",
            ),
        )
        val repository = DataStoreAppPreferencesRepository(dataStore)

        val result = repository.get()

        val preferences = assertIs<DomainResult.Success<AppPreferences>>(result).value
        assertNull(preferences.exploreCityId)
        assertEquals(AppLocale.French, preferences.locale)
        assertEquals(KwaborCurrency.Xof, preferences.displayCurrency)
    }

    @Test
    fun settersUpdateOnlyTheirOwnPreference() = runTest {
        val dataStore = FakePreferencesDataStore(mutablePreferencesOf())
        val repository = DataStoreAppPreferencesRepository(dataStore)

        assertIs<DomainResult.Success<AppPreferences>>(repository.setExploreCity("  abomey  "))
        assertIs<DomainResult.Success<AppPreferences>>(repository.setDisplayCurrency(KwaborCurrency.Usd))

        val preferences = assertIs<DomainResult.Success<AppPreferences>>(repository.get()).value
        assertEquals("abomey", preferences.exploreCityId)
        assertEquals(AppLocale.French, preferences.locale)
        assertEquals(KwaborCurrency.Usd, preferences.displayCurrency)
    }

    @Test
    fun setExploreCityRejectsInvalidInputWithoutMutatingStoredValue() = runTest {
        val dataStore = FakePreferencesDataStore(
            mutablePreferencesOf(EXPLORE_CITY_ID_KEY to "cotonou"),
        )
        val repository = DataStoreAppPreferencesRepository(dataStore)

        val result = repository.setExploreCity("porto novo")

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals(DomainError.Validation("error.preferences.explore_city_invalid"), failure.error)
        assertEquals("cotonou", dataStore.current()[EXPLORE_CITY_ID_KEY])
    }

    @Test
    fun setLocaleRejectsLocaleNotDeliveredInV1() = runTest {
        val dataStore = FakePreferencesDataStore(mutablePreferencesOf())
        val repository = DataStoreAppPreferencesRepository(dataStore)

        val result = repository.setLocale(AppLocale.English)

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals(DomainError.Validation("error.preferences.locale_unavailable"), failure.error)
        assertNull(dataStore.current()[APP_LOCALE_KEY])
    }

    @Test
    fun clearingExploreCityRemovesThePersistedValue() = runTest {
        val dataStore = FakePreferencesDataStore(
            mutablePreferencesOf(EXPLORE_CITY_ID_KEY to "cotonou"),
        )
        val repository = DataStoreAppPreferencesRepository(dataStore)

        val result = repository.setExploreCity(cityId = null)

        val preferences = assertIs<DomainResult.Success<AppPreferences>>(result).value
        assertNull(preferences.exploreCityId)
        assertNull(dataStore.current()[EXPLORE_CITY_ID_KEY])
    }

    @Test
    fun storageIoFailuresAreReturnedAsTypedDomainErrors() = runTest {
        val repository = DataStoreAppPreferencesRepository(FailingPreferencesDataStore())

        val readFailure = assertIs<DomainResult.Failure>(repository.get())
        val writeFailure = assertIs<DomainResult.Failure>(repository.setDisplayCurrency(KwaborCurrency.Eur))

        assertEquals(
            DomainError.LocalStorageUnavailable("error.preferences.storage_unavailable"),
            readFailure.error,
        )
        assertEquals(readFailure.error, writeFailure.error)
    }
}

private class FakePreferencesDataStore(
    initialPreferences: Preferences,
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initialPreferences)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }

    fun current(): Preferences = state.value
}

private class FailingPreferencesDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow {
        throw IOException("Synthetic preferences read failure.")
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        throw IOException("Synthetic preferences write failure.")
    }
}
