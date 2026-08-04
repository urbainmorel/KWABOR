package com.kwabor.shared.data.guide

import com.kwabor.shared.domain.guide.GuideDiscoveryRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import org.koin.core.module.Module
import org.koin.dsl.module

internal val guideDiscoveryDataModule: Module = module {
    single<GuideDiscoveryDataSource> {
        SupabaseGuideDiscoveryDataSource(postgrest = get<SupabaseClient>().postgrest)
    }
    single<GuideDiscoveryRepository> { DataGuideDiscoveryRepository(dataSource = get()) }
}
