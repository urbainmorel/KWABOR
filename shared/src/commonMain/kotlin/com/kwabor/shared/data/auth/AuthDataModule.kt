package com.kwabor.shared.data.auth

import com.kwabor.shared.domain.auth.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import org.koin.core.module.Module
import org.koin.dsl.module

internal val authDataModule: Module = module {
    single<AccountDeletionStepUpSessionFactory> {
        SupabaseAccountDeletionStepUpSessionFactory(environment = get())
    }
    single<AuthDataSource> {
        val auth = get<SupabaseClient>().auth
        val sessionManager = auth.sessionManager
        val passwordRecoverySessionStore = sessionManager as? PasswordRecoverySessionStore
            ?: error("Secure password recovery session storage is unavailable")
        val accountDeletionSessionStore = sessionManager as? AccountDeletionSessionStore
            ?: error("Secure account deletion session storage is unavailable")
        val accountDeletionSessionGuard = AccountDeletionSessionGuard(
            coordinator = AccountDeletionSessionCoordinator(
                accountDeletionStore = accountDeletionSessionStore,
                passwordRecoveryStore = passwordRecoverySessionStore,
            ),
            clearCurrentSession = auth::clearSession,
        )
        SupabaseAuthDataSource(
            auth = auth,
            postgrest = get<SupabaseClient>().postgrest,
            accountDeletionStepUpSessionFactory = get(),
            passwordRecoverySessionStore = passwordRecoverySessionStore,
            accountDeletionSessionStore = accountDeletionSessionStore,
            accountDeletionSessionGuard = accountDeletionSessionGuard,
        )
    }
    single<AuthRepository> { DataAuthRepository(dataSource = get()) }
}
