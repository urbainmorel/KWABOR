package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import org.koin.core.module.Module
import org.koin.dsl.module

internal val authDataModule: Module = module {
    single<AuthDataSource> {
        SupabaseAuthDataSource(
            auth = get<SupabaseClient>().auth,
        )
    }
    single<AuthRepository> { DataAuthRepository(dataSource = get()) }
}
