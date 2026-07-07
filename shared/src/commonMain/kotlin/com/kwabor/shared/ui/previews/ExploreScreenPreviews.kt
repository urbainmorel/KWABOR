package com.kwabor.shared.ui.previews

import androidx.compose.runtime.Composable
import com.kwabor.shared.design.KwaborTheme
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.sampleExploreUiState
import com.kwabor.shared.ui.screens.explore.ExploreScreen
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
fun ExploreScreenPreview() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme {
        ExploreScreen(
            state = sampleExploreUiState(strings),
            strings = strings,
        )
    }
}

@Preview
@Composable
fun ExploreScreenDarkPreview() {
    val strings = stringsFor(AppLocale.French)
    KwaborTheme(darkTheme = true) {
        ExploreScreen(
            state = sampleExploreUiState(strings).copy(isOffline = true),
            strings = strings,
        )
    }
}
