package com.kwabor.shared.data.explore

import androidx.sqlite.SQLiteException
import com.kwabor.shared.data.local.ExploreCacheSnapshot
import com.kwabor.shared.data.local.ExplorePersistenceWriteResult
import com.kwabor.shared.data.local.ExploreReferenceSnapshot
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.core.ClockProvider
import kotlinx.coroutines.CancellationException

internal class FakeExploreWallCache(
    var snapshot: ExploreCacheSnapshot? = null,
) : ExploreWallCache {
    var failReads: Boolean = false
    var failWrites: Boolean = false
    var rejectWrites: Boolean = false
    var cancelWrites: Boolean = false
    val writes = mutableListOf<ExploreCacheSnapshot>()
    val clearedKeys = mutableListOf<String>()
    val clearRequests = mutableListOf<Pair<String, Long>>()

    override suspend fun read(snapshotKey: String): ExploreCacheSnapshot? {
        if (failReads) {
            throw SQLiteException("Synthetic Explore wall read failure.")
        }
        return snapshot?.takeIf { cached -> cached.snapshotKey == snapshotKey }
    }

    override suspend fun replace(snapshot: ExploreCacheSnapshot): ExplorePersistenceWriteResult {
        if (cancelWrites) {
            throw CancellationException("Synthetic Explore wall write cancellation.")
        }
        require(!rejectWrites) { "Synthetic invalid Explore wall payload." }
        if (failWrites) {
            throw SQLiteException("Synthetic Explore wall write failure.")
        }
        val currentTimestamp = this.snapshot
            ?.takeIf { current -> current.snapshotKey == snapshot.snapshotKey }
            ?.cachedAtEpochMilliseconds
        if (currentTimestamp != null && currentTimestamp >= snapshot.cachedAtEpochMilliseconds) {
            return ExplorePersistenceWriteResult.Rejected
        }
        writes += snapshot
        this.snapshot = snapshot
        return ExplorePersistenceWriteResult.Applied
    }

    override suspend fun clear(snapshotKey: String, expectedCachedAtEpochMilliseconds: Long): Boolean {
        clearRequests += snapshotKey to expectedCachedAtEpochMilliseconds
        val currentSnapshot = snapshot
        val timestampMatches = currentSnapshot?.snapshotKey == snapshotKey &&
            currentSnapshot.cachedAtEpochMilliseconds == expectedCachedAtEpochMilliseconds
        if (timestampMatches) {
            clearedKeys += snapshotKey
            snapshot = null
        }
        return timestampMatches
    }
}

internal class FakeExploreReferenceCache(
    var snapshot: ExploreReferenceSnapshot? = null,
) : ExploreReferenceCache {
    var failReads: Boolean = false
    var failWrites: Boolean = false
    var rejectWrites: Boolean = false
    val writes = mutableListOf<ExploreReferenceSnapshot>()

    override suspend fun read(): ExploreReferenceSnapshot? {
        if (failReads) {
            throw SQLiteException("Synthetic Explore reference read failure.")
        }
        return snapshot
    }

    override suspend fun replace(snapshot: ExploreReferenceSnapshot) {
        require(!rejectWrites) { "Synthetic invalid Explore reference payload." }
        if (failWrites) {
            throw SQLiteException("Synthetic Explore reference write failure.")
        }
        writes += snapshot
        this.snapshot = snapshot
    }
}

internal class FakeExploreFeedPersistenceCache(
    private val wall: FakeExploreWallCache,
    private val references: FakeExploreReferenceCache,
) : ExploreFeedPersistenceCache {
    override suspend fun replace(
        wall: ExploreCacheSnapshot,
        references: ExploreReferenceSnapshot,
    ): ExplorePersistenceWriteResult {
        val currentWallTimestamp = this.wall.snapshot
            ?.takeIf { snapshot -> snapshot.snapshotKey == wall.snapshotKey }
            ?.cachedAtEpochMilliseconds
        val currentReferenceTimestamp = this.references.snapshot?.cachedAtEpochMilliseconds
        val plan = feedReplacementPlan(
            currentWallTimestamp = currentWallTimestamp,
            incomingWallTimestamp = wall.cachedAtEpochMilliseconds,
            currentReferenceTimestamp = currentReferenceTimestamp,
            incomingReferenceTimestamp = references.cachedAtEpochMilliseconds,
        )
        if (!plan.hasChanges) {
            return ExplorePersistenceWriteResult.Rejected
        }
        validateFeedWallReplacement(plan.wall, this.wall)
        validateFeedReferenceReplacement(plan.references, this.references)
        if (plan.wall) {
            this.wall.replace(wall)
        }
        if (plan.references) {
            this.references.replace(references)
        }
        return ExplorePersistenceWriteResult.Applied
    }
}

