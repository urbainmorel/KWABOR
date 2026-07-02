package com.kwabor.shared.domain.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLocaleTest {
    @Test
    fun resolveAppLocale_prefersFirstDeliveredLanguageIgnoringRegion() {
        val locale = resolveAppLocale(
            preferredLanguageTags = listOf("pt-BR", "en-US"),
            deliveredLocales = setOf(AppLocale.French, AppLocale.Portuguese),
        )

        assertEquals(AppLocale.Portuguese, locale)
    }

    @Test
    fun resolveAppLocale_fallsBackToFrenchWhenOnlyFrenchIsDelivered() {
        val locale = resolveAppLocale(
            preferredLanguageTags = listOf("zh-CN"),
            deliveredLocales = setOf(AppLocale.French),
        )

        assertEquals(AppLocale.French, locale)
    }
}
