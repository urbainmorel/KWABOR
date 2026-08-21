package com.kwabor.shared.app

import android.content.Context
import com.kwabor.shared.data.auth.createAndroidSecureAuthSessionManager
import com.kwabor.shared.data.local.createAndroidKwaborDatabaseBuilder
import com.kwabor.shared.data.observability.createAndroidObservedAppSessionStore
import com.kwabor.shared.data.observability.createAndroidObservedAppSessionTimeSource
import com.kwabor.shared.data.preferences.createAndroidAppPreferencesStorage

fun createAndroidKwaborCompositionRootOrNull(
    context: Context,
    environmentName: String?,
    supabaseUrl: String?,
    supabasePublishableKey: String?,
): KwaborCompositionRoot? {
    val applicationContext = context.applicationContext
    return createKwaborCompositionRootOrNull(
        supabaseUrl = supabaseUrl,
        supabasePublishableKey = supabasePublishableKey,
        environmentName = environmentName,
        authSessionManager = createAndroidSecureAuthSessionManager(applicationContext),
        persistenceConfigurationProvider = {
            KwaborPersistenceConfiguration(
                databaseBuilderFactory = { createAndroidKwaborDatabaseBuilder(applicationContext) },
                preferencesStorageFactory = { createAndroidAppPreferencesStorage(applicationContext) },
                observedAppSessionStore = createAndroidObservedAppSessionStore(applicationContext),
                observedAppSessionTimeSource = createAndroidObservedAppSessionTimeSource(applicationContext),
            )
        },
    )
}
