package com.kwabor.shared.data.explore

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ExploreRequestTimestampAllocator(
    private val clockProvider: ClockProvider,
    private val persistentWatermarkProvider: ExplorePersistenceWatermarkProvider =
        EMPTY_EXPLORE_PERSISTENCE_WATERMARK_PROVIDER,
) {
    private val mutex = Mutex()
    private var lastAllocatedTimestamp = NO_TIMESTAMP
    private var hasPersistentSeed = false

    suspend fun allocateOrNull(): Long? = allocate()?.timestamp

    suspend fun allocate(): ExploreTimestampAllocation? = mutex.withLock {
        val watermarkReadUnavailable = seedFromPersistenceIfAvailable()
        val clockTimestamp = clockProvider.nowEpochMilliseconds()
        val allocatedTimestamp = when {
            clockTimestamp > lastAllocatedTimestamp -> clockTimestamp
            lastAllocatedTimestamp == Long.MAX_VALUE -> return@withLock null
            lastAllocatedTimestamp >= 0 -> lastAllocatedTimestamp + 1
            else -> return@withLock null
        }
        lastAllocatedTimestamp = allocatedTimestamp
        ExploreTimestampAllocation(
            timestamp = allocatedTimestamp,
            watermarkReadUnavailable = watermarkReadUnavailable,
        )
    }

    private suspend fun seedFromPersistenceIfAvailable(): Boolean {
        if (hasPersistentSeed) {
            return false
        }
        return when (val watermark = persistentWatermarkProvider.read()) {
            is ExplorePersistenceWatermarkRead.Available -> {
                val persistedTimestamp = watermark.timestamp ?: NO_TIMESTAMP
                lastAllocatedTimestamp = maxOf(lastAllocatedTimestamp, persistedTimestamp)
                hasPersistentSeed = true
                false
            }
            ExplorePersistenceWatermarkRead.Unavailable -> true
        }
    }
}

internal class ExploreRequestCoordinator(
    clockProvider: ClockProvider,
    persistentWatermarkProvider: ExplorePersistenceWatermarkProvider =
        EMPTY_EXPLORE_PERSISTENCE_WATERMARK_PROVIDER,
) {
    private val mutex = Mutex()
    private val timestampAllocator = ExploreRequestTimestampAllocator(clockProvider, persistentWatermarkProvider)
    private val states = mutableMapOf<String, ExploreRequestState>()

    suspend fun reserveRefresh(cacheKey: String): ExploreRequestReservation? = mutex.withLock {
        val allocation = timestampAllocator.allocate() ?: return@withLock null
        val state = states.getOrPut(cacheKey, ::ExploreRequestState)
        state.active = ActiveExploreRequest(
            requestTimestamp = allocation.timestamp,
            operation = ExploreRequestOperation.Refresh,
            baseSnapshotTimestamp = null,
        )
        ExploreRequestReservation(
            cacheKey = cacheKey,
            requestTimestamp = allocation.timestamp,
            watermarkReadUnavailable = allocation.watermarkReadUnavailable,
        )
    }

    suspend fun reserveAppend(cacheKey: String, baseSnapshotTimestamp: Long): ExploreRequestReservation? =
        mutex.withLock {
            val state = states.getOrPut(cacheKey, ::ExploreRequestState)
            if (!state.acceptsAppend(baseSnapshotTimestamp)) {
                return@withLock null
            }
            val allocation = timestampAllocator.allocate()
            if (allocation == null) {
                removeEmptyState(cacheKey, state)
                return@withLock null
            }
            state.active = ActiveExploreRequest(
                requestTimestamp = allocation.timestamp,
                operation = ExploreRequestOperation.Append,
                baseSnapshotTimestamp = baseSnapshotTimestamp,
            )
            ExploreRequestReservation(
                cacheKey = cacheKey,
                requestTimestamp = allocation.timestamp,
                watermarkReadUnavailable = allocation.watermarkReadUnavailable,
            )
        }

    suspend fun rollback(reservation: ExploreRequestReservation) {
        mutex.withLock {
            val state = states[reservation.cacheKey] ?: return@withLock
            if (state.active?.requestTimestamp == reservation.requestTimestamp) {
                state.active = null
                removeEmptyState(reservation.cacheKey, state)
            }
        }
    }

    suspend fun <T> completeIfLatest(
        reservation: ExploreRequestReservation,
        block: suspend () -> DomainResult<T>,
    ): DomainResult<T>? = mutex.withLock {
        val state = states[reservation.cacheKey] ?: return@withLock null
        if (state.active?.requestTimestamp != reservation.requestTimestamp) {
            return@withLock null
        }
        try {
            block().also { result ->
                if (result is DomainResult.Success) {
                    state.committedSnapshotTimestamp = reservation.requestTimestamp
                }
            }
        } finally {
            state.active = null
            removeEmptyState(reservation.cacheKey, state)
        }
    }

    private fun removeEmptyState(cacheKey: String, state: ExploreRequestState) {
        if (state.active == null && state.committedSnapshotTimestamp == null) {
            states.remove(cacheKey)
        }
    }
}

internal fun interface ExplorePersistenceWatermarkProvider {
    suspend fun read(): ExplorePersistenceWatermarkRead
}

internal sealed interface ExplorePersistenceWatermarkRead {
    data class Available(val timestamp: Long?) : ExplorePersistenceWatermarkRead

    data object Unavailable : ExplorePersistenceWatermarkRead
}

internal data class ExploreRequestReservation(
    val cacheKey: String,
    val requestTimestamp: Long,
    val watermarkReadUnavailable: Boolean,
)

internal data class ExploreTimestampAllocation(
    val timestamp: Long,
    val watermarkReadUnavailable: Boolean,
)

private class ExploreRequestState(
    var committedSnapshotTimestamp: Long? = null,
    var active: ActiveExploreRequest? = null,
) {
    fun acceptsAppend(baseSnapshotTimestamp: Long): Boolean = when (val currentRequest = active) {
        null -> committedSnapshotTimestamp == null || committedSnapshotTimestamp == baseSnapshotTimestamp
        else ->
            currentRequest.operation == ExploreRequestOperation.Append &&
                baseSnapshotTimestamp >= requireNotNull(currentRequest.baseSnapshotTimestamp)
    }
}

private data class ActiveExploreRequest(
    val requestTimestamp: Long,
    val operation: ExploreRequestOperation,
    val baseSnapshotTimestamp: Long?,
)

private enum class ExploreRequestOperation {
    Refresh,
    Append,
}

private const val NO_TIMESTAMP = -1L

internal val EMPTY_EXPLORE_PERSISTENCE_WATERMARK_PROVIDER = ExplorePersistenceWatermarkProvider {
    ExplorePersistenceWatermarkRead.Available(timestamp = null)
}
