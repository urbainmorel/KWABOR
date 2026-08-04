package com.kwabor.shared.data.local

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.IOException

private const val ANDROID_ROOM_DIRECTORY_NAME = "KwaborRoom"
private const val ANDROID_ROOM_STORAGE_LOG_TAG = "KwaborRoomStorage"
private val ANDROID_DATABASE_FILE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

internal fun createAndroidKwaborDatabaseBuilder(context: Context): RoomDatabase.Builder<KwaborDatabase> {
    val applicationContext = context.applicationContext
    val databasePath = prepareAndroidRoomDatabasePath(applicationContext)
    if (databasePath == null) {
        Log.e(
            ANDROID_ROOM_STORAGE_LOG_TAG,
            "Local persistence policy is unavailable; using memory-only storage.",
        )
        return Room.inMemoryDatabaseBuilder<KwaborDatabase>(
            context = applicationContext,
            factory = KwaborDatabaseConstructor::initialize,
        )
    }
    return Room.databaseBuilder<KwaborDatabase>(
        context = applicationContext,
        name = databasePath,
        factory = KwaborDatabaseConstructor::initialize,
    )
}

internal fun prepareAndroidRoomDatabasePath(context: Context): String? = try {
    val legacyFilesRemoved = removeLegacyAndroidDatabaseFiles(context)
    val noBackupRoot = context.noBackupFilesDir.canonicalFile
    val roomDirectory = File(noBackupRoot, ANDROID_ROOM_DIRECTORY_NAME).canonicalFile
    if (!legacyFilesRemoved) {
        null
    } else if (roomDirectory.parentFile != noBackupRoot) {
        null
    } else if (!ensureAndroidRoomDirectory(roomDirectory)) {
        null
    } else {
        File(roomDirectory, KWABOR_DATABASE_FILENAME).absolutePath
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private fun ensureAndroidRoomDirectory(roomDirectory: File): Boolean = when {
    roomDirectory.isDirectory -> true
    roomDirectory.exists() -> false
    else -> roomDirectory.mkdirs()
}

private fun removeLegacyAndroidDatabaseFiles(context: Context): Boolean {
    val legacyDatabasePath = context.getDatabasePath(KWABOR_DATABASE_FILENAME).absolutePath
    var allFilesRemoved = true
    ANDROID_DATABASE_FILE_SUFFIXES.forEach { suffix ->
        val legacyFile = File("$legacyDatabasePath$suffix")
        val legacyFileRemoved = try {
            !legacyFile.exists() || legacyFile.delete()
        } catch (_: SecurityException) {
            false
        }
        if (!legacyFileRemoved) {
            allFilesRemoved = false
        }
    }
    return allFilesRemoved
}
