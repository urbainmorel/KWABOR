package com.kwabor.shared.data.explore

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExploreRequestTimestampAllocatorTest {
    @Test
    fun timestampsRemainStrictlyIncreasingWhenTheDeviceClockStallsOrMovesBackwards() = runTest {
        val clock = MutableExploreClock(2_000L)
        val allocator = ExploreRequestTimestampAllocator(clock)

        assertEquals(2_000L, allocator.allocateOrNull())
        assertEquals(2_001L, allocator.allocateOrNull())
        clock.now = 1_000L
        assertEquals(2_002L, allocator.allocateOrNull())
    }

    @Test
    fun concurrentRequestsReceiveUniqueStartOrder() = runTest {
        val allocator = ExploreRequestTimestampAllocator(MutableExploreClock(5_000L))

        val timestamps = List(REQUEST_COUNT) {
            async { requireNotNull(allocator.allocateOrNull()) }
        }.awaitAll()

        assertEquals((5_000L until 5_000L + REQUEST_COUNT).toList(), timestamps.sorted())
    }

    @Test
    fun concurrentInitializationSeedsOnceBeyondThePersistentWatermark() = runTest {
        var readCount = 0
        val provider = ExplorePersistenceWatermarkProvider {
            readCount += 1
            ExplorePersistenceWatermarkRead.Available(timestamp = 9_000L)
        }
        val allocator = ExploreRequestTimestampAllocator(
            clockProvider = MutableExploreClock(1_000L),
            persistentWatermarkProvider = provider,
        )

        val timestamps = List(REQUEST_COUNT) {
            async { requireNotNull(allocator.allocateOrNull()) }
        }.awaitAll()

        assertEquals(1, readCount)
        assertEquals((9_001L until 9_001L + REQUEST_COUNT).toList(), timestamps.sorted())
    }

    @Test
    fun unavailablePersistenceUsesTheClockAndRetriesSeedingOnTheNextReservation() = runTest {
        var readCount = 0
        val provider = ExplorePersistenceWatermarkProvider {
            readCount += 1
            if (readCount == 1) {
                ExplorePersistenceWatermarkRead.Unavailable
            } else {
                ExplorePersistenceWatermarkRead.Available(timestamp = 3_000L)
            }
        }
        val clock = MutableExploreClock(1_000L)
        val allocator = ExploreRequestTimestampAllocator(clock, provider)

        assertEquals(1_000L, allocator.allocateOrNull())
        clock.now = 500L
        assertEquals(3_001L, allocator.allocateOrNull())
        assertEquals(3_002L, allocator.allocateOrNull())
        assertEquals(2, readCount)
    }

    @Test
    fun invalidOrExhaustedClockCannotProduceAnOrderingToken() = runTest {
        assertNull(ExploreRequestTimestampAllocator(MutableExploreClock(-1L)).allocateOrNull())
        val allocator = ExploreRequestTimestampAllocator(MutableExploreClock(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, allocator.allocateOrNull())
        assertNull(allocator.allocateOrNull())
    }
}

private const val REQUEST_COUNT = 8
