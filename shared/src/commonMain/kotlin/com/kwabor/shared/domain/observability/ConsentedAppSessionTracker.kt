package com.kwabor.shared.domain.observability

class ObservedAppSession internal constructor() {
    val eventName: String = OBSERVED_APP_SESSION_EVENT_NAME
}

data class ObservedAppSessionTimeMark(
    val wallEpochMilliseconds: Long,
    val monotonicMilliseconds: Long,
    val bootIdentifier: Long?,
    val bootAnchorEpochMilliseconds: Long,
) {
    init {
        require(wallEpochMilliseconds >= 0L) {
            "Observed app session wall time must be non-negative."
        }
        require(monotonicMilliseconds >= 0L) {
            "Observed app session monotonic time must be non-negative."
        }
        require(bootAnchorEpochMilliseconds >= 0L) {
            "Observed app session boot anchor must be non-negative."
        }
        require(bootIdentifier == null || bootIdentifier >= 0L) {
            "Observed app session boot identifiers must be non-negative."
        }
    }
}

sealed interface ObservedAppSessionTimeRead {
    data object Failure : ObservedAppSessionTimeRead

    data class Available(val mark: ObservedAppSessionTimeMark) : ObservedAppSessionTimeRead
}

interface ObservedAppSessionTimeSource {
    fun read(): ObservedAppSessionTimeRead
}

sealed interface ObservedAppSessionCheckpointRead {
    data object Missing : ObservedAppSessionCheckpointRead

    data object Failure : ObservedAppSessionCheckpointRead

    data object Foreground : ObservedAppSessionCheckpointRead

    data class BackgroundedAt(val timeMark: ObservedAppSessionTimeMark) : ObservedAppSessionCheckpointRead
}

interface ObservedAppSessionStore {
    fun read(): ObservedAppSessionCheckpointRead

    fun writeForeground(): Boolean

    fun writeBackgroundedAt(timeMark: ObservedAppSessionTimeMark): Boolean

    fun clear(): Boolean
}

class ConsentedAppSessionTracker(
    private val timeSource: ObservedAppSessionTimeSource,
    private val store: ObservedAppSessionStore,
    private val inactivityThresholdMilliseconds: Long = DEFAULT_SESSION_INACTIVITY_MILLISECONDS,
    private val maximumBootAnchorDriftMilliseconds: Long = DEFAULT_MAXIMUM_BOOT_ANCHOR_DRIFT_MILLISECONDS,
) {
    private var isForeground = false
    private var measurementAllowed = false
    private var foregroundEvaluationPending = false
    private var hasObservedSession = false
    private var localResetPending = false

    init {
        require(inactivityThresholdMilliseconds > 0L) {
            "Observed app session inactivity threshold must be positive."
        }
        require(maximumBootAnchorDriftMilliseconds >= 0L) {
            "Observed app session boot-anchor drift must be non-negative."
        }
    }

    fun updateMeasurementEligibility(allowed: Boolean): ObservedAppSession? {
        measurementAllowed = allowed
        return evaluatePendingForeground()
    }

    fun onForeground(): ObservedAppSession? {
        if (!isForeground) {
            isForeground = true
            foregroundEvaluationPending = true
        }
        return evaluatePendingForeground()
    }

    fun onBackground() {
        if (!isForeground) return
        isForeground = false
        foregroundEvaluationPending = false
        if (!hasObservedSession) return
        val timeMark = when (val timeRead = timeSource.read()) {
            ObservedAppSessionTimeRead.Failure -> return
            is ObservedAppSessionTimeRead.Available -> timeRead.mark
        }
        store.writeBackgroundedAt(timeMark)
    }

    fun revoke(): Boolean {
        measurementAllowed = false
        hasObservedSession = false
        foregroundEvaluationPending = isForeground
        localResetPending = !store.clear()
        return !localResetPending
    }

    private fun evaluatePendingForeground(): ObservedAppSession? {
        if (!measurementAllowed || !foregroundEvaluationPending) return null
        if (!completePendingLocalReset()) return null
        val shouldStartSession = when (val checkpoint = store.read()) {
            ObservedAppSessionCheckpointRead.Missing -> true
            ObservedAppSessionCheckpointRead.Failure -> return null
            ObservedAppSessionCheckpointRead.Foreground -> false
            is ObservedAppSessionCheckpointRead.BackgroundedAt -> {
                val currentTime = when (val timeRead = timeSource.read()) {
                    ObservedAppSessionTimeRead.Failure -> return null
                    is ObservedAppSessionTimeRead.Available -> timeRead.mark
                }
                hasReachedInactivityThreshold(
                    currentTime = currentTime,
                    backgroundTime = checkpoint.timeMark,
                )
            }
        }
        if (!store.writeForeground()) return null
        foregroundEvaluationPending = false
        hasObservedSession = true
        return ObservedAppSession().takeIf { shouldStartSession }
    }

    private fun completePendingLocalReset(): Boolean {
        if (!localResetPending) return true
        if (!store.clear()) return false
        localResetPending = false
        return true
    }

    private fun hasReachedInactivityThreshold(
        currentTime: ObservedAppSessionTimeMark,
        backgroundTime: ObservedAppSessionTimeMark,
    ): Boolean {
        if (!hasCertainBootContinuity(currentTime, backgroundTime)) return false
        if (currentTime.monotonicMilliseconds < backgroundTime.monotonicMilliseconds) return false
        return currentTime.monotonicMilliseconds - backgroundTime.monotonicMilliseconds >=
            inactivityThresholdMilliseconds
    }

    private fun hasCertainBootContinuity(
        currentTime: ObservedAppSessionTimeMark,
        backgroundTime: ObservedAppSessionTimeMark,
    ): Boolean {
        val currentBootIdentifier = currentTime.bootIdentifier
        val backgroundBootIdentifier = backgroundTime.bootIdentifier
        if (currentBootIdentifier != null || backgroundBootIdentifier != null) {
            return currentBootIdentifier != null && currentBootIdentifier == backgroundBootIdentifier
        }
        return distanceWithin(
            first = currentTime.bootAnchorEpochMilliseconds,
            second = backgroundTime.bootAnchorEpochMilliseconds,
            maximumDistance = maximumBootAnchorDriftMilliseconds,
        )
    }
}

private fun distanceWithin(first: Long, second: Long, maximumDistance: Long): Boolean = if (first >= second) {
    first - second <= maximumDistance
} else {
    second - first <= maximumDistance
}

private const val OBSERVED_APP_SESSION_EVENT_NAME = "observed_session_started"
private const val DEFAULT_SESSION_INACTIVITY_MILLISECONDS = 30L * 60L * 1_000L
private const val DEFAULT_MAXIMUM_BOOT_ANCHOR_DRIFT_MILLISECONDS = 5_000L
