package com.kwabor.shared.presentation.search

import com.kwabor.shared.domain.search.SearchRepository
import org.koin.core.module.Module
import org.koin.dsl.module

internal val searchPresentationModule: Module = module {
    factory { SearchPresenter(repository = get<SearchRepository>()) }
}
