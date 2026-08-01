package com.kwabor.shared.data.preferences

import androidx.datastore.core.Storage
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal fun createIosAppPreferencesStorage(): Storage<Preferences> = OkioStorage(
    fileSystem = FileSystem.SYSTEM,
    serializer = PreferencesSerializer,
    producePath = {
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
        "$applicationSupportPath/$APP_PREFERENCES_FILE_NAME".toPath()
    },
)
