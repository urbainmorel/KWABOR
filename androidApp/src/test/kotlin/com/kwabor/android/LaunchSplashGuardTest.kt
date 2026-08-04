package com.kwabor.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchSplashGuardTest {
    @Test
    fun splashIsKeptUntilTheMinimumDurationBoundary() {
        var nowMillis = 1_000L
        val guard = LaunchSplashGuard(
            nowMillis = { nowMillis },
            minimumVisibleDurationMillis = 1_000L,
        )

        assertTrue(guard.shouldKeepOnScreen())
        nowMillis = 1_999L
        assertTrue(guard.shouldKeepOnScreen())
        nowMillis = 2_000L
        assertFalse(guard.shouldKeepOnScreen())
    }

    @Test
    fun firstActivityInAProcessGetsTheBrandHoldWhileLaterCreationsDoNot() {
        assertEquals(
            COLD_START_MINIMUM_SPLASH_MILLIS,
            launchSplashMinimumVisibleDurationMillis(isFirstActivityInProcess = true),
        )
        assertEquals(
            0L,
            launchSplashMinimumVisibleDurationMillis(isFirstActivityInProcess = false),
        )
    }

    @Test
    fun processStateConsumesTheColdStartExactlyOnce() {
        val processState = LaunchProcessState()

        assertTrue(processState.consumeIsFirstActivityInProcess())
        assertFalse(processState.consumeIsFirstActivityInProcess())
        assertFalse(processState.consumeIsFirstActivityInProcess())
    }

    @Test
    fun regressedMonotonicClockKeepsTheSplashFailClosed() {
        var nowMillis = 1_000L
        val guard = LaunchSplashGuard(
            nowMillis = { nowMillis },
            minimumVisibleDurationMillis = 1_000L,
        )

        nowMillis = 999L

        assertTrue(guard.shouldKeepOnScreen())
    }

    @Test
    fun zeroDurationDoesNotDelayTheFirstApplicationFrame() {
        val guard = LaunchSplashGuard(
            nowMillis = { 1_000L },
            minimumVisibleDurationMillis = 0L,
        )

        assertFalse(guard.shouldKeepOnScreen())
    }

    @Test
    fun negativeDurationIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            LaunchSplashGuard(
                nowMillis = { 1_000L },
                minimumVisibleDurationMillis = -1L,
            )
        }
    }
}
