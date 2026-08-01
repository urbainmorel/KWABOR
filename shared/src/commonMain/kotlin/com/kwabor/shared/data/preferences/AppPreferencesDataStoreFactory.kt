package com.kwabor.shared.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Storage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope

internal const val APP_PREFERENCES_FILE_NAME = "kwabor.preferences_pb"

internal fun createAppPreferencesDataStore(
    storage: Storage<Preferences>,
    coroutineScope: CoroutineScope,
): DataStore<Preferences> = DataStoreFactory.create(
    storage = storage,
    corruptionHandler = ReplaceFileCorruptionHandler(produceNewData = { emptyPreferences() }),
    scope = coroutineScope,
)
