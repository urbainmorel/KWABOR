package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.observability.PerformanceExploreAppearanceKind
import com.kwabor.shared.domain.observability.PerformanceMeasurement
import com.kwabor.shared.domain.observability.PerformanceMetricName
import com.kwabor.shared.domain.observability.PerformanceTraceName
import com.kwabor.shared.domain.observability.PerformanceViewportState

data class ExploreFirstUsableViewportSample(
    val generation: Long,
    val processExploreKind: PerformanceExploreAppearanceKind,
    val startedAtNanoseconds: Long,
)

data class ExploreFirstUsableViewportMeasurement(
    val sample: ExploreFirstUsableViewportSample,
    val viewportState: PerformanceViewportState,
    val durationNanoseconds: Long,
) {
    val performanceMeasurement: PerformanceMeasurement
        get() = PerformanceMeasurement(
            traceName = PerformanceTraceName.ExploreInitialLoad,
            metricName = PerformanceMetricName.FirstUsableViewportMicroseconds,
            metricValue = durationNanoseconds / NANOSECONDS_PER_MICROSECOND,
            processExploreKind = sample.processExploreKind,
            viewportState = viewportState,
        )
}

class ExploreFirstUsableViewportProbe {
    private var generation = 0L
    private var hasObservedProcessAppearance = false
    private var visibilitySessionActive = false
    private var activeSample: ExploreFirstUsableViewportSample? = null

    fun onVisible(diagnosticsAllowed: Boolean, startedAtNanoseconds: Long): ExploreFirstUsableViewportSample? {
        if (!diagnosticsAllowed || startedAtNanoseconds < 0L) return null
        if (visibilitySessionActive) return activeSample

        visibilitySessionActive = true
        val processExploreKind = if (hasObservedProcessAppearance) {
            PerformanceExploreAppearanceKind.SubsequentExplore
        } else {
            PerformanceExploreAppearanceKind.FirstProcessExplore
        }
        hasObservedProcessAppearance = true
        generation = generation.nextGeneration()
        val sample = ExploreFirstUsableViewportSample(
            generation = generation,
            processExploreKind = processExploreKind,
            startedAtNanoseconds = startedAtNanoseconds,
        )
        activeSample = sample
        return sample
    }

    fun onHidden() {
        activeSample = null
        visibilitySessionActive = false
    }

    fun onViewportCommitted(
        generation: Long,
        viewportState: PerformanceViewportState,
        committedAtNanoseconds: Long,
    ): ExploreFirstUsableViewportMeasurement? {
        val active = activeSample ?: return null
        if (active.generation != generation) return null

        activeSample = null
        if (committedAtNanoseconds < active.startedAtNanoseconds) return null
        return ExploreFirstUsableViewportMeasurement(
            sample = active,
            viewportState = viewportState,
            durationNanoseconds = committedAtNanoseconds - active.startedAtNanoseconds,
        )
    }
}

private fun Long.nextGeneration(): Long = if (this == Long.MAX_VALUE) 1L else this + 1L

private const val NANOSECONDS_PER_MICROSECOND = 1_000L
