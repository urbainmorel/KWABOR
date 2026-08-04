package com.kwabor.shared.data.preferences

import android.content.Context
import androidx.datastore.core.Storage
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path.Companion.toPath

internal fun createAndroidAppPreferencesStorage(context: Context): Storage<Preferences> {
    val applicationContext = context.applicationContext
    return OkioStorage(
        fileSystem = FileSystem.SYSTEM,
        serializer = PreferencesSerializer,
        producePath = {
            applicationContext.filesDir.resolve(APP_PREFERENCES_FILE_NAME).absolutePath.toPath()
        },
    )
}
