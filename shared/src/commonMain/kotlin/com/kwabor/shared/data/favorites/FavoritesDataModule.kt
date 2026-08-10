package com.kwabor.shared.data.favorites

import com.kwabor.shared.domain.favorites.FavoritesRepository
import com.kwabor.shared.domain.interaction.AccountScopedFavoriteMutationRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import org.koin.core.module.Module
import org.koin.dsl.module

internal val favoritesDataModule: Module = module {
    single<FavoritesDataSource> {
        SupabaseFavoritesDataSource(postgrest = get<SupabaseClient>().postgrest)
    }
    single { DataFavoritesRepository(dataSource = get()) }
    single<FavoritesRepository> { get<DataFavoritesRepository>() }
    single<AccountScopedFavoriteMutationRepository> { get<DataFavoritesRepository>() }
}
