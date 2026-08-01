package com.kwabor.shared.app

import androidx.datastore.core.DataStore
import androidx.datastore.core.Storage
import androidx.datastore.preferences.core.Preferences
import androidx.room.RoomDatabase
import com.kwabor.shared.data.local.ExploreCacheStore
import com.kwabor.shared.data.local.ExploreFeedPersistenceStore
import com.kwabor.shared.data.local.ExplorePersistenceWatermarkStore
import com.kwabor.shared.data.local.ExploreReferenceStore
import com.kwabor.shared.data.local.KwaborDatabase
import com.kwabor.shared.data.local.buildKwaborDatabase
import com.kwabor.shared.data.preferences.DataStoreAppPreferencesRepository
import com.kwabor.shared.data.preferences.createAppPreferencesDataStore
import com.kwabor.shared.domain.preferences.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

internal class KwaborPersistenceConfiguration(
    val databaseBuilderFactory: () -> RoomDatabase.Builder<KwaborDatabase>,
    val preferencesStorageFactory: () -> Storage<Preferences>,
)

private val appPreferencesDataStoreScopeQualifier = named("app-preferences-data-store-scope")

internal fun persistenceModule(configuration: KwaborPersistenceConfiguration): Module = module {
    single<RoomDatabase.Builder<KwaborDatabase>> { configuration.databaseBuilderFactory() }
    single<Storage<Preferences>> { configuration.preferencesStorageFactory() }

    single<KwaborDatabase> {
        buildKwaborDatabase(
            builder = get(),
            queryCoroutineContext = get<DispatcherProvider>().io,
        )
    } onClose { database -> database?.close() }

    registerExplorePersistenceStores()

    single<CoroutineScope>(qualifier = appPreferencesDataStoreScopeQualifier) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().io)
    } onClose { scope -> scope?.cancel() }

    single<DataStore<Preferences>> {
        createAppPreferencesDataStore(
            storage = get(),
            coroutineScope = get(qualifier = appPreferencesDataStoreScopeQualifier),
        )
    }

    single<AppPreferencesRepository> {
        DataStoreAppPreferencesRepository(dataStore = get())
    }
}

private fun Module.registerExplorePersistenceStores() {
    single {
        ExploreCacheStore(
            dao = get<KwaborDatabase>().exploreCacheDao(),
        )
    }
    single {
        ExploreReferenceStore(
            dao = get<KwaborDatabase>().exploreReferenceDao(),
        )
    }
    single {
        ExploreFeedPersistenceStore(
            dao = get<KwaborDatabase>().exploreFeedPersistenceDao(),
        )
    }
    single {
        ExplorePersistenceWatermarkStore(
            dao = get<KwaborDatabase>().explorePersistenceWatermarkDao(),
        )
    }
}
