package com.kwabor.shared.data.auth

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest

internal class SupabaseAuthDataSource(
    auth: Auth,
    postgrest: Postgrest,
    accountDeletionStepUpSessionFactory: AccountDeletionStepUpSessionFactory,
    passwordRecoverySessionStore: PasswordRecoverySessionStore,
    accountDeletionSessionStore: AccountDeletionSessionStore,
    accountDeletionSessionGuard: AccountDeletionSessionGuard,
) : AuthDataSource,
    AuthSessionDataSource by SupabaseAuthSessionDataSource(
        auth = auth,
        postgrest = postgrest,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
        accountDeletionSessionGuard = accountDeletionSessionGuard,
    ),
    AuthRegistrationDataSource by SupabaseAuthRegistrationDataSource(
        auth = auth,
        postgrest = postgrest,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
        accountDeletionSessionGuard = accountDeletionSessionGuard,
    ),
    PasswordRecoveryAuthDataSource by SupabasePasswordRecoveryAuthDataSource(
        auth = auth,
        postgrest = postgrest,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
        accountDeletionSessionGuard = accountDeletionSessionGuard,
    ),
    PromoterActivationAuthDataSource by SupabasePromoterActivationAuthDataSource(
        auth = auth,
        postgrest = postgrest,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
        accountDeletionSessionGuard = accountDeletionSessionGuard,
    ),
    AccountSecurityAuthDataSource by SupabaseAccountSecurityAuthDataSource(
        auth = auth,
        stepUpSessionFactory = accountDeletionStepUpSessionFactory,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
        accountDeletionSessionStore = accountDeletionSessionStore,
        accountDeletionSessionGuard = accountDeletionSessionGuard,
    )
