package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreCatalogPage
import com.kwabor.shared.domain.explore.ExploreCatalogRepository
import com.kwabor.shared.domain.explore.ExploreCatalogRequest

class DataExploreCatalogRepository internal constructor(
    private val dataSource: ExploreCatalogDataSource,
) : ExploreCatalogRepository {
    override suspend fun listCatalog(request: ExploreCatalogRequest): DomainResult<ExploreCatalogPage> = try {
        DomainResult.Success(dataSource.listCatalog(request).toDomain())
    } catch (exception: ExploreCatalogDataException) {
        DomainResult.Failure(exception.domainError)
    }
}
