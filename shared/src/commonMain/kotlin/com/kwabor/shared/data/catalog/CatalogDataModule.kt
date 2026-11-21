package com.kwabor.shared.data.catalog

import com.kwabor.shared.data.config.KwaborEnvironment
import com.kwabor.shared.data.config.KwaborEnvironmentTier
import com.kwabor.shared.data.local.KwaborDatabase
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.interaction.AccountScopedListingLikeRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import org.koin.core.module.Module
import org.koin.dsl.module

internal fun catalogDataModule(hasAuthentication: Boolean): Module = module {
    single<CatalogSessionState> {
        if (hasAuthentication) {
            SupabaseCatalogSessionState(auth = get<SupabaseClient>().auth)
        } else {
            GuestCatalogSessionState
        }
    }
    single<CatalogDataSource> {
        SessionAwareCatalogDataSource(
            delegate = SupabaseCatalogDataSource(
                postgrest = get<SupabaseClient>().postgrest,
            ),
            sessionState = get(),
        )
    }
    single {
        val environment = get<KwaborEnvironment>()
        val detailCache = if (environment.tier == KwaborEnvironmentTier.Staging) {
            getOrNull<KwaborDatabase>()?.let { database ->
                DemoCatalogDetailCache(
                    dao = database.demoCatalogDetailDao(),
                    projectUrl = environment.supabaseUrl.trimEnd('/'),
                    clock = get(),
                )
            }
        } else {
            null
        }
        DataCatalogRepository(dataSource = get(), detailCache = detailCache)
    }
    single<CatalogRepository> { get<DataCatalogRepository>() }
    single<AccountScopedListingLikeRepository> { get<DataCatalogRepository>() }
}
