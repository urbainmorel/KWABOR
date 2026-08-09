package com.kwabor.shared.presentation.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewerSessionScopeTrackerTest {
    @Test
    fun duplicatePublicationKeepsTheSameScope() {
        val tracker = ViewerSessionScopeTracker()

        val first = tracker.update(accountId = " account-a ", accountSetupComplete = true)
        val duplicate = tracker.update(accountId = "account-a", accountSetupComplete = true)

        assertEquals(ViewerSessionScope(accountId = "account-a", epoch = 1L), first)
        assertEquals(first, duplicate)
        assertEquals(first, tracker.currentScope)
    }

    @Test
    fun logoutAndReloginToTheSameAccountReceiveDifferentEpochs() {
        val tracker = ViewerSessionScopeTracker()

        val firstLogin = tracker.update(accountId = "account-a", accountSetupComplete = true)
        val logout = tracker.update(accountId = "account-a", accountSetupComplete = false)
        val secondLogin = tracker.update(accountId = "account-a", accountSetupComplete = true)

        assertEquals(1L, firstLogin.epoch)
        assertNull(logout.accountId)
        assertEquals(2L, logout.epoch)
        assertEquals("account-a", secondLogin.accountId)
        assertEquals(3L, secondLogin.epoch)
    }

    @Test
    fun incompleteAccountIsNormalizedToTheInitialGuestWithoutIncrementing() {
        val tracker = ViewerSessionScopeTracker()

        val scope = tracker.update(accountId = "account-a", accountSetupComplete = false)

        assertEquals(ViewerSessionScope.InitialGuest, scope)
    }
}
