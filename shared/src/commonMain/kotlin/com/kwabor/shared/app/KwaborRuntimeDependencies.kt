package com.kwabor.shared.app

import com.kwabor.shared.data.config.KwaborEnvironment
import com.kwabor.shared.data.config.createAuthRepository
import com.kwabor.shared.data.config.createAuthenticatedCatalogRepository
import com.kwabor.shared.data.config.createCatalogRepository
import com.kwabor.shared.data.core.SystemClockProvider
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.core.ClockProvider
import io.github.jan.supabase.auth.SessionManager

class KwaborRuntimeDependencies private constructor(
    val catalogRepository: CatalogRepository,
    val clockProvider: ClockProvider,
    val authRepository: AuthRepository?,
) {
    companion object {
        fun createOrNull(
            supabaseUrl: String?,
            supabasePublishableKey: String?,
            authSessionManager: SessionManager? = null,
        ): KwaborRuntimeDependencies? {
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
                    catalogRepository = authSessionManager?.let { sessionManager ->
                        createAuthenticatedCatalogRepository(
                            environment = environment,
                            authSessionManager = sessionManager,
                        )
                    } ?: createCatalogRepository(environment),
                    clockProvider = SystemClockProvider(),
                    authRepository = authSessionManager?.let { sessionManager ->
                        createAuthRepository(
                            environment = environment,
                            authSessionManager = sessionManager,
                        )
                    },
                )
            }.getOrNull()
        }
    }
}
