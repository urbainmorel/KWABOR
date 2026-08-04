package com.kwabor.android.ui.screens.explore

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.i18n.stringsFor
import com.kwabor.shared.presentation.search.SearchUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchScreenPoliciesTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun paginationGuardRequestsEachEligibleCursorOnceAndCanReset() {
        val guard = SearchPaginationGuard()

        assertTrue(
            guard.shouldRequest(
                cursor = "cursor-1",
                canLoadMore = true,
                reachedPaginationThreshold = true,
                hasAppendError = false,
            ),
        )
        assertFalse(
            guard.shouldRequest(
                cursor = "cursor-1",
                canLoadMore = true,
                reachedPaginationThreshold = true,
                hasAppendError = false,
            ),
        )

        guard.reset()

        assertTrue(
            guard.shouldRequest(
                cursor = "cursor-1",
                canLoadMore = true,
                reachedPaginationThreshold = true,
                hasAppendError = false,
            ),
        )
    }

    @Test
    fun paginationGuardRejectsIncompleteOrFailedStates() {
        val guard = SearchPaginationGuard()

        assertFalse(guard.shouldRequest(null, true, true, false))
        assertFalse(guard.shouldRequest("cursor", false, true, false))
        assertFalse(guard.shouldRequest("cursor", true, false, false))
        assertFalse(guard.shouldRequest("cursor", true, true, true))
        assertNull(guard.requestedCursor)
    }

    @Test
    fun liveRegionAnnouncesQueryValidationAfterFocusLeavesTheField() {
        val state = SearchUiState(queryErrorMessage = strings.search.invalidQuery)

        assertEquals(strings.search.invalidQuery, searchLiveRegionStatus(state, strings))
    }
}
