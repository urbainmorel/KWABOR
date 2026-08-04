package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogRepository
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
    single<CatalogRepository> { DataCatalogRepository(dataSource = get()) }
}
