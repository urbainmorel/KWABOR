package com.kwabor.android.app

import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoritesNavigationPolicyTest {
    @Test
    fun favorites_isTypedChildRouteOfProfileRootWithoutAddingNavbarItem() {
        assertEquals(RootNavigationDestination.Profile, FavoritesRoute.rootDestination)
        assertEquals(5, RootNavigationDestination.entries.size)
    }

    @Test
    fun privateStack_isPurgedWhenExistingAccountChangesOrEnds() {
        val policy = FavoritesAccountNavigationPolicy(initialAccountId = ACCOUNT_A)

        assertEquals(FavoritesNavigationPrivacyDecision.None, policy.decisionFor(ACCOUNT_A))
        assertEquals(FavoritesNavigationPrivacyDecision.PurgePrivateChildren, policy.decisionFor(ACCOUNT_B))
        assertEquals(FavoritesNavigationPrivacyDecision.PurgePrivateChildren, policy.decisionFor(null))
        assertEquals(FavoritesNavigationPrivacyDecision.None, policy.decisionFor(ACCOUNT_A))
    }

    @Test
    fun initialGuestState_resetsColdRestoredPrivateStackOnlyOnce() {
        val policy = FavoritesAccountNavigationPolicy(initialAccountId = null)

        assertEquals(FavoritesNavigationPrivacyDecision.ResetToHomeAndPurge, policy.decisionFor(null))
        assertEquals(FavoritesNavigationPrivacyDecision.None, policy.decisionFor(null))
    }
}

private const val ACCOUNT_A = "00000000-0000-4000-8000-000000000001"
private const val ACCOUNT_B = "00000000-0000-4000-8000-000000000002"
