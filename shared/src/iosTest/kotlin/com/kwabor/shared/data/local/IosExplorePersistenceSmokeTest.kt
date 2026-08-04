package com.kwabor.shared.data.local

import com.kwabor.shared.data.preferences.DataStoreAppPreferencesRepository
import com.kwabor.shared.data.preferences.createAppPreferencesDataStore
import com.kwabor.shared.data.preferences.createIosAppPreferencesStorage
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.preferences.AppPreferences
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalForeignApi::class)
class IosExplorePersistenceSmokeTest {
    @Test
    fun exploreCacheReferencesAndPreferencesSurviveReopen() = runTest {
        val filePrefix = "${NSTemporaryDirectory()}kwabor-ios-${NSUUID().UUIDString}"
        val databasePath = "$filePrefix.db"
        val preferencesPath = "$filePrefix.preferences_pb"
        val testCoroutineContext = coroutineContext

        try {
            val expectedWall = wallSnapshot()
            val expectedReferences = referenceSnapshot()

            openDatabase(databasePath, testCoroutineContext).useDatabase { database ->
                ExploreCacheStore(database.exploreCacheDao()).replace(expectedWall)
                ExploreReferenceStore(database.exploreReferenceDao()).replace(expectedReferences)
            }

            openDatabase(databasePath, testCoroutineContext).useDatabase { database ->
                assertEquals(
                    expectedWall,
                    ExploreCacheStore(database.exploreCacheDao()).read(expectedWall.snapshotKey),
                )
                assertEquals(expectedReferences, ExploreReferenceStore(database.exploreReferenceDao()).read())
            }

            writePreferences(preferencesPath, testCoroutineContext)
            assertPreferencesSurviveReopen(preferencesPath, testCoroutineContext)
        } finally {
            removePersistenceFiles(databasePath, preferencesPath)
        }
    }

    private fun openDatabase(path: String, queryCoroutineContext: CoroutineContext): KwaborDatabase =
        buildKwaborDatabase(
            builder = createIosKwaborDatabaseBuilder(databasePath = path),
            queryCoroutineContext = queryCoroutineContext,
        )

    private suspend fun writePreferences(path: String, queryCoroutineContext: CoroutineContext) {
        withPreferencesRepository(path, queryCoroutineContext) { repository ->
            assertIs<DomainResult.Success<*>>(repository.setExploreCity("cotonou"))
            assertIs<DomainResult.Success<*>>(repository.setDisplayCurrency(KwaborCurrency.Eur))
        }
    }

    private suspend fun assertPreferencesSurviveReopen(path: String, queryCoroutineContext: CoroutineContext) {
        withPreferencesRepository(path, queryCoroutineContext) { repository ->
            val preferences = assertIs<DomainResult.Success<AppPreferences>>(repository.get()).value
            assertEquals("cotonou", preferences.exploreCityId)
            assertEquals(KwaborCurrency.Eur, preferences.displayCurrency)
        }
    }

    private suspend fun withPreferencesRepository(
        path: String,
        queryCoroutineContext: CoroutineContext,
        block: suspend (DataStoreAppPreferencesRepository) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + queryCoroutineContext.minusKey(Job))
        val repository = DataStoreAppPreferencesRepository(
            dataStore = createAppPreferencesDataStore(
                storage = createIosAppPreferencesStorage(filePath = path),
                coroutineScope = scope,
            ),
        )
        try {
            block(repository)
        } finally {
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
        }
    }
}

private inline fun <R> KwaborDatabase.useDatabase(block: (KwaborDatabase) -> R): R = try {
    block(this)
} finally {
    close()
}

private fun wallSnapshot(): ExploreCacheSnapshot = ExploreCacheSnapshot(
    snapshotKey = "explore:cotonou:places",
    items = listOf(
        ListingSummary(
            id = "listing-ganvie",
            type = ListingType.Place,
            listingClass = ListingClass.Heritage,
            status = ListingStatus.Published,
            name = "Cité lacustre de Ganvié",
            cityId = "ganvie",
            categoryId = "heritage",
            coverImageUrl = "https://images.kwabor.example/ganvie.jpg",
            priceFromXof = null,
            ratingAverage = 4.8,
            likesCount = 42,
            verified = true,
            sponsoredUntilEpochMilliseconds = null,
            isSponsoredPlacement = false,
        ),
    ),
    nextCursor = "cursor-next",
    cachedAtEpochMilliseconds = 1_000,
)

private fun referenceSnapshot(): ExploreReferenceSnapshot = ExploreReferenceSnapshot(
    cities = listOf(
        City(
            id = "cotonou",
            name = "Cotonou",
            latitude = 6.3703,
            longitude = 2.3912,
        ),
        City(
            id = "ganvie",
            name = "Ganvié",
            latitude = 6.4667,
            longitude = 2.4167,
        ),
    ),
    categories = listOf(
        Category(
            id = "heritage",
            nameKey = "category_heritage",
            listingType = ListingType.Place,
            defaultListingClass = ListingClass.Heritage,
        ),
    ),
    cachedAtEpochMilliseconds = 1_000,
)

@OptIn(ExperimentalForeignApi::class)
private fun removePersistenceFiles(databasePath: String, preferencesPath: String) {
    val fileManager = NSFileManager.defaultManager
    listOf(
        databasePath,
        "$databasePath-shm",
        "$databasePath-wal",
        preferencesPath,
        "$preferencesPath.tmp",
    ).forEach { path ->
        if (fileManager.fileExistsAtPath(path)) {
            fileManager.removeItemAtPath(path, error = null)
        }
    }
}
