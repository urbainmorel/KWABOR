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
        val protectionApplicator = RecordingIosRoomFileProtectionApplicator()
        val secondDirectoryUrl = prepareIosRoomDirectory(rootUrl, fileManager, protectionApplicator)

        assertEquals(firstDirectoryUrl.path, secondDirectoryUrl.path)
        assertBackupExcluded(firstDirectoryUrl)
        val directoryPath = assertNotNull(firstDirectoryUrl.path)
        assertProtectionApplied(
            protectionApplicator = protectionApplicator,
            expectedPaths = listOf(directoryPath),
        )
    }

    @Test
    fun existingDatabaseFamilyIsProtectedAndLegacyCacheIsRemoved() = withTemporaryApplicationSupport { rootUrl ->
        val protectionApplicator = RecordingIosRoomFileProtectionApplicator()
        val rootPath = assertNotNull(rootUrl.path)
        IOS_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val legacyPath = "$rootPath/$KWABOR_DATABASE_FILENAME$suffix"
            assertTrue(fileManager.createFileAtPath(legacyPath, contents = null, attributes = null))
        }

        val roomDirectoryUrl = prepareIosRoomDirectory(rootUrl, fileManager, protectionApplicator)
        val roomDirectoryPath = assertNotNull(roomDirectoryUrl.path)
        IOS_TEST_DATABASE_SUFFIXES.forEach { suffix ->
            val legacyPath = "$rootPath/$KWABOR_DATABASE_FILENAME$suffix"
            assertFalse(fileManager.fileExistsAtPath(legacyPath))

            val protectedPath = "$roomDirectoryPath/$KWABOR_DATABASE_FILENAME$suffix"
            assertTrue(fileManager.createFileAtPath(protectedPath, contents = null, attributes = null))
        }

        protectionApplicator.clear()
        prepareIosRoomDirectory(rootUrl, fileManager, protectionApplicator)

        assertProtectionApplied(
            protectionApplicator = protectionApplicator,
            expectedPaths = buildList {
                add(roomDirectoryPath)
                IOS_TEST_DATABASE_SUFFIXES.forEach { suffix ->
                    add("$roomDirectoryPath/$KWABOR_DATABASE_FILENAME$suffix")
                }
            },
        )
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

    @Test
    fun fileProtectionFailureUsesMemoryOnlyFallback() = runTest {
        withTemporaryApplicationSupport { rootUrl ->
            val rootPath = assertNotNull(rootUrl.path)
            val roomDirectoryPath = "$rootPath/KwaborRoom"
            val protectionApplicator = RecordingIosRoomFileProtectionApplicator { false }
            var failureReported = false

            val database = buildKwaborDatabase(
                builder = createIosKwaborDatabaseBuilder(
                    applicationSupportUrl = rootUrl,
                    fileManager = fileManager,
                    protectionApplicator = protectionApplicator,
                    onPolicyFailure = { failureReported = true },
                ),
                queryCoroutineContext = coroutineContext,
            )
            try {
                assertNull(database.exploreCacheDao().findSnapshot("missing"))
                assertTrue(failureReported)
                assertProtectionApplied(
                    protectionApplicator = protectionApplicator,
                    expectedPaths = listOf(roomDirectoryPath),
                )
                IOS_TEST_DATABASE_SUFFIXES.forEach { suffix ->
                    assertFalse(
                        fileManager.fileExistsAtPath(
                            "$roomDirectoryPath/$KWABOR_DATABASE_FILENAME$suffix",
                        ),
                    )
                }
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun existingDatabaseFileProtectionFailureFailsClosed() = withTemporaryApplicationSupport { rootUrl ->
        val roomDirectoryUrl = prepareIosRoomDirectory(rootUrl, fileManager)
        val roomDirectoryPath = assertNotNull(roomDirectoryUrl.path)
        val databasePath = "$roomDirectoryPath/$KWABOR_DATABASE_FILENAME"
        assertTrue(fileManager.createFileAtPath(databasePath, contents = null, attributes = null))
        val protectionApplicator = RecordingIosRoomFileProtectionApplicator { path ->
            path != databasePath
        }

        assertFailsWith<IosRoomStoragePolicyException> {
            prepareIosRoomDirectory(rootUrl, fileManager, protectionApplicator)
        }
        assertProtectionApplied(
            protectionApplicator = protectionApplicator,
            expectedPaths = listOf(roomDirectoryPath, databasePath),
        )
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

    private fun assertBackupExcluded(directoryUrl: NSURL) {
        val backupKey = assertNotNull(NSURLIsExcludedFromBackupKey)
        val backupValues = directoryUrl.resourceValuesForKeys(listOf(backupKey), error = null)
        val isExcluded = backupValues?.get(backupKey) as? NSNumber
        assertEquals(true, isExcluded?.boolValue)
    }

    private fun assertProtectionApplied(
        protectionApplicator: RecordingIosRoomFileProtectionApplicator,
        expectedPaths: List<String>,
    ) {
        val protectionKey = assertNotNull(NSFileProtectionKey)
        val protectionValue = assertNotNull(NSFileProtectionCompleteUntilFirstUserAuthentication)
        assertEquals(expectedPaths, protectionApplicator.applications.map { application -> application.path })
        protectionApplicator.applications.forEach { application ->
            assertEquals(protectionValue, application.attributes[protectionKey])
        }
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

private class RecordingIosRoomFileProtectionApplicator(
    private val shouldSucceed: (String) -> Boolean = { true },
) : IosRoomFileProtectionApplicator {
    private val recordedApplications = mutableListOf<ProtectionApplication>()

    val applications: List<ProtectionApplication>
        get() = recordedApplications.toList()

    override fun apply(path: String, attributes: Map<Any?, Any?>): Boolean {
        recordedApplications += ProtectionApplication(path = path, attributes = attributes.toMap())
        return shouldSucceed(path)
    }

    fun clear() {
        recordedApplications.clear()
    }
}

private data class ProtectionApplication(
    val path: String,
    val attributes: Map<Any?, Any?>,
)
