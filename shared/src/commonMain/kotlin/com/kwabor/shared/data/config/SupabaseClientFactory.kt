package com.kwabor.shared.data.config

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

fun createKwaborSupabaseClient(
    environment: KwaborEnvironment,
    authSessionManager: SessionManager? = null,
): SupabaseClient = createSupabaseClient(
    supabaseUrl = environment.supabaseUrl,
    supabaseKey = environment.supabasePublishableKey,
) {
    install(Postgrest)
    authSessionManager?.let { secureSessionManager ->
        install(Auth) {
            sessionManager = secureSessionManager
            // Native Google/Apple acquisition supplies ID tokens; no Supabase browser redirect is used.
            codeVerifierCache = MemoryCodeVerifierCache()
            flowType = FlowType.PKCE
            scheme = "kwabor"
            host = "auth"
        }
    }
}
