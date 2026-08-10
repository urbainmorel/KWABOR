package com.kwabor.android

import kotlin.test.Test
import kotlin.test.assertEquals

class MainActivityForegroundTest {
    @Test
    fun foregroundRetriesPendingAuthenticationPrivacyCleanupExactlyOnce() {
        var notifications = 0

        notifyAuthenticationForeground { notifications += 1 }

        assertEquals(1, notifications)
    }

    @Test
    fun authenticationForegroundRemainsSafeWithoutConfiguredViewModel() {
        notifyAuthenticationForeground(onForeground = null)
    }

    @Test
    fun foregroundNotifiesDurableInteractionsExactlyOnce() {
        var notifications = 0

        notifyInteractionForeground { notifications += 1 }

        assertEquals(1, notifications)
    }

    @Test
    fun foregroundRemainsSafeWithoutDurableInteractions() {
        notifyInteractionForeground(onForeground = null)
    }
}
