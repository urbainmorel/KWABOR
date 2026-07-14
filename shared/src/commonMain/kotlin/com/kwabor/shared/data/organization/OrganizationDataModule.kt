package com.kwabor.shared.data.organization

import com.kwabor.shared.domain.organization.OrganizationRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import org.koin.core.module.Module
import org.koin.dsl.module

internal val organizationDataModule: Module = module {
    single<OrganizationDataSource> {
        SupabaseOrganizationDataSource(
            postgrest = get<SupabaseClient>().postgrest,
        )
    }
    single<OrganizationRepository> { DataOrganizationRepository(dataSource = get()) }
}
