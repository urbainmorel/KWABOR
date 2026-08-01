package com.kwabor.shared.domain.preferences

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AppPreferencesTest {
    @Test
    fun default_isV1FrenchXofWithoutSelectedCity() {
        assertNull(AppPreferences.Default.exploreCityId)
        assertEquals(AppLocale.French, AppPreferences.Default.locale)
        assertEquals(KwaborCurrency.Xof, AppPreferences.Default.displayCurrency)
    }

    @Test
    fun create_normalizesAValidExploreCityId() {
        val result = AppPreferences.create(
            exploreCityId = "  cotonou_1  ",
            locale = AppLocale.French,
            displayCurrency = KwaborCurrency.Eur,
        )

        val success = assertIs<DomainResult.Success<AppPreferences>>(result)
        assertEquals("cotonou_1", success.value.exploreCityId)
        assertEquals(KwaborCurrency.Eur, success.value.displayCurrency)
    }

    @Test
    fun create_rejectsBlankOrUnsafeExploreCityIds() {
        listOf("", "   ", "porto novo", "../cotonou").forEach { cityId ->
            val result = AppPreferences.create(
                exploreCityId = cityId,
                locale = AppLocale.French,
                displayCurrency = KwaborCurrency.Xof,
            )

            val failure = assertIs<DomainResult.Failure>(result)
            assertEquals(
                DomainError.Validation("error.preferences.explore_city_invalid"),
                failure.error,
            )
        }
    }

    @Test
    fun create_rejectsLocalesNotDeliveredInV1() {
        val result = AppPreferences.create(
            exploreCityId = null,
            locale = AppLocale.English,
            displayCurrency = KwaborCurrency.Xof,
        )

        val failure = assertIs<DomainResult.Failure>(result)
        assertEquals(DomainError.Validation("error.preferences.locale_unavailable"), failure.error)
    }
}
