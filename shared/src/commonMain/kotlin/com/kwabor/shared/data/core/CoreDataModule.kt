package com.kwabor.shared.data.core

import com.kwabor.shared.data.config.KwaborEnvironment
import com.kwabor.shared.data.config.createKwaborSupabaseClient
import com.kwabor.shared.domain.core.ClockProvider
import io.github.jan.supabase.auth.SessionManager
import org.koin.core.module.Module
import org.koin.dsl.module

internal fun coreDataModule(environment: KwaborEnvironment, authSessionManager: SessionManager?): Module = module {
    single { environment }
    single {
        createKwaborSupabaseClient(
            environment = get(),
            authSessionManager = authSessionManager,
        )
    }
    single<ClockProvider> { SystemClockProvider() }
}
