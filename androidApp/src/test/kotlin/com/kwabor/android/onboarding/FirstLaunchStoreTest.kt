package com.kwabor.android.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstLaunchStoreTest {
    @Test
    fun bundledIntroRevisionStartsAtOne() {
        assertEquals(1L, BUNDLED_INTRO_REVISION)
    }

    @Test
    fun legacySeenFlagMigratesToTheFixedRevisionOneBaseline() {
        assertEquals(1L, migratedBundledIntroRevision(storedRevision = null, legacyIntroSeen = true))
        assertEquals(0L, migratedBundledIntroRevision(storedRevision = null, legacyIntroSeen = false))
    }

    @Test
    fun durableRevisionRemainsAuthoritativeAndCannotBecomeNegative() {
        assertEquals(3L, migratedBundledIntroRevision(storedRevision = 3L, legacyIntroSeen = false))
        assertEquals(0L, migratedBundledIntroRevision(storedRevision = -1L, legacyIntroSeen = true))
    }

    @Test
    fun eachStrictlyNewerBundledRevisionIsRequiredExactlyOnce() {
        assertTrue(isBundledIntroRevisionRequired(presentedRevision = 1L, bundledRevision = 2L))
        assertFalse(isBundledIntroRevisionRequired(presentedRevision = 2L, bundledRevision = 2L))
        assertFalse(isBundledIntroRevisionRequired(presentedRevision = 3L, bundledRevision = 2L))
    }

    @Test
    fun nonPositiveBundledRevisionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            isBundledIntroRevisionRequired(presentedRevision = 0L, bundledRevision = 0L)
        }
    }
}
