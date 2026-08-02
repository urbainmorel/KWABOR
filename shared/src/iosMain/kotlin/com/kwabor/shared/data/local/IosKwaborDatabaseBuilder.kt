package com.kwabor.shared.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal fun createIosKwaborDatabaseBuilder(): RoomDatabase.Builder<KwaborDatabase> {
    val applicationSupportUrl = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val applicationSupportPath = checkNotNull(applicationSupportUrl?.path) {
        "The iOS application support directory is unavailable."
    }
    return createIosKwaborDatabaseBuilder(
        databasePath = "$applicationSupportPath/$KWABOR_DATABASE_FILENAME",
    )
}

internal fun createIosKwaborDatabaseBuilder(databasePath: String): RoomDatabase.Builder<KwaborDatabase> {
    require(databasePath.isNotBlank()) { "The iOS database path must not be blank." }
    return Room.databaseBuilder<KwaborDatabase>(
        name = databasePath,
        factory = KwaborDatabaseConstructor::initialize,
    )
}
