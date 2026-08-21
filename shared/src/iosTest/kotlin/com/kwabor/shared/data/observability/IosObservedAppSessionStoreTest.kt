package com.kwabor.shared.data.observability

import com.kwabor.shared.domain.observability.ObservedAppSessionCheckpointRead
import kotlinx.cinterop.ExperimentalForeignApi
import okio.IOException
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosObservedAppSessionStoreTest {
    private val fileManager = NSFileManager.defaultManager

    @Test
    fun failedAtomicReplacementIsNeverAcknowledged() {
        val file = FakeIosObservedAppSessionFile(replacementSucceeds = false)
        val store = IosObservedAppSessionStore(file)

        assertFalse(store.writeForeground())
        assertEquals(IosObservedAppSessionFileRead.Missing, file.read())
    }

    @Test
    fun failedDurableRemovalIsNeverAcknowledged() {
        val file = FakeIosObservedAppSessionFile(
            initialValue = ObservedAppSessionCheckpointCodec.encodeForeground(),
            removalSucceeds = false,
        )
        val store = IosObservedAppSessionStore(file)

        assertFalse(store.clear())
        assertEquals(ObservedAppSessionCheckpointRead.Foreground, store.read())
    }

    @Test
    fun fileReadFailureFailsClosed() {
        val store = IosObservedAppSessionStore(FailingIosObservedAppSessionFile)

        assertEquals(ObservedAppSessionCheckpointRead.Failure, store.read())
    }

    @Test
    fun thrownFileReadFailureFailsClosed() {
        val store = IosObservedAppSessionStore(ThrowingIosObservedAppSessionFile)

        assertEquals(ObservedAppSessionCheckpointRead.Failure, store.read())
    }

    @Test
    fun checkpointDirectoryIsExcludedFromBackupBeforePersistence() = withTemporaryApplicationSupport { rootUrl ->
        val store = createIosObservedAppSessionStore(rootUrl, fileManager)
        val directoryUrl = assertNotNull(
            rootUrl.URLByAppendingPathComponent(
                pathComponent = OBSERVED_APP_SESSION_DIRECTORY_FOR_TEST,
                isDirectory = true,
            ),
        )

        assertTrue(store.writeForeground())
        assertBackupExcluded(directoryUrl)
    }

    @Test
    fun backupExclusionFailureDisablesPersistenceBeforeCheckpointCreation() =
        withTemporaryApplicationSupport { rootUrl ->
            val directoryUrl = assertNotNull(
                rootUrl.URLByAppendingPathComponent(
                    pathComponent = OBSERVED_APP_SESSION_DIRECTORY_FOR_TEST,
                    isDirectory = true,
                ),
            )
            val directoryPath = assertNotNull(directoryUrl.path)
            val store = createIosObservedAppSessionStore(
                applicationSupportUrl = rootUrl,
                fileManager = fileManager,
                backupExclusionApplicator = IosObservedAppSessionBackupExclusionApplicator { _, _ -> false },
            )

            assertFalse(store.writeForeground())
            assertEquals(ObservedAppSessionCheckpointRead.Failure, store.read())
            assertFalse(
                fileManager.fileExistsAtPath("$directoryPath/$OBSERVED_APP_SESSION_FILE_FOR_TEST"),
            )
        }

    @Test
    fun backupExclusionPreparationIsRetriedAfterATransientFailureInTheSameProcess() =
        withTemporaryApplicationSupport { rootUrl ->
            var preparationAttempts = 0
            val store = createIosObservedAppSessionStore(
                applicationSupportUrl = rootUrl,
                fileManager = fileManager,
                backupExclusionApplicator = IosObservedAppSessionBackupExclusionApplicator { _, _ ->
                    preparationAttempts += 1
                    preparationAttempts > 1
                },
            )

            assertFalse(store.writeForeground())
            assertTrue(store.writeForeground())
            assertEquals(ObservedAppSessionCheckpointRead.Foreground, store.read())
            assertEquals(2, preparationAttempts)
        }

    private fun assertBackupExcluded(directoryUrl: NSURL) {
        val backupKey = assertNotNull(NSURLIsExcludedFromBackupKey)
        val backupValues = directoryUrl.resourceValuesForKeys(listOf(backupKey), error = null)
        val isExcluded = backupValues?.get(backupKey) as? NSNumber
        assertEquals(true, isExcluded?.boolValue)
    }

    private inline fun withTemporaryApplicationSupport(block: (NSURL) -> Unit) {
        val rootPath = "${NSTemporaryDirectory()}kwabor-observability-policy-${NSUUID().UUIDString}"
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

private class FakeIosObservedAppSessionFile(
    initialValue: String? = null,
    private val replacementSucceeds: Boolean = true,
    private val removalSucceeds: Boolean = true,
) : IosObservedAppSessionFile {
    private var value = initialValue

    override fun read(): IosObservedAppSessionFileRead = value
        ?.let(IosObservedAppSessionFileRead::Value)
        ?: IosObservedAppSessionFileRead.Missing

    override fun replaceAtomically(value: String): Boolean {
        if (!replacementSucceeds) return false
        this.value = value
        return true
    }

    override fun removeDurably(): Boolean {
        if (!removalSucceeds) return false
        value = null
        return true
    }
}

private data object FailingIosObservedAppSessionFile : IosObservedAppSessionFile {
    override fun read(): IosObservedAppSessionFileRead = IosObservedAppSessionFileRead.Failure

    override fun replaceAtomically(value: String): Boolean = false

    override fun removeDurably(): Boolean = false
}

private data object ThrowingIosObservedAppSessionFile : IosObservedAppSessionFile {
    override fun read(): IosObservedAppSessionFileRead = throw IOException("expected test failure")

    override fun replaceAtomically(value: String): Boolean = false

    override fun removeDurably(): Boolean = false
}

private const val OBSERVED_APP_SESSION_DIRECTORY_FOR_TEST = "KwaborObservability"
private const val OBSERVED_APP_SESSION_FILE_FOR_TEST = "observed-session-v1"
