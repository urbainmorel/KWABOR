package com.kwabor.android.ui.previews

import androidx.compose.runtime.Composable
import com.kwabor.android.design.KwaborTheme
import com.kwabor.android.ui.screens.explore.ExploreScreen
import com.kwabor.android.ui.screens.explore.ExploreScreenActions
import com.kwabor.android.ui.screens.explore.ExploreScreenUiModel
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.sampleExploreUiState
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
fun ExploreScreenPreview() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme {
        ExploreScreen(
            model = ExploreScreenUiModel(state = sampleExploreUiState(strings)),
            strings = strings,
            mediaUrlPolicy = previewListingMediaUrlPolicy,
            actions = previewExploreActions,
        )
    }
}

@Preview
@Composable
fun ExploreScreenDarkPreview() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme(darkTheme = true) {
        ExploreScreen(
            model = ExploreScreenUiModel(state = sampleExploreUiState(strings).copy(isOffline = true)),
            strings = strings,
            mediaUrlPolicy = previewListingMediaUrlPolicy,
            actions = previewExploreActions,
        )
    }
}

private val previewExploreActions = ExploreScreenActions(
    onTabSelected = {},
    onChipSelected = {},
    onListingClick = {},
    onLikeClick = {},
    onFavoriteClick = {},
    onRetry = {},
    onRefresh = {},
    onLoadNext = {},
    onCityClick = {},
    onCityDismiss = {},
    onCitySelected = {},
    onUseLocation = {},
    onGuideDiscoveryClick = {},
)
