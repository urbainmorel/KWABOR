package com.kwabor.shared.data.search

import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.data.local.SearchCacheStore
import com.kwabor.shared.domain.search.SearchRepository
import org.koin.core.module.Module
import org.koin.dsl.module

internal fun searchDataModule(hasPersistence: Boolean): Module = module {
    single<SearchRepository> {
        OfflineFirstSearchRepository(
            catalogRepository = get(),
            localCache = if (hasPersistence) {
                StoredSearchLocalCache(lazy { get<SearchCacheStore>() })
            } else {
                null
            },
            localSearchDispatcher = get<DispatcherProvider>().default,
        )
    }
}
