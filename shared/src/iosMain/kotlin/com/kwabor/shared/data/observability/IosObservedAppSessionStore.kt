package com.kwabor.shared.data.observability

import com.kwabor.shared.domain.observability.ObservedAppSessionCheckpointRead
import com.kwabor.shared.domain.observability.ObservedAppSessionStore
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeMark
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal fun createIosObservedAppSessionStore(): ObservedAppSessionStore {
    val fileManager = NSFileManager.defaultManager
    val applicationSupportUrl = fileManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val checkpointFile = applicationSupportUrl?.path
        ?.let(::prepareObservedAppSessionFile)
        ?: UnavailableIosObservedAppSessionFile
    return IosObservedAppSessionStore(checkpointFile)
}

internal class IosObservedAppSessionStore(
    private val checkpointFile: IosObservedAppSessionFile,
) : ObservedAppSessionStore {
    override fun read(): ObservedAppSessionCheckpointRead = try {
        when (val fileRead = checkpointFile.read()) {
            IosObservedAppSessionFileRead.Missing -> ObservedAppSessionCheckpointRead.Missing
            IosObservedAppSessionFileRead.Failure -> ObservedAppSessionCheckpointRead.Failure
            is IosObservedAppSessionFileRead.Value -> ObservedAppSessionCheckpointCodec.decode(fileRead.value)
        }
    } catch (_: IOException) {
        ObservedAppSessionCheckpointRead.Failure
    }

    override fun writeForeground(): Boolean = checkpointFile.replaceAtomically(
        ObservedAppSessionCheckpointCodec.encodeForeground(),
    )

    override fun writeBackgroundedAt(timeMark: ObservedAppSessionTimeMark): Boolean = checkpointFile.replaceAtomically(
        ObservedAppSessionCheckpointCodec.encodeBackgroundedAt(timeMark),
    )

    override fun clear(): Boolean = checkpointFile.removeDurably()
}

internal sealed interface IosObservedAppSessionFileRead {
    data object Missing : IosObservedAppSessionFileRead

    data object Failure : IosObservedAppSessionFileRead

    data class Value(val value: String) : IosObservedAppSessionFileRead
}

internal interface IosObservedAppSessionFile {
    fun read(): IosObservedAppSessionFileRead

    fun replaceAtomically(value: String): Boolean

    fun removeDurably(): Boolean
}

private class FoundationIosObservedAppSessionFile(
    private val path: Path,
    private val fileSystem: FileSystem,
) : IosObservedAppSessionFile {
    override fun read(): IosObservedAppSessionFileRead = try {
        if (!fileSystem.exists(path)) {
            IosObservedAppSessionFileRead.Missing
        } else {
            val size = fileSystem.metadata(path).size
            if (size == null || size > MAXIMUM_CHECKPOINT_BYTES) {
                IosObservedAppSessionFileRead.Failure
            } else {
                IosObservedAppSessionFileRead.Value(fileSystem.read(path) { readUtf8() })
            }
        }
    } catch (_: IOException) {
        IosObservedAppSessionFileRead.Failure
    }

    override fun replaceAtomically(value: String): Boolean {
        if (value.encodeToByteArray().size.toLong() > MAXIMUM_CHECKPOINT_BYTES) return false
        val temporaryPath = temporaryPath()
        return try {
            fileSystem.write(temporaryPath) { writeUtf8(value) }
            fileSystem.atomicMove(temporaryPath, path)
            true
        } catch (_: IOException) {
            false
        }
    }

    override fun removeDurably(): Boolean {
        val temporaryPath = temporaryPath()
        return try {
            fileSystem.delete(path, mustExist = false)
            fileSystem.delete(temporaryPath, mustExist = false)
            !fileSystem.exists(path) && !fileSystem.exists(temporaryPath)
        } catch (_: IOException) {
            false
        }
    }

    private fun temporaryPath(): Path = "$path.tmp".toPath()
}

private data object UnavailableIosObservedAppSessionFile : IosObservedAppSessionFile {
    override fun read(): IosObservedAppSessionFileRead = IosObservedAppSessionFileRead.Failure

    override fun replaceAtomically(value: String): Boolean = false

    override fun removeDurably(): Boolean = false
}

private fun prepareObservedAppSessionFile(applicationSupportPath: String): IosObservedAppSessionFile? {
    val fileSystem = FileSystem.SYSTEM
    val directoryPath = "$applicationSupportPath/$OBSERVED_APP_SESSION_DIRECTORY".toPath()
    return try {
        fileSystem.createDirectories(directoryPath)
        FoundationIosObservedAppSessionFile(
            path = "$directoryPath/$OBSERVED_APP_SESSION_FILE".toPath(),
            fileSystem = fileSystem,
        )
    } catch (_: IOException) {
        null
    }
}

private const val OBSERVED_APP_SESSION_DIRECTORY = "KwaborObservability"
private const val OBSERVED_APP_SESSION_FILE = "observed-session-v1"
private const val MAXIMUM_CHECKPOINT_BYTES = 512L
