package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.CatalogRepository
import org.koin.core.module.Module
import org.koin.dsl.module

internal val catalogDetailPresentationModule: Module = module {
    factory {
        CatalogDetailPresenter(
            catalogRepository = get<CatalogRepository>(),
            clockProvider = get(),
        )
    }
}