private data class FeedReplacementPlan(
    val wall: Boolean,
    val references: Boolean,
) {
    val hasChanges: Boolean = wall || references
}

private fun feedReplacementPlan(
    currentWallTimestamp: Long?,
    incomingWallTimestamp: Long,
    currentReferenceTimestamp: Long?,
    incomingReferenceTimestamp: Long,
): FeedReplacementPlan = FeedReplacementPlan(
    wall = currentWallTimestamp == null || currentWallTimestamp < incomingWallTimestamp,
    references = currentReferenceTimestamp == null || currentReferenceTimestamp < incomingReferenceTimestamp,
)

private fun validateFeedWallReplacement(shouldReplace: Boolean, wall: FakeExploreWallCache) {
    if (!shouldReplace) return
    if (wall.cancelWrites) {
        throw CancellationException("Synthetic Explore feed write cancellation.")
    }
    require(!wall.rejectWrites) { "Synthetic invalid Explore feed payload." }
    if (wall.failWrites) {
        throw SQLiteException("Synthetic Explore feed write failure.")
    }
}

private fun validateFeedReferenceReplacement(shouldReplace: Boolean, references: FakeExploreReferenceCache) {
    if (!shouldReplace) return
    require(!references.rejectWrites) { "Synthetic invalid Explore feed payload." }
    if (references.failWrites) {
        throw SQLiteException("Synthetic Explore feed write failure.")
    }
}

internal class MutableExploreClock(var now: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = now
}

internal fun testCities(): List<City> = listOf(City(id = "city-cotonou", name = "Cotonou"))

internal fun testCategories(): List<Category> = listOf(
    Category(
        id = "category-culture",
        nameKey = "category.culture",
        listingType = ListingType.Place,
        defaultListingClass = ListingClass.Heritage,
    ),
)

internal fun testListings(range: IntRange): List<ListingSummary> = range.map(::testListing)

internal fun testEstablishmentListings(
    range: IntRange,
    sponsoredIndices: Set<Int> = emptySet(),
): List<ListingSummary> = range.map { index ->
    ListingSummary(
        id = "establishment-$index",
        type = ListingType.Establishment,
        listingClass = ListingClass.Commercial,
        status = ListingStatus.Published,
        name = "Établissement $index",
        cityId = "city-cotonou",
        categoryId = "category-restaurant",
        coverImageUrl = null,
        priceFromXof = null,
        ratingAverage = null,
        likesCount = index,
        verified = true,
        sponsoredUntilEpochMilliseconds = if (index in sponsoredIndices) 2_000_000_000_000L else null,
        isSponsoredPlacement = index in sponsoredIndices,
        viewsCount = index.toLong(),
        isEventEnded = false,
    )
}

internal fun testEstablishmentCategory(): Category = Category(
    id = "category-restaurant",
    nameKey = "category.restaurant",
    listingType = ListingType.Establishment,
    defaultListingClass = ListingClass.Commercial,
)

private fun testListing(index: Int): ListingSummary = ListingSummary(
    id = "listing-$index",
    type = ListingType.Place,
    listingClass = ListingClass.Heritage,
    status = ListingStatus.Published,
    name = "Lieu $index",
    cityId = "city-cotonou",
    categoryId = "category-culture",
    coverImageUrl = null,
    priceFromXof = null,
    ratingAverage = null,
    likesCount = index,
    verified = true,
    sponsoredUntilEpochMilliseconds = null,
    isSponsoredPlacement = false,
    viewsCount = index.toLong(),
    isEventEnded = false,
)
