package com.kwabor.shared.presentation.favorites

import com.kwabor.shared.domain.favorites.FavoritesRepository
import org.koin.core.module.Module
import org.koin.dsl.module

internal val favoritesPresentationModule: Module = module {
    factory { FavoritesPresenter(repository = get<FavoritesRepository>()) }
}
