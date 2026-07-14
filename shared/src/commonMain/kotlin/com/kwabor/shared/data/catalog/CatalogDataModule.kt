package com.kwabor.shared.data.catalog

import com.kwabor.shared.domain.catalog.CatalogRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import org.koin.core.module.Module
import org.koin.dsl.module

internal val catalogDataModule: Module = module {
    single<CatalogDataSource> {
        SupabaseCatalogDataSource(
            postgrest = get<SupabaseClient>().postgrest,
        )
    }
    single<CatalogRepository> { DataCatalogRepository(dataSource = get()) }
}
