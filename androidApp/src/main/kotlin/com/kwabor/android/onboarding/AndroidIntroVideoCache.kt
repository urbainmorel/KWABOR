package com.kwabor.android.onboarding

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.observability.RemoteIntroVideo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal interface IntroVideoCache {
    suspend fun resolve(source: RemoteIntroVideo): File?

    suspend fun clear()
}

internal class AndroidIntroVideoCache(
    context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : IntroVideoCache {
    private val directory = File(context.filesDir, DIRECTORY_NAME)

    override suspend fun resolve(source: RemoteIntroVideo): File? = withContext(dispatcherProvider.io) {
        directory.mkdirs()
        val target = cachedFile(source)
        if (target.isFile && target.hasSha256(source.sha256) && target.isSupportedIntroVideo()) {
            return@withContext target
        }
        target.delete()
        downloadAndValidate(source = source, target = target)
    }

    override suspend fun clear() = withContext(dispatcherProvider.io) {
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.parentFile == directory) {
                file.delete()
            }
        }
    }

    private suspend fun downloadAndValidate(source: RemoteIntroVideo, target: File): File? {
        val temporary = File(directory, "${target.name}.part")
        temporary.delete()
        val connection = openConnection(source.url) ?: return null
        return try {
            connection.connect()
            downloadValidatedResponse(
                connection = connection,
                source = source,
                temporary = temporary,
                target = target,
            )
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private suspend fun downloadValidatedResponse(
        connection: HttpURLConnection,
        source: RemoteIntroVideo,
        temporary: File,
        target: File,
    ): File? {
        if (!connection.isSuccessfulMp4Response()) return null
        val actualSha256 = connection.copyBodyTo(temporary) ?: return null
        currentCoroutineContext().ensureActive()
        val isValid = actualSha256 == source.sha256 && temporary.isSupportedIntroVideo()
        if (!isValid) return null
        publishAtomically(temporary = temporary, target = target)
        removeOldVersions(except = target)
        return target
    }

    private suspend fun HttpURLConnection.copyBodyTo(target: File): String? {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        var totalBytes = 0L
        var withinLimit = true
        inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var count = input.read(buffer)
                while (count >= 0 && withinLimit) {
                    currentCoroutineContext().ensureActive()
                    totalBytes += count
                    withinLimit = writeChunk(output, digest, buffer, count, totalBytes)
                    count = nextCount(input = input, buffer = buffer, shouldContinue = withinLimit)
                }
                output.fd.sync()
            }
        }
        return digest.digest().toHexString().takeIf { withinLimit }
    }

    private fun writeChunk(
        output: FileOutputStream,
        digest: MessageDigest,
        buffer: ByteArray,
        count: Int,
        totalBytes: Long,
    ): Boolean {
        val withinLimit = totalBytes <= MAX_VIDEO_BYTES
        if (withinLimit) {
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        return withinLimit
    }

    private fun nextCount(input: InputStream, buffer: ByteArray, shouldContinue: Boolean): Int =
        if (shouldContinue) input.read(buffer) else END_OF_STREAM

    private fun openConnection(url: String): HttpURLConnection? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return null
        (uri.toURL().openConnection() as? HttpURLConnection)?.apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            useCaches = false
        }
    }.getOrNull()

    private fun HttpURLConnection.isSuccessfulMp4Response(): Boolean {
        val isHttpsFinalUrl = url.protocol.equals("https", ignoreCase = true)
        val responseMimeType = contentType?.substringBefore(';')?.trim()?.lowercase()
        val isAcceptableSize = contentLengthLong in -1L..MAX_VIDEO_BYTES
        return responseCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX &&
            isHttpsFinalUrl &&
            responseMimeType == MP4_MIME_TYPE &&
            isAcceptableSize
    }

    private fun cachedFile(source: RemoteIntroVideo): File = File(
        directory,
        "intro-${source.revision}-${source.sha256.take(HASH_FILE_PREFIX_LENGTH)}.mp4",
    )

    private fun publishAtomically(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun removeOldVersions(except: File) {
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file != except && file.extension == MP4_EXTENSION) {
                file.delete()
            }
        }
    }
}

private fun File.hasSha256(expectedSha256: String): Boolean {
    val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
    inputStream().use { input ->
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHexString() == expectedSha256
}

private fun File.isSupportedIntroVideo(): Boolean = runCatching {
    val retriever = MediaMetadataRetriever()
    val durationMillis = try {
        retriever.setDataSource(absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } finally {
        retriever.release()
    } ?: return false
    if (durationMillis !in MIN_VIDEO_DURATION_MILLIS..MAX_VIDEO_DURATION_MILLIS) return false

    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(absolutePath)
        (0 until extractor.trackCount).any { index ->
            val format = extractor.getTrackFormat(index)
            val mimeType = format.getString(MediaFormat.KEY_MIME)
            val width = format.getIntegerOrNull(MediaFormat.KEY_WIDTH)
            val height = format.getIntegerOrNull(MediaFormat.KEY_HEIGHT)
            mimeType == H264_MIME_TYPE && width != null && height != null && height > width
        }
    } finally {
        extractor.release()
    }
}.getOrDefault(false)

private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val DIRECTORY_NAME = "intro-media"
private const val MP4_EXTENSION = "mp4"
private const val MP4_MIME_TYPE = "video/mp4"
private const val H264_MIME_TYPE = "video/avc"
private const val SHA256_ALGORITHM = "SHA-256"
private const val HASH_FILE_PREFIX_LENGTH = 12
private const val DOWNLOAD_BUFFER_SIZE = 8_192
private const val MAX_VIDEO_BYTES = 5L * 1_024L * 1_024L
private const val MIN_VIDEO_DURATION_MILLIS = 15_000L
private const val MAX_VIDEO_DURATION_MILLIS = 25_500L
private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 20_000
private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val END_OF_STREAM = -1
