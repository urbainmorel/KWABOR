package com.kwabor.shared.app

import com.kwabor.shared.data.auth.authDataModule
import com.kwabor.shared.data.catalog.catalogDataModule
import com.kwabor.shared.data.config.createKwaborEnvironmentOrNull
import com.kwabor.shared.data.core.coreDataModule
import com.kwabor.shared.data.organization.organizationDataModule
import com.kwabor.shared.domain.auth.AuthRepository
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.organization.OrganizationRepository
import com.kwabor.shared.presentation.auth.AuthPresenter
import com.kwabor.shared.presentation.auth.authPresentationModule
import com.kwabor.shared.presentation.explore.ExplorePresenter
import com.kwabor.shared.presentation.explore.explorePresentationModule
import io.github.jan.supabase.auth.SessionManager
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class KwaborCompositionRoot internal constructor(
    private val application: KoinApplication,
    hasAuthentication: Boolean,
) {
    val catalogRepository: CatalogRepository = application.koin.get()
    val clockProvider: ClockProvider = application.koin.get()
    val organizationRepository: OrganizationRepository = application.koin.get()
    val explorePresenter: ExplorePresenter = application.koin.get()
    val authRepository: AuthRepository? = if (hasAuthentication) application.koin.get() else null
    val authPresenter: AuthPresenter? = if (hasAuthentication) application.koin.get() else null

    fun close() {
        application.close()
    }
}

internal fun createKwaborCompositionRootOrNull(
    supabaseUrl: String?,
    supabasePublishableKey: String?,
    environmentName: String? = DEFAULT_ENVIRONMENT_NAME,
    authSessionManager: SessionManager? = null,
): KwaborCompositionRoot? {
    val environment = createKwaborEnvironmentOrNull(
        environmentName = environmentName,
        supabaseUrl = supabaseUrl,
        supabasePublishableKey = supabasePublishableKey,
    ) ?: return null
    val rootModule = module {
        includes(
            coreDataModule(
                environment = environment,
                authSessionManager = authSessionManager,
            ),
            catalogDataModule,
            explorePresentationModule,
            organizationDataModule,
        )
        if (authSessionManager != null) {
            includes(authDataModule, authPresentationModule)
        }
    }
    val application = koinApplication {
        allowOverride(false)
        modules(rootModule)
    }

    return KwaborCompositionRoot(
        application = application,
        hasAuthentication = authSessionManager != null,
    )
}

private const val DEFAULT_ENVIRONMENT_NAME = "development"
