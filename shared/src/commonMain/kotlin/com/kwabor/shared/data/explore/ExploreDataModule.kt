package com.kwabor.shared.data.explore

import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.data.local.ExploreCacheStore
import com.kwabor.shared.data.local.ExploreFeedPersistenceStore
import com.kwabor.shared.data.local.ExplorePersistenceWatermarkStore
import com.kwabor.shared.data.local.ExploreReferenceStore
import com.kwabor.shared.domain.explore.ExploreFeedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.dsl.onClose

private val exploreSingleFlightScopeQualifier = named("explore-single-flight-scope")

internal fun exploreDataModule(hasPersistence: Boolean): Module = module {
    single<CoroutineScope>(qualifier = exploreSingleFlightScopeQualifier) {
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().io)
    } onClose { scope -> scope?.cancel() }

    single<ExploreFeedRepository> {
        OfflineFirstExploreFeedRepository(
            catalogRepository = get(),
            cache = exploreFeedCacheDependencies(hasPersistence),
            clockProvider = get(),
            singleFlightScope = get(qualifier = exploreSingleFlightScopeQualifier),
        )
    }
}

private fun Scope.exploreFeedCacheDependencies(hasPersistence: Boolean): ExploreFeedCacheDependencies =
    if (hasPersistence) {
        ExploreFeedCacheDependencies(
            wall = StoredExploreWallCache(lazy { get<ExploreCacheStore>() }),
            references = StoredExploreReferenceCache(lazy { get<ExploreReferenceStore>() }),
            persistence = StoredExploreFeedPersistenceCache(lazy { get<ExploreFeedPersistenceStore>() }),
            watermarkProvider = StoredExplorePersistenceWatermarkProvider(
                lazy { get<ExplorePersistenceWatermarkStore>() },
            ),
        )
    } else {
        ExploreFeedCacheDependencies()
    }
