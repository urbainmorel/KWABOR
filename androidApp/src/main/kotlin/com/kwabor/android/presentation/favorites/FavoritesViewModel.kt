package com.kwabor.android.presentation.favorites

import androidx.lifecycle.ViewModel
import com.kwabor.shared.i18n.FavoritesStrings
import com.kwabor.shared.presentation.favorites.FavoritesPresenter
import com.kwabor.shared.presentation.favorites.FavoritesRuntime
import com.kwabor.shared.presentation.favorites.FavoritesUiState
import com.kwabor.shared.presentation.session.ViewerSessionScope
import com.kwabor.shared.presentation.session.ViewerSessionScopeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transform
import com.kwabor.shared.presentation.favorites.FavoritesEffect as SharedFavoritesEffect
import com.kwabor.shared.presentation.favorites.FavoritesIntent as SharedFavoritesIntent

internal sealed interface FavoritesEffect {
    val scope: ViewerSessionScope

    data class OpenCatalogDetail(
        val listingId: String,
        override val scope: ViewerSessionScope,
    ) : FavoritesEffect

    data class FavoriteChanged(
        val listingId: String,
        val favorited: Boolean,
        override val scope: ViewerSessionScope,
    ) : FavoritesEffect
}

internal class FavoritesViewModel(
    presenter: FavoritesPresenter,
    strings: FavoritesStrings,
    private val coroutineScope: CoroutineScope,
    private val viewerSessionScopeTracker: ViewerSessionScopeTracker,
) : ViewModel() {
    private val runtime = FavoritesRuntime(
        presenter = presenter,
        strings = strings,
        coroutineScope = coroutineScope,
    )
    val state: StateFlow<FavoritesUiState> = runtime.state
    val effects: Flow<FavoritesEffect> = runtime.effects.transform { effect ->
        if (effect.scope == viewerSessionScopeTracker.currentScope) {
            emit(
                when (effect) {
                    is SharedFavoritesEffect.OpenCatalogDetail -> FavoritesEffect.OpenCatalogDetail(
                        listingId = effect.listingId,
                        scope = effect.scope,
                    )
                    is SharedFavoritesEffect.FavoriteChanged -> FavoritesEffect.FavoriteChanged(
                        listingId = effect.listingId,
                        favorited = effect.favorited,
                        scope = effect.scope,
                    )
                },
            )
        }
    }

    fun onIntent(intent: SharedFavoritesIntent) {
        runtime.dispatch(intent)
    }

    override fun onCleared() {
        runtime.close()
        coroutineScope.cancel()
        super.onCleared()
    }
}
