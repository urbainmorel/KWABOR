package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import org.koin.core.module.Module
import org.koin.dsl.module

internal val authDataModule: Module = module {
    single<AuthDataSource> {
        val auth = get<SupabaseClient>().auth
        val passwordRecoverySessionStore = auth.sessionManager as? PasswordRecoverySessionStore
            ?: error("Secure password recovery session storage is unavailable")
        SupabaseAuthDataSource(
            auth = auth,
            postgrest = get<SupabaseClient>().postgrest,
            passwordRecoverySessionStore = passwordRecoverySessionStore,
        )
    }
    single<AuthRepository> { DataAuthRepository(dataSource = get()) }
}
