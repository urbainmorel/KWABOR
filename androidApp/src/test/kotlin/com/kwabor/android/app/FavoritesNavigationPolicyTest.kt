package com.kwabor.android.app

import com.kwabor.shared.presentation.navigation.RootNavigationDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritesNavigationPolicyTest {
    @Test
    fun favorites_isTypedChildRouteOfProfileRootWithoutAddingNavbarItem() {
        assertEquals(RootNavigationDestination.Profile, FavoritesRoute.rootDestination)
        assertEquals(5, RootNavigationDestination.entries.size)
    }

    @Test
    fun privateStack_isPurgedWhenExistingAccountChangesOrEnds() {
        val policy = FavoritesAccountNavigationPolicy(initialAccountId = ACCOUNT_A)

        assertFalse(policy.shouldPurgeFor(ACCOUNT_A))
        assertTrue(policy.shouldPurgeFor(ACCOUNT_B))
        assertTrue(policy.shouldPurgeFor(null))
        assertFalse(policy.shouldPurgeFor(ACCOUNT_A))
    }
}

private const val ACCOUNT_A = "00000000-0000-4000-8000-000000000001"
private const val ACCOUNT_B = "00000000-0000-4000-8000-000000000002"
