package com.kwabor.shared.data.local

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosRoomStorageProtectionTest {
    private val fileManager = NSFileManager.defaultManager

    @Test
    fun protectedRoomDirectoryIsBackupExcludedAndIdempotent() = withTemporaryApplicationSupport { rootUrl ->
        val firstDirectoryUrl = prepareIosRoomDirectory(rootUrl, fileManager)
        val secondDirectoryUrl = prepareIosRoomDirectory(rootUrl, fileManager)

        assertEquals(firstDirectoryUrl.path, secondDirectoryUrl.path)
        assertRoomDirectoryPolicy(firstDirectoryUrl)
    }

    @Test
    fun existingDatabaseFamilyIsProtectedAndLegacyCacheIsRemoved() = withTemporaryApplicationSupport { rootUrl ->
        val rootPath = assertNotNull(rootUrl.path)
        IOS_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val legacyPath = "$rootPath/$KWABOR_DATABASE_FILENAME$suffix"
            assertTrue(fileManager.createFileAtPath(legacyPath, contents = null, attributes = null))
        }

        val roomDirectoryUrl = prepareIosRoomDirectory(rootUrl, fileManager)
        val roomDirectoryPath = assertNotNull(roomDirectoryUrl.path)
        IOS_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val legacyPath = "$rootPath/$KWABOR_DATABASE_FILENAME$suffix"
            assertFalse(fileManager.fileExistsAtPath(legacyPath))

            val protectedPath = "$roomDirectoryPath/$KWABOR_DATABASE_FILENAME$suffix"
            assertTrue(fileManager.createFileAtPath(protectedPath, contents = null, attributes = null))
        }

        prepareIosRoomDirectory(rootUrl, fileManager)

        val protectionKey = assertNotNull(NSFileProtectionKey)
        val protectionValue = assertNotNull(NSFileProtectionCompleteUntilFirstUserAuthentication)
        IOS_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val protectedPath = "$roomDirectoryPath/$KWABOR_DATABASE_FILENAME$suffix"
            val attributes = fileManager.attributesOfItemAtPath(protectedPath, error = null)
            assertEquals(protectionValue, attributes?.get(protectionKey))
        }
    }

    @Test
    fun directoryCollisionFailsClosed() = withTemporaryApplicationSupport { rootUrl ->
        val rootPath = assertNotNull(rootUrl.path)
        createLegacyDatabaseFamily(rootPath)
        assertTrue(
            fileManager.createFileAtPath(
                path = "$rootPath/KwaborRoom",
                contents = null,
                attributes = null,
            ),
        )

        assertFailsWith<IosRoomStoragePolicyException> {
            prepareIosRoomDirectory(rootUrl, fileManager)
        }
        assertLegacyDatabaseFamilyRemoved(rootPath)
    }

    @Test
    fun directoryCollisionUsesMemoryOnlyFallback() = runTest {
        withTemporaryApplicationSupport { rootUrl ->
            val rootPath = assertNotNull(rootUrl.path)
            createLegacyDatabaseFamily(rootPath)
            val collisionPath = "$rootPath/KwaborRoom"
            assertTrue(fileManager.createFileAtPath(collisionPath, contents = null, attributes = null))
            var failureReported = false

            val database = buildKwaborDatabase(
                builder = createIosKwaborDatabaseBuilder(
                    applicationSupportUrl = rootUrl,
                    fileManager = fileManager,
                    onPolicyFailure = { failureReported = true },
                ),
                queryCoroutineContext = coroutineContext,
            )
            try {
                assertNull(database.exploreCacheDao().findSnapshot("missing"))
                assertTrue(failureReported)
                assertTrue(fileManager.fileExistsAtPath(collisionPath))
                assertLegacyDatabaseFamilyRemoved(rootPath)
            } finally {
                database.close()
            }
        }
    }

    private fun createLegacyDatabaseFamily(rootPath: String) {
        IOS_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val legacyPath = "$rootPath/$KWABOR_DATABASE_FILENAME$suffix"
            assertTrue(fileManager.createFileAtPath(legacyPath, contents = null, attributes = null))
        }
    }

    private fun assertLegacyDatabaseFamilyRemoved(rootPath: String) {
        IOS_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val legacyPath = "$rootPath/$KWABOR_DATABASE_FILENAME$suffix"
            assertFalse(fileManager.fileExistsAtPath(legacyPath))
        }
    }

    private fun assertRoomDirectoryPolicy(directoryUrl: NSURL) {
        val backupKey = assertNotNull(NSURLIsExcludedFromBackupKey)
        val backupValues = directoryUrl.resourceValuesForKeys(listOf(backupKey), error = null)
        val isExcluded = backupValues?.get(backupKey) as? NSNumber
        assertEquals(true, isExcluded?.boolValue)

        val directoryPath = assertNotNull(directoryUrl.path)
        val protectionKey = assertNotNull(NSFileProtectionKey)
        val protectionValue = assertNotNull(NSFileProtectionCompleteUntilFirstUserAuthentication)
        val attributes = fileManager.attributesOfItemAtPath(directoryPath, error = null)
        assertEquals(protectionValue, attributes?.get(protectionKey))
    }

    private inline fun withTemporaryApplicationSupport(block: (NSURL) -> Unit) {
        val rootPath = "${NSTemporaryDirectory()}kwabor-room-policy-${NSUUID().UUIDString}"
        check(
            fileManager.createDirectoryAtPath(
                path = rootPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ),
        )
        val rootUrl = NSURL.fileURLWithPath(rootPath, isDirectory = true)
        try {
            block(rootUrl)
        } finally {
            if (fileManager.fileExistsAtPath(rootPath)) {
                fileManager.removeItemAtPath(rootPath, error = null)
            }
        }
    }
}

private val IOS_TEST_DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
