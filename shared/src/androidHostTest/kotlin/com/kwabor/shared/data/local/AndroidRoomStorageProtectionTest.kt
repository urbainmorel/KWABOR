package com.kwabor.shared.data.local

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidRoomStorageProtectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        cleanStorage()
    }

    @After
    fun tearDown() {
        cleanStorage()
    }

    @Test
    fun databaseUsesNoBackupDirectoryAndRemovesLegacyCache() = runTest {
        val legacyBasePath = context.getDatabasePath(KWABOR_DATABASE_FILENAME).absolutePath
        ANDROID_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val legacyFile = File("$legacyBasePath$suffix")
            assertTrue(legacyFile.parentFile?.mkdirs() == true || legacyFile.parentFile?.isDirectory == true)
            assertTrue(legacyFile.createNewFile())
        }

        val databasePath = assertNotNull(prepareAndroidRoomDatabasePath(context))
        val expectedDirectory = File(context.noBackupFilesDir, "KwaborRoom").canonicalFile
        assertTrue(File(databasePath).canonicalFile.parentFile == expectedDirectory)
        ANDROID_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            assertFalse(File("$legacyBasePath$suffix").exists())
        }

        val database = buildKwaborDatabase(
            builder = createAndroidKwaborDatabaseBuilder(context),
            queryCoroutineContext = coroutineContext,
            driver = AndroidSQLiteDriver(),
        )
        try {
            assertNull(database.exploreCacheDao().findSnapshot("missing"))
            assertTrue(File(databasePath).isFile)
        } finally {
            database.close()
        }
    }

    @Test
    fun directoryCollisionFallsBackWithoutOpeningDiskDatabase() = runTest {
        val legacyBasePath = createLegacyDatabaseFamily()
        val collision = File(context.noBackupFilesDir, "KwaborRoom")
        assertTrue(collision.createNewFile())
        assertNull(prepareAndroidRoomDatabasePath(context))
        ANDROID_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            assertFalse(File("$legacyBasePath$suffix").exists())
        }

        val database = buildKwaborDatabase(
            builder = createAndroidKwaborDatabaseBuilder(context),
            queryCoroutineContext = coroutineContext,
            driver = AndroidSQLiteDriver(),
        )
        try {
            assertNull(database.exploreCacheDao().findSnapshot("missing"))
            assertTrue(collision.isFile)
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyCleanupAttemptsEveryFileAfterOneDeletionFailure() {
        val legacyBasePath = context.getDatabasePath(KWABOR_DATABASE_FILENAME).absolutePath
        val undeletableLegacyBase = File(legacyBasePath)
        assertTrue(undeletableLegacyBase.mkdirs())
        assertTrue(File(undeletableLegacyBase, "collision").createNewFile())
        ANDROID_TEST_DATABASE_SUFFIXES.drop(1).forEach { suffix ->
            assertTrue(File("$legacyBasePath$suffix").createNewFile())
        }

        assertNull(prepareAndroidRoomDatabasePath(context))

        assertTrue(undeletableLegacyBase.isDirectory)
        ANDROID_TEST_DATABASE_SUFFIXES.drop(1).forEach { suffix ->
            assertFalse(File("$legacyBasePath$suffix").exists())
        }
    }

    private fun createLegacyDatabaseFamily(): String {
        val legacyBasePath = context.getDatabasePath(KWABOR_DATABASE_FILENAME).absolutePath
        ANDROID_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val legacyFile = File("$legacyBasePath$suffix")
            assertTrue(legacyFile.parentFile?.mkdirs() == true || legacyFile.parentFile?.isDirectory == true)
            assertTrue(legacyFile.createNewFile())
        }
        return legacyBasePath
    }

    private fun cleanStorage() {
        val noBackupRoomPath = File(context.noBackupFilesDir, "KwaborRoom")
        if (noBackupRoomPath.isDirectory) {
            noBackupRoomPath.deleteRecursively()
        } else if (noBackupRoomPath.exists()) {
            noBackupRoomPath.delete()
        }
        val legacyBasePath = context.getDatabasePath(KWABOR_DATABASE_FILENAME).absolutePath
        ANDROID_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val legacyFile = File("$legacyBasePath$suffix")
            if (legacyFile.isDirectory) {
                legacyFile.deleteRecursively()
            } else {
                legacyFile.delete()
            }
        }
    }
}

private val ANDROID_TEST_DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
