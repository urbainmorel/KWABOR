package com.kwabor.android.observability

import android.os.SystemClock
import com.kwabor.shared.domain.observability.PerformanceMeasurement
import com.kwabor.shared.domain.observability.PerformanceViewportState
import com.kwabor.shared.presentation.explore.ExploreFirstUsableViewportProbe

internal class AndroidExploreFirstUsableViewportReporter private constructor(
    private val diagnosticsAllowed: () -> Boolean,
    private val recordMeasurement: (PerformanceMeasurement) -> Unit,
    private val monotonicClock: () -> Long,
    private val probe: ExploreFirstUsableViewportProbe,
) {
    constructor(controller: AndroidObservabilityController) : this(
        diagnosticsAllowed = { controller.performanceCollectionAllowed.value },
        recordMeasurement = controller.performance::recordMeasurement,
        monotonicClock = SystemClock::elapsedRealtimeNanos,
        probe = ExploreFirstUsableViewportProbe(),
    )

    internal constructor(
        diagnosticsAllowed: () -> Boolean,
        recordMeasurement: (PerformanceMeasurement) -> Unit,
        monotonicClock: () -> Long,
    ) : this(
        diagnosticsAllowed = diagnosticsAllowed,
        recordMeasurement = recordMeasurement,
        monotonicClock = monotonicClock,
        probe = ExploreFirstUsableViewportProbe(),
    )

    fun onVisible(): Long? {
        val isAllowed = diagnosticsAllowed()
        if (!isAllowed) return null
        return probe.onVisible(
            diagnosticsAllowed = true,
            startedAtNanoseconds = monotonicClock(),
        )?.generation
    }

    fun onHidden() {
        probe.onHidden()
    }

    fun onViewportCommitted(generation: Long, viewportState: PerformanceViewportState) {
        probe.onViewportCommitted(
            generation = generation,
            viewportState = viewportState,
            committedAtNanoseconds = monotonicClock(),
        )?.performanceMeasurement?.let(recordMeasurement)
    }
}
