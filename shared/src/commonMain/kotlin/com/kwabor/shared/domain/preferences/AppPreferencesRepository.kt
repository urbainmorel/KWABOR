package com.kwabor.shared.domain.preferences

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency

interface AppPreferencesRepository {
    suspend fun get(): DomainResult<AppPreferences>

    suspend fun setExploreCity(cityId: String?): DomainResult<AppPreferences>

    suspend fun setLocale(locale: AppLocale): DomainResult<AppPreferences>

    suspend fun setDisplayCurrency(currency: KwaborCurrency): DomainResult<AppPreferences>
}
