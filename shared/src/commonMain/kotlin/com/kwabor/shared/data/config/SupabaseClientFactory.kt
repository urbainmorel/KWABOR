package com.kwabor.shared.data.config

import com.kwabor.shared.data.catalog.DataCatalogRepository
import com.kwabor.shared.data.catalog.SupabaseCatalogDataSource
import com.kwabor.shared.data.organization.DataOrganizationRepository
import com.kwabor.shared.data.organization.SupabaseOrganizationDataSource
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.organization.OrganizationRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest

fun createKwaborSupabaseClient(environment: KwaborEnvironment): SupabaseClient = createSupabaseClient(
    supabaseUrl = environment.supabaseUrl,
    supabaseKey = environment.supabasePublishableKey,
) {
    install(Postgrest)
}

fun createOrganizationRepository(environment: KwaborEnvironment): OrganizationRepository = DataOrganizationRepository(
    dataSource = SupabaseOrganizationDataSource(
        postgrest = createKwaborSupabaseClient(environment).postgrest,
    ),
)

fun createCatalogRepository(environment: KwaborEnvironment): CatalogRepository = DataCatalogRepository(
    dataSource = SupabaseCatalogDataSource(
        postgrest = createKwaborSupabaseClient(environment).postgrest,
    ),
)
