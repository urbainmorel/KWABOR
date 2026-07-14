package com.kwabor.shared.presentation.explore

import org.koin.core.module.Module
import org.koin.dsl.module

internal val explorePresentationModule: Module = module {
    factory {
        ExplorePresenter(
            catalogRepository = get(),
            clockProvider = get(),
        )
    }
}
