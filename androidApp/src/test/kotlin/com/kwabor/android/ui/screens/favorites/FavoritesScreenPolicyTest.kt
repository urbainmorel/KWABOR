package com.kwabor.android.ui.screens.favorites

import androidx.compose.ui.unit.dp
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.favorites.FavoritesUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoritesScreenPolicyTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun liveRegionStatus_exposesActionableAsynchronousStates() {
        val idle = FavoritesUiState(isAccountReady = true)

        assertNull(favoritesLiveRegionStatus(idle, strings))
        assertEquals(strings.loading, favoritesLiveRegionStatus(idle.copy(isLoading = true), strings))
        assertEquals(
            "${strings.errorStateTitle}. ${strings.favorites.loadFailed}",
            favoritesLiveRegionStatus(idle.copy(errorMessage = strings.favorites.loadFailed), strings),
        )
        assertEquals(
            "${strings.errorStateTitle}. ${strings.favorites.refreshFailed}",
            favoritesLiveRegionStatus(idle.copy(refreshMessage = strings.favorites.refreshFailed), strings),
        )
        assertEquals(
            "${strings.errorStateTitle}. ${strings.favorites.removeFailed}",
            favoritesLiveRegionStatus(idle.copy(mutationMessage = strings.favorites.removeFailed), strings),
        )
    }

    @Test
    fun gridPolicy_reflowsFromPhoneToTabletAndAccessibilityText() {
        assertEquals(2, favoritesColumnCount(599.dp, fontScale = 1f))
        assertEquals(3, favoritesColumnCount(600.dp, fontScale = 1f))
        assertEquals(1, favoritesColumnCount(600.dp, fontScale = 1.3f))
    }

    @Test
    fun paginationPolicy_deduplicatesCursorAndRequiresRetryAfterFailure() {
        val ready = FavoritesUiState(isAccountReady = true, nextCursor = "cursor-2")

        assertTrue(shouldRequestNextFavoritesPage(true, "cursor-2", null, ready))
        assertFalse(shouldRequestNextFavoritesPage(true, "cursor-2", "cursor-2", ready))
        assertFalse(
            shouldRequestNextFavoritesPage(
                reachedThreshold = true,
                cursor = "cursor-2",
                requestedCursor = null,
                state = ready.copy(appendErrorMessage = strings.favorites.loadMoreFailed),
            ),
        )
    }
}
