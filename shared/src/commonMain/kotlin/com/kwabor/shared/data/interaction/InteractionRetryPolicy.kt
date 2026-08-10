package com.kwabor.shared.data.interaction

import kotlin.math.min
import kotlin.random.Random

internal const val MAX_INTERACTION_RETRY_DELAY_MILLISECONDS = 5L * 60L * 1_000L

internal fun interface InteractionJitterSource {
    fun nextLong(untilExclusive: Long): Long
}

internal fun interface InteractionRetryDelayPolicy {
    fun delayMilliseconds(nextAttemptCount: Int): Long
}

internal class EqualJitterInteractionRetryDelayPolicy(
    private val jitterSource: InteractionJitterSource,
    private val baseDelayMilliseconds: Long = 1_000L,
    private val maximumDelayMilliseconds: Long = MAX_INTERACTION_RETRY_DELAY_MILLISECONDS,
) : InteractionRetryDelayPolicy {
    init {
        require(baseDelayMilliseconds > 0L) { "Interaction retry base delay must be positive." }
        require(maximumDelayMilliseconds >= baseDelayMilliseconds) {
            "Interaction retry maximum delay must cover the base delay."
        }
        require(maximumDelayMilliseconds <= MAX_INTERACTION_RETRY_DELAY_MILLISECONDS) {
            "Interaction retry delay cannot exceed five minutes."
        }
    }

    override fun delayMilliseconds(nextAttemptCount: Int): Long {
        require(nextAttemptCount > 0) { "Interaction retry attempt count must be positive." }
        val exponentialDelay = cappedExponentialDelay(nextAttemptCount)
        val lowerBound = exponentialDelay / 2L
        val jitterRange = exponentialDelay - lowerBound
        val jitter = if (jitterRange == Long.MAX_VALUE) {
            jitterSource.nextLong(Long.MAX_VALUE)
        } else {
            jitterSource.nextLong(jitterRange + 1L)
        }
        require(jitter in 0L..jitterRange) { "Interaction jitter source returned an out-of-range value." }
        return lowerBound + jitter
    }

    private fun cappedExponentialDelay(nextAttemptCount: Int): Long {
        var delay = baseDelayMilliseconds
        repeat(min(nextAttemptCount - 1, MAXIMUM_BACKOFF_DOUBLINGS)) {
            delay = min(delay * 2L, maximumDelayMilliseconds)
        }
        return delay
    }
}

internal object DefaultInteractionJitterSource : InteractionJitterSource {
    override fun nextLong(untilExclusive: Long): Long = Random.Default.nextLong(untilExclusive)
}

private const val MAXIMUM_BACKOFF_DOUBLINGS = 30
