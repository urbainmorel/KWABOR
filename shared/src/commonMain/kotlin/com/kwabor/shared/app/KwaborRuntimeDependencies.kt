package com.kwabor.shared.app

import com.kwabor.shared.data.config.KwaborEnvironment
import com.kwabor.shared.data.config.createCatalogRepository
import com.kwabor.shared.data.core.SystemClockProvider
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.core.ClockProvider

class KwaborRuntimeDependencies private constructor(
    val catalogRepository: CatalogRepository,
    val clockProvider: ClockProvider,
) {
    companion object {
        fun createOrNull(supabaseUrl: String?, supabasePublishableKey: String?): KwaborRuntimeDependencies? {
            val safeUrl = supabaseUrl?.trim().orEmpty()
            val safePublishableKey = supabasePublishableKey?.trim().orEmpty()

            if (safeUrl.isBlank() || safePublishableKey.isBlank()) {
                return null
            }

            return runCatching {
                val environment = KwaborEnvironment(
                    supabaseUrl = safeUrl,
                    supabasePublishableKey = safePublishableKey,
                )
                KwaborRuntimeDependencies(
                    catalogRepository = createCatalogRepository(environment),
                    clockProvider = SystemClockProvider(),
                )
            }.getOrNull()
        }
    }
}
