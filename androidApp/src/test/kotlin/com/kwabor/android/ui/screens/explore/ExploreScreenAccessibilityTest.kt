package com.kwabor.android.ui.screens.explore

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.explore.initialExploreUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExploreScreenAccessibilityTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun feedLiveRegionStatus_exposesOnlyActionableAsynchronousStates() {
        val idle = initialExploreUiState(strings)

        assertNull(exploreFeedLiveRegionStatus(idle, strings))
        assertEquals(
            strings.loading,
            exploreFeedLiveRegionStatus(idle.copy(isLoading = true), strings),
        )
        assertEquals(
            "${strings.errorStateTitle}. ${strings.exploreLoadMoreError}",
            exploreFeedLiveRegionStatus(idle.copy(errorMessage = strings.exploreLoadMoreError), strings),
        )
        assertEquals(
            strings.loading,
            exploreFeedLiveRegionStatus(idle.copy(isAppending = true), strings),
        )
        assertEquals(
            "${strings.errorStateTitle}. ${strings.exploreLoadMoreError}",
            exploreFeedLiveRegionStatus(idle.copy(appendErrorMessage = strings.exploreLoadMoreError), strings),
        )
    }
}
