package com.kwabor.shared.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingType
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SearchCacheStoreTest {
    @Test
    fun readsPublishedCanonicalListingsWithReferenceLabelsAndExactFilters() = runTest {
        withDatabase(coroutineContext) { database ->
            val snapshot = seededSnapshot()
            ExploreReferenceStore(database.exploreReferenceDao()).replace(referenceSnapshot())
            ExploreCacheStore(database.exploreCacheDao()).replace(snapshot)
            val store = SearchCacheStore(database.searchCacheDao())

            val candidates = store.readCandidates(
                ListingFilters(
                    cityId = "cotonou",
                    categoryId = "restaurants",
                    listingType = ListingType.Establishment,
                    listingClass = ListingClass.Commercial,
                ),
            )

            val candidate = candidates.single()
            assertEquals("restaurant-cotonou", candidate.listing.id)
            assertEquals("Cotonou", candidate.cityName)
            assertEquals("category.restaurant", candidate.categoryNameKey)
            assertNull(candidate.listing.isSponsoredPlacement)
        }
    }

    @Test
    fun readIsBoundedToPublishedRowsAndDoesNotMutateTheExploreSnapshot() = runTest {
        withDatabase(coroutineContext) { database ->
            val snapshot = seededSnapshot()
            val exploreStore = ExploreCacheStore(database.exploreCacheDao())
            exploreStore.replace(snapshot)
            val before = exploreStore.read(snapshot.snapshotKey)

            val candidates = SearchCacheStore(database.searchCacheDao()).readCandidates(ListingFilters())

            assertEquals(
                listOf("musee-ouidah", "restaurant-cotonou"),
                candidates.map { candidate -> candidate.listing.id }.sorted(),
            )
            assertEquals(before, exploreStore.read(snapshot.snapshotKey))
        }
    }
}

private fun seededSnapshot(): ExploreCacheSnapshot = ExploreCacheSnapshot(
    snapshotKey = "explore:search-store-test",
    items = listOf(
        listingSummary(id = "restaurant-cotonou", name = "Restaurant Kwabor"),
        listingSummary(id = "restaurant-draft", name = "Restaurant brouillon").copy(
            status = ListingStatus.Draft,
        ),
        listingSummary(id = "musee-ouidah", name = "Musée de Ouidah").copy(
            type = ListingType.Place,
            listingClass = ListingClass.Heritage,
            cityId = "ouidah",
            categoryId = "sites",
        ),
    ),
    nextCursor = "remote-next",
    cachedAtEpochMilliseconds = 2_000,
)

private fun referenceSnapshot(): ExploreReferenceSnapshot = ExploreReferenceSnapshot(
    cities = listOf(
        City(id = "cotonou", name = "Cotonou"),
        City(id = "ouidah", name = "Ouidah"),
    ),
    categories = listOf(
        Category(
            id = "restaurants",
            nameKey = "category.restaurant",
            listingType = ListingType.Establishment,
            defaultListingClass = ListingClass.Commercial,
        ),
        Category(
            id = "sites",
            nameKey = "category.site",
            listingType = ListingType.Place,
            defaultListingClass = ListingClass.Heritage,
        ),
    ),
    cachedAtEpochMilliseconds = 1_000,
)

private suspend fun withDatabase(queryCoroutineContext: CoroutineContext, block: suspend (KwaborDatabase) -> Unit) {
    val database = buildKwaborDatabase(
        builder = Room.inMemoryDatabaseBuilder(
            context = ApplicationProvider.getApplicationContext<Context>(),
            factory = KwaborDatabaseConstructor::initialize,
        ),
        queryCoroutineContext = queryCoroutineContext,
        driver = AndroidSQLiteDriver(),
    )
    try {
        block(database)
    } finally {
        database.close()
    }
}
