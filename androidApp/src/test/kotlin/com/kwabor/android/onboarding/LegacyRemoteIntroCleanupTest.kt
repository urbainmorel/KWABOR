package com.kwabor.android.onboarding

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyRemoteIntroCleanupTest {
    @Test
    fun successfulCleanupRemovesTheLegacyCacheAndMarksTheMigrationComplete() {
        val root = Files.createTempDirectory("kwabor-intro-cleanup").toFile()
        val cache = File(root, "intro-media").apply { mkdirs() }
        File(cache, "intro-1.mp4").writeBytes(byteArrayOf(1, 2, 3))
        File(cache, "intro-2.mp4.part").writeBytes(byteArrayOf(4, 5, 6))
        val state = FakeLegacyRemoteIntroCleanupState()

        try {
            assertTrue(LegacyRemoteIntroCleanup(state, cache).run())

            assertFalse(cache.exists())
            assertTrue(state.complete)
            assertTrue(state.preferencesRemoved)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedMigrationDoesNotTouchARecreatedDirectory() {
        val root = Files.createTempDirectory("kwabor-intro-cleanup-complete").toFile()
        val cache = File(root, "intro-media").apply { mkdirs() }
        val retained = File(cache, "retained.mp4").apply { writeBytes(byteArrayOf(1)) }
        val state = FakeLegacyRemoteIntroCleanupState(complete = true)

        try {
            assertTrue(LegacyRemoteIntroCleanup(state, cache).run())

            assertTrue(retained.exists())
            assertFalse(state.preferencesRemoved)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedPreferenceCleanupDoesNotAcknowledgeAndCanRetry() {
        val cache = File("unused-cache-directory")
        val state = FakeLegacyRemoteIntroCleanupState(preferenceRemovalSucceeds = false)
        val cleanup = LegacyRemoteIntroCleanup(
            state = state,
            cacheDirectory = cache,
            cacheCleanup = { true },
        )

        assertFalse(cleanup.run())
        assertFalse(state.complete)

        state.preferenceRemovalSucceeds = true
        assertTrue(cleanup.run())
        assertTrue(state.complete)
    }

    @Test
    fun failedCacheCleanupDoesNotAcknowledgeAndCanRetry() {
        val state = FakeLegacyRemoteIntroCleanupState()
        var cacheCanBeRemoved = false
        val cleanup = LegacyRemoteIntroCleanup(
            state = state,
            cacheDirectory = File("unused-cache-directory"),
            cacheCleanup = { cacheCanBeRemoved },
        )

        assertFalse(cleanup.run())
        assertFalse(state.complete)

        cacheCanBeRemoved = true
        assertTrue(cleanup.run())
        assertTrue(state.complete)
    }
}

private class FakeLegacyRemoteIntroCleanupState(
    var complete: Boolean = false,
    var preferenceRemovalSucceeds: Boolean = true,
) : LegacyRemoteIntroCleanupState {
    var preferencesRemoved = false
        private set

    override fun isComplete(): Boolean = complete

    override fun removeLegacyPreferences(): Boolean {
        preferencesRemoved = true
        return preferenceRemovalSucceeds
    }

    override fun markComplete(): Boolean {
        complete = true
        return true
    }
}
