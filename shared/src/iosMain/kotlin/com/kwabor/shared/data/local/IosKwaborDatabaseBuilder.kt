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
    return Room.databaseBuilder<KwaborDatabase>(
        name = "$applicationSupportPath/$KWABOR_DATABASE_FILENAME",
        factory = KwaborDatabaseConstructor::initialize,
    )
}
