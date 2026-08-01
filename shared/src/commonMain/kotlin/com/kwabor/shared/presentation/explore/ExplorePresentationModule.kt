package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.CatalogRepository
import org.koin.core.module.Module
import org.koin.dsl.module

internal fun explorePresentationModule(hasPersistence: Boolean): Module = module {
    factory {
        ExplorePresenter(
            exploreFeedRepository = get(),
            catalogInteractionRepository = get<CatalogRepository>(),
            appPreferencesRepository = if (hasPersistence) get() else null,
            clockProvider = get(),
        )
    }
}
