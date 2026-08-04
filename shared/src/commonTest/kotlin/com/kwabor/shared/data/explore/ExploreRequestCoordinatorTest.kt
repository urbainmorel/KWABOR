package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.core.DomainResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExploreRequestCoordinatorTest {
    @Test
    fun newerAppendSupersedesAnOlderSnapshotGeneration() = runTest {
        val coordinator = ExploreRequestCoordinator(MutableExploreClock(2_000L))
        val older = assertNotNull(coordinator.reserveAppend(CACHE_KEY, baseSnapshotTimestamp = 1_000L))
        val newer = assertNotNull(coordinator.reserveAppend(CACHE_KEY, baseSnapshotTimestamp = 1_500L))

        val olderResult = coordinator.completeIfLatest(older) { DomainResult.Success("older") }
        val newerResult = coordinator.completeIfLatest(newer) { DomainResult.Success("newer") }

        assertNull(olderResult)
        assertEquals(DomainResult.Success("newer"), newerResult)
    }

    @Test
    fun refreshSupersedesAnAppendThatHasNotCommitted() = runTest {
        val coordinator = ExploreRequestCoordinator(MutableExploreClock(2_000L))
        val append = assertNotNull(coordinator.reserveAppend(CACHE_KEY, baseSnapshotTimestamp = 1_000L))
        val refresh = assertNotNull(coordinator.reserveRefresh(CACHE_KEY))

        assertNull(coordinator.completeIfLatest(append) { DomainResult.Success("append") })
        assertEquals(
            DomainResult.Success("refresh"),
            coordinator.completeIfLatest(refresh) { DomainResult.Success("refresh") },
        )
    }

    @Test
    fun failedRefreshRollbackKeepsTheLastCommittedSnapshotAppendable() = runTest {
        val coordinator = ExploreRequestCoordinator(MutableExploreClock(2_000L))
        val initial = assertNotNull(coordinator.reserveAppend(CACHE_KEY, baseSnapshotTimestamp = 1_000L))
        assertEquals(
            DomainResult.Success("initial"),
            coordinator.completeIfLatest(initial) { DomainResult.Success("initial") },
        )
        val refresh = assertNotNull(coordinator.reserveRefresh(CACHE_KEY))

        coordinator.rollback(refresh)

        assertNotNull(
            coordinator.reserveAppend(
                cacheKey = CACHE_KEY,
                baseSnapshotTimestamp = initial.requestTimestamp,
            ),
        )
    }
}

private const val CACHE_KEY = "explore:test"
