package com.kwabor.shared.data.auth

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest

internal class SupabaseAuthDataSource(
    auth: Auth,
    postgrest: Postgrest,
    accountDeletionStepUpSessionFactory: AccountDeletionStepUpSessionFactory,
    passwordRecoverySessionStore: PasswordRecoverySessionStore,
) : AuthDataSource,
    AuthSessionDataSource by SupabaseAuthSessionDataSource(
        auth = auth,
        postgrest = postgrest,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
    ),
    AuthRegistrationDataSource by SupabaseAuthRegistrationDataSource(
        auth = auth,
        postgrest = postgrest,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
    ),
    PasswordRecoveryAuthDataSource by SupabasePasswordRecoveryAuthDataSource(
        auth = auth,
        postgrest = postgrest,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
    ),
    PromoterActivationAuthDataSource by SupabasePromoterActivationAuthDataSource(
        auth = auth,
        postgrest = postgrest,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
    ),
    AccountSecurityAuthDataSource by SupabaseAccountSecurityAuthDataSource(
        auth = auth,
        stepUpSessionFactory = accountDeletionStepUpSessionFactory,
        passwordRecoverySessionStore = passwordRecoverySessionStore,
    )
