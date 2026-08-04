package com.kwabor.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kwabor.android.presentation.search.SearchEffect
import com.kwabor.shared.presentation.detail.CatalogDetailIntent

@Composable
internal fun SearchEffectHandler(dependencies: HomeShellDependencies) {
    LaunchedEffect(dependencies.searchViewModel, dependencies.catalogDetailViewModel) {
        dependencies.searchViewModel.effects.collect { effect ->
            when (effect) {
                is SearchEffect.OpenCatalogDetail -> dependencies.catalogDetailViewModel.onIntent(
                    CatalogDetailIntent.Open(effect.listingId),
                )
            }
        }
    }
}
