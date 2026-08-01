package com.kwabor.shared.data.explore

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.kwabor.shared.data.local.ExploreCacheSnapshot
import com.kwabor.shared.data.local.ExploreCacheStore
import com.kwabor.shared.data.local.ExplorePersistenceWatermarkStore
import com.kwabor.shared.data.local.ExplorePersistenceWriteResult
import com.kwabor.shared.data.local.KwaborDatabase
import com.kwabor.shared.data.local.KwaborDatabaseConstructor
import com.kwabor.shared.data.local.buildKwaborDatabase
import com.kwabor.shared.domain.core.ClockProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExplorePersistenceWatermarkTest {
    @Test
    fun aSecondCoordinatorWithARewoundClockStartsBeyondTheFirstPersistedGeneration() = runTest {
        withWatermarkDatabase(coroutineContext) { database ->
            val cacheStore = ExploreCacheStore(database.exploreCacheDao())
            val watermarkStore = ExplorePersistenceWatermarkStore(database.explorePersistenceWatermarkDao())
            val initialSnapshot = emptySnapshot(timestamp = 9_000L)
            assertEquals(ExplorePersistenceWriteResult.Applied, cacheStore.replace(initialSnapshot))

            val firstCoordinator = ExploreRequestCoordinator(
                clockProvider = FixedClock(epochMilliseconds = 1_000L),
                persistentWatermarkProvider = StoredExplorePersistenceWatermarkProvider(lazy { watermarkStore }),
            )
            val firstReservation = assertNotNull(firstCoordinator.reserveRefresh(CACHE_KEY))
            assertEquals(FIRST_RESERVED_TIMESTAMP, firstReservation.requestTimestamp)
            assertEquals(
                ExplorePersistenceWriteResult.Applied,
                cacheStore.replace(emptySnapshot(firstReservation.requestTimestamp)),
            )

            val secondCoordinator = ExploreRequestCoordinator(
                clockProvider = FixedClock(epochMilliseconds = 500L),
                persistentWatermarkProvider = StoredExplorePersistenceWatermarkProvider(lazy { watermarkStore }),
            )
            val secondReservation = assertNotNull(secondCoordinator.reserveRefresh(CACHE_KEY))

            assertEquals(SECOND_RESERVED_TIMESTAMP, secondReservation.requestTimestamp)
        }
    }
}

private fun emptySnapshot(timestamp: Long): ExploreCacheSnapshot = ExploreCacheSnapshot(
    snapshotKey = CACHE_KEY,
    items = emptyList(),
    nextCursor = null,
    cachedAtEpochMilliseconds = timestamp,
)

private suspend fun withWatermarkDatabase(
    queryCoroutineContext: CoroutineContext,
    block: suspend (KwaborDatabase) -> Unit,
) {
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

private class FixedClock(private val epochMilliseconds: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = epochMilliseconds
}

private const val CACHE_KEY = "explore:watermark"
private const val FIRST_RESERVED_TIMESTAMP = 9_001L
private const val SECOND_RESERVED_TIMESTAMP = 9_002L
