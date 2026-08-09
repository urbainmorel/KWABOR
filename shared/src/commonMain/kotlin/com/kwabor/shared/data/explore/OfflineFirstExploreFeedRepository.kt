package com.kwabor.shared.data.explore

import androidx.sqlite.SQLiteException
import com.kwabor.shared.data.local.ExploreCacheSnapshot
import com.kwabor.shared.data.local.ExplorePersistenceWriteResult
import com.kwabor.shared.data.local.ExploreReferenceSnapshot
import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.explore.ExploreCatalogPage
import com.kwabor.shared.domain.explore.ExploreCatalogRepository
import com.kwabor.shared.domain.explore.ExploreCatalogRequest
import com.kwabor.shared.domain.explore.ExploreFeedCacheOperation
import com.kwabor.shared.domain.explore.ExploreFeedQuery
import com.kwabor.shared.domain.explore.ExploreFeedRepository
import com.kwabor.shared.domain.explore.ExploreFeedSnapshot
import com.kwabor.shared.domain.explore.ExploreFeedSource
import com.kwabor.shared.domain.explore.ExploreFeedWarning
import com.kwabor.shared.domain.explore.ExploreSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

internal class OfflineFirstExploreFeedRepository(
    private val catalogRepository: CatalogRepository,
    private val exploreCatalogRepository: ExploreCatalogRepository,
    private val cache: ExploreFeedCacheDependencies,
    clockProvider: ClockProvider,
    singleFlightScope: CoroutineScope,
) : ExploreFeedRepository {
    private val refreshSingleFlight = ExploreFeedSingleFlight<ExploreFeedSnapshot>(singleFlightScope)
    private val appendPageSingleFlight = ExploreFeedSingleFlight<ExploreCatalogPage>(singleFlightScope)
    private val requestCoordinator = ExploreRequestCoordinator(clockProvider, cache.watermarkProvider)

    override suspend fun readCached(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot?> {
        return when (val current = readCached(query, query.toCacheKey())) {
            is DomainResult.Success -> current.value?.let { DomainResult.Success(it) }
                ?: readCached(query, query.toLegacyCacheKey())
            is DomainResult.Failure -> current
        }
    }

    private suspend fun readCached(query: ExploreFeedQuery, cacheKey: String): DomainResult<ExploreFeedSnapshot?> {
        val availableCache = cache.wall ?: return localStorageFailure()
        return when (val wallResult = readLocal { availableCache.read(cacheKey) }) {
            is DomainResult.Success -> wallResult.value?.let { wall ->
                readCachedReferences(query, cacheKey, wall)
            }
                ?: DomainResult.Success(null)
            is DomainResult.Failure -> wallResult
        }
    }

    private suspend fun readCachedReferences(
        query: ExploreFeedQuery,
        cacheKey: String,
        wall: ExploreCacheSnapshot,
    ): DomainResult<ExploreFeedSnapshot?> {
        val availableCache = cache.references ?: return localStorageFailure()
        return when (val referencesResult = readLocal(availableCache::read)) {
            is DomainResult.Success -> referencesResult.value?.let { references ->
                if (query.acceptsItemsWithKnownReferences(wall.items, references.cities, references.categories)) {
                    DomainResult.Success(wall.toDomain(references))
                } else {
                    clearCachedWall(cacheKey, wall.cachedAtEpochMilliseconds)
                }
            } ?: DomainResult.Success(null)
            is DomainResult.Failure -> referencesResult
        }
    }

    private suspend fun clearCachedWall(
        cacheKey: String,
        expectedCachedAtEpochMilliseconds: Long,
    ): DomainResult<ExploreFeedSnapshot?> {
        val availableCache = cache.wall ?: return localStorageFailure()
        return when (
            val clearResult = readLocal {
                availableCache.clear(cacheKey, expectedCachedAtEpochMilliseconds)
            }
        ) {
            is DomainResult.Success -> DomainResult.Success(null)
            is DomainResult.Failure -> clearResult
        }
    }

    override suspend fun refresh(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot> =
        refreshSingleFlight.execute(query.toRefreshSingleFlightKey()) {
            refreshOnce(query)
        }

    private suspend fun refreshOnce(query: ExploreFeedQuery): DomainResult<ExploreFeedSnapshot> {
        val cacheKey = query.toCacheKey()
        val reservation = requestCoordinator.reserveRefresh(cacheKey)
            ?: return invalidPayloadFailure()
        val remoteSnapshot = when (
            val result = requestCoordinator.runReservedRequest(reservation) {
                loadRemoteSnapshot(query, reservation.requestTimestamp)
            }
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        return requestCoordinator.completeIfLatest(reservation) {
            when (
                val persistence = persistRefresh(
                    cacheKey = cacheKey,
                    snapshot = remoteSnapshot,
                    retainedFailures = watermarkFailureOperations(reservation.watermarkReadUnavailable),
                )
            ) {
                is DomainResult.Success -> DomainResult.Success(remoteSnapshot.copy(warning = persistence.value))
                is DomainResult.Failure -> persistence
            }
        } ?: revalidationRequiredFailure()
    }

    override suspend fun append(
        query: ExploreFeedQuery,
        currentSnapshot: ExploreFeedSnapshot,
    ): DomainResult<ExploreFeedSnapshot> {
        if (currentSnapshot.source != ExploreFeedSource.Network) {
            return DomainResult.Failure(DomainError.Validation(EXPLORE_REVALIDATION_REQUIRED_ERROR_KEY))
        }
        val cursor = currentSnapshot.nextCursor
            ?: return DomainResult.Failure(DomainError.Validation(EXPLORE_NO_NEXT_PAGE_ERROR_KEY))
        return appendOnce(query, currentSnapshot, cursor)
    }

    private suspend fun appendOnce(
        query: ExploreFeedQuery,
        currentSnapshot: ExploreFeedSnapshot,
        cursor: String,
    ): DomainResult<ExploreFeedSnapshot> {
        val cacheKey = query.toCacheKey()
        val reservation = requestCoordinator.reserveAppend(
            cacheKey = cacheKey,
            baseSnapshotTimestamp = currentSnapshot.cachedAtEpochMilliseconds,
        ) ?: return revalidationRequiredFailure()
        val page = when (
            val result = requestCoordinator.runReservedRequest(reservation) {
                appendPageSingleFlight.execute(query.toAppendSingleFlightKey(cursor)) {
                    exploreCatalogRepository.listCatalog(query.toCatalogRequest(cursor))
                }
            }
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        val isProgressivePage = page.isProgressiveAfter(cursor, query, currentSnapshot)
        if (!isProgressivePage) {
            requestCoordinator.rollback(reservation)
            return DomainResult.Failure(DomainError.Unexpected(EXPLORE_INVALID_PAGE_ERROR_KEY))
        }
        val updatedSnapshot = currentSnapshot.append(page, reservation.requestTimestamp)
        return requestCoordinator.completeIfLatest(reservation) {
            when (
                val persistence = persistSafeAppend(
                    cacheKey = cacheKey,
                    currentSnapshot = currentSnapshot,
                    updatedSnapshot = updatedSnapshot,
                    watermarkReadUnavailable = reservation.watermarkReadUnavailable,
                )
            ) {
                is DomainResult.Success -> DomainResult.Success(updatedSnapshot.copy(warning = persistence.value))
                is DomainResult.Failure -> persistence
            }
        } ?: revalidationRequiredFailure()
    }

    private suspend fun loadRemoteSnapshot(
        query: ExploreFeedQuery,
        requestTimestamp: Long,
    ): DomainResult<ExploreFeedSnapshot> {
        val references = when (val result = loadRemoteReferences()) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        query.invalidReferenceFilterOrNull(references.cities, references.categories)?.let { error ->
            return DomainResult.Failure(error)
        }
        val page = when (val result = loadFirstRemotePage(query, references)) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        return references.toNetworkSnapshot(page, requestTimestamp)
    }

    private suspend fun loadRemoteReferences(): DomainResult<RemoteExploreReferences> {
        val cities = when (val result = catalogRepository.listCities()) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        val categories = when (val result = catalogRepository.listCategories()) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        if (!cities.areCitiesValidForExploreCache() || !categories.areCategoriesValidForExploreCache()) {
            return DomainResult.Failure(DomainError.Unexpected(EXPLORE_INVALID_PAYLOAD_ERROR_KEY))
        }
        return DomainResult.Success(RemoteExploreReferences(cities, categories))
    }

    private suspend fun loadFirstRemotePage(
        query: ExploreFeedQuery,
        references: RemoteExploreReferences,
    ): DomainResult<ExploreCatalogPage> {
        val page = when (
            val result = exploreCatalogRepository.listCatalog(query.toCatalogRequest(cursor = null))
        ) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> return result
        }
        if (!page.isValidFirstPage(query, references.cities, references.categories)) {
            return DomainResult.Failure(DomainError.Unexpected(EXPLORE_INVALID_PAGE_ERROR_KEY))
        }
        return DomainResult.Success(page)
    }

    private fun RemoteExploreReferences.toNetworkSnapshot(
        page: ExploreCatalogPage,
        requestTimestamp: Long,
    ): DomainResult<ExploreFeedSnapshot> = DomainResult.Success(
        ExploreFeedSnapshot(
            cities = cities,
            categories = categories,
            items = page.items.distinctBy(ListingSummary::id),
            nextCursor = page.nextCursor,
            cachedAtEpochMilliseconds = requestTimestamp,
            source = ExploreFeedSource.Network,
            serverSnapshotAtEpochMicroseconds = page.snapshotAtEpochMicroseconds,
        ),
    )

    private suspend fun persistRefresh(
        cacheKey: String,
        snapshot: ExploreFeedSnapshot,
        retainedFailures: Set<ExploreFeedCacheOperation>,
    ): DomainResult<ExploreFeedWarning?> {
        return when (
            cache.persistence.writeFeed(
                wall = snapshot.toCacheSnapshot(cacheKey),
                references = snapshot.toReferenceSnapshot(),
            )
        ) {
            LocalWriteResult.Success -> DomainResult.Success(retainedFailures.toWarningOrNull())
            LocalWriteResult.StorageUnavailable -> DomainResult.Success(
                (
                    retainedFailures +
                        setOf(
                            ExploreFeedCacheOperation.WriteWall,
                            ExploreFeedCacheOperation.WriteReferences,
                        )
                    ).toWarningOrNull(),
            )
            LocalWriteResult.InvalidPayload -> invalidPayloadFailure()
            LocalWriteResult.Rejected -> revalidationRequiredFailure()
        }
    }

    private suspend fun persistSafeAppend(
        cacheKey: String,
        currentSnapshot: ExploreFeedSnapshot,
        updatedSnapshot: ExploreFeedSnapshot,
        watermarkReadUnavailable: Boolean,
    ): DomainResult<ExploreFeedWarning?> {
        val pendingFailures = currentSnapshot.persistenceFailureOperations() +
            watermarkFailureOperations(watermarkReadUnavailable)
        val retainedFailures = pendingFailures.filterTo(mutableSetOf()) { operation ->
            operation == ExploreFeedCacheOperation.ReadWatermark
        }
        val safeSnapshot = when {
            updatedSnapshot.items.size <= MAX_PERSISTED_EXPLORE_FEED_ITEMS -> updatedSnapshot
            currentSnapshot.items.size <= MAX_PERSISTED_EXPLORE_FEED_ITEMS -> currentSnapshot
            else -> return DomainResult.Success(pendingFailures.toWarningOrNull())
        }
        if (ExploreFeedCacheOperation.WriteReferences in pendingFailures) {
            return persistRefresh(
                cacheKey = cacheKey,
                snapshot = safeSnapshot,
                retainedFailures = retainedFailures,
            )
        }
        if (
            safeSnapshot === currentSnapshot &&
            ExploreFeedCacheOperation.WriteWall !in pendingFailures
        ) {
            return DomainResult.Success(retainedFailures.toWarningOrNull())
        }
        return when (cache.wall.writeWall(safeSnapshot.toCacheSnapshot(cacheKey))) {
            LocalWriteResult.Success -> DomainResult.Success(retainedFailures.toWarningOrNull())
            LocalWriteResult.StorageUnavailable -> DomainResult.Success(
                (retainedFailures + ExploreFeedCacheOperation.WriteWall).toWarningOrNull(),
            )
            LocalWriteResult.InvalidPayload ->
                DomainResult.Failure(DomainError.Unexpected(EXPLORE_INVALID_PAYLOAD_ERROR_KEY))
            LocalWriteResult.Rejected -> revalidationRequiredFailure()
        }
    }
}

private fun watermarkFailureOperations(isUnavailable: Boolean): Set<ExploreFeedCacheOperation> = if (isUnavailable) {
    setOf(ExploreFeedCacheOperation.ReadWatermark)
} else {
    emptySet()
}

private fun ExploreFeedSnapshot.persistenceFailureOperations(): Set<ExploreFeedCacheOperation> =
    (warning as? ExploreFeedWarning.LocalPersistenceUnavailable)?.failedOperations.orEmpty()

private fun ExploreCatalogPage.isValidFirstPage(
    query: ExploreFeedQuery,
    cities: List<City>,
    categories: List<Category>,
): Boolean = toListingSummaryPage().isValidFirstPage(query, cities, categories) &&
    items.hasValidSponsorPlacementOrder()

private fun ExploreCatalogPage.isProgressiveAfter(
    cursor: String,
    query: ExploreFeedQuery,
    currentSnapshot: ExploreFeedSnapshot,
): Boolean = toListingSummaryPage().isProgressiveAfter(
    cursor = cursor,
    query = query,
    cities = currentSnapshot.cities,
    categories = currentSnapshot.categories,
    existingListingIds = currentSnapshot.items.mapTo(mutableSetOf(), ListingSummary::id),
) && (
    items.isEmpty() || snapshotAtEpochMicroseconds == currentSnapshot.serverSnapshotAtEpochMicroseconds
    ) && (currentSnapshot.items + items).hasValidSponsorPlacementOrder()

private fun List<ListingSummary>.hasValidSponsorPlacementOrder(): Boolean {
    var sponsorCount = 0
    var organicSeen = false
    forEach { listing ->
        if (listing.isSponsoredPlacement == true) {
            sponsorCount += 1
            if (organicSeen || sponsorCount > MAX_EXPLORE_SPONSORED_PLACEMENTS) {
                return false
            }
        } else {
            organicSeen = true
        }
    }
    return true
}

private fun Set<ExploreFeedCacheOperation>.toWarningOrNull(): ExploreFeedWarning? =
    takeIf { operations -> operations.isNotEmpty() }
        ?.let { operations -> ExploreFeedWarning.LocalPersistenceUnavailable(operations) }

private suspend fun ExploreWallCache?.writeWall(snapshot: ExploreCacheSnapshot): LocalWriteResult {
    val availableCache = this ?: return LocalWriteResult.StorageUnavailable
    return writeLocal { availableCache.replace(snapshot) }
}

private suspend fun ExploreFeedPersistenceCache?.writeFeed(
    wall: ExploreCacheSnapshot,
    references: ExploreReferenceSnapshot,
): LocalWriteResult {
    val availableCache = this ?: return LocalWriteResult.StorageUnavailable
    return writeLocal { availableCache.replace(wall, references) }
}

private suspend fun <T> ExploreRequestCoordinator.runReservedRequest(
    reservation: ExploreRequestReservation,
    block: suspend () -> DomainResult<T>,
): DomainResult<T> {
    var result: DomainResult<T>? = null
    try {
        return block().also { completed -> result = completed }
    } finally {
        if (result == null || result is DomainResult.Failure) {
            rollback(reservation)
        }
    }
}

private fun ExploreFeedSnapshot.append(page: ExploreCatalogPage, nowEpochMilliseconds: Long): ExploreFeedSnapshot =
    copy(
        items = items + page.items,
        nextCursor = page.nextCursor,
        cachedAtEpochMilliseconds = nowEpochMilliseconds,
        source = ExploreFeedSource.Network,
        warning = null,
        itemContentCapturedAtEpochMilliseconds = items.associate { listing ->
            listing.id to (itemContentCapturedAtEpochMilliseconds[listing.id] ?: cachedAtEpochMilliseconds)
        },
    )

private fun ExploreCatalogPage.toListingSummaryPage(): ListingSummaryPage = ListingSummaryPage(
    items = items,
    nextCursor = nextCursor,
)

private fun ExploreFeedQuery.toCatalogRequest(cursor: String?): ExploreCatalogRequest {
    val listingType = requireNotNull(filters.listingType)
    return ExploreCatalogRequest(
        listingType = listingType,
        cityId = filters.cityId,
        categoryId = filters.categoryId,
        listingClass = filters.listingClass,
        sort = when (listingType) {
            ListingType.Event -> ExploreSort.TemporalProximity
            ListingType.Place,
            ListingType.Establishment,
            -> ExploreSort.Popularity
        },
        cursor = cursor,
        limit = pageSize,
    )
}

private suspend fun <T> readLocal(block: suspend () -> T): DomainResult<T> = try {
    DomainResult.Success(block())
} catch (exception: CancellationException) {
    throw exception
} catch (_: SQLiteException) {
    localStorageFailure()
}

private suspend fun writeLocal(block: suspend () -> ExplorePersistenceWriteResult): LocalWriteResult = try {
    when (block()) {
        ExplorePersistenceWriteResult.Applied -> LocalWriteResult.Success
        ExplorePersistenceWriteResult.Rejected -> LocalWriteResult.Rejected
    }
} catch (exception: CancellationException) {
    throw exception
} catch (_: SQLiteException) {
    LocalWriteResult.StorageUnavailable
} catch (_: IllegalArgumentException) {
    LocalWriteResult.InvalidPayload
}

private fun <T> localStorageFailure(): DomainResult<T> =
    DomainResult.Failure(DomainError.LocalStorageUnavailable(EXPLORE_STORAGE_ERROR_KEY))

private fun <T> invalidPayloadFailure(): DomainResult<T> =
    DomainResult.Failure(DomainError.Unexpected(EXPLORE_INVALID_PAYLOAD_ERROR_KEY))

private fun <T> revalidationRequiredFailure(): DomainResult<T> =
    DomainResult.Failure(DomainError.Validation(EXPLORE_REVALIDATION_REQUIRED_ERROR_KEY))

private sealed interface LocalWriteResult {
    data object Success : LocalWriteResult

    data object StorageUnavailable : LocalWriteResult

    data object InvalidPayload : LocalWriteResult

    data object Rejected : LocalWriteResult
}

private data class RemoteExploreReferences(
    val cities: List<City>,
    val categories: List<Category>,
)

private const val MAX_PERSISTED_EXPLORE_FEED_ITEMS = 40
private const val MAX_EXPLORE_SPONSORED_PLACEMENTS = 2
private const val EXPLORE_STORAGE_ERROR_KEY = "error.explore.storage_unavailable"
private const val EXPLORE_REVALIDATION_REQUIRED_ERROR_KEY = "error.explore.revalidation_required"
private const val EXPLORE_NO_NEXT_PAGE_ERROR_KEY = "error.explore.no_next_page"
private const val EXPLORE_INVALID_PAGE_ERROR_KEY = "error.explore.invalid_page"
private const val EXPLORE_INVALID_PAYLOAD_ERROR_KEY = "error.explore.invalid_payload"
