package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.PerformanceExploreAppearanceKind
import com.kwabor.shared.domain.observability.PerformanceMeasurement
import com.kwabor.shared.domain.observability.PerformanceViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidExploreFirstUsableViewportReporterTest {
    @Test
    fun layoutDuplicatesAndStaleCallbacksExportOneMonotonicMeasurement() {
        val clock = MutableMonotonicClock(1_000L)
        val measurements = mutableListOf<PerformanceMeasurement>()
        val reporter = reporter(clock, measurements)
        val firstProcessExploreGeneration = requireNotNull(reporter.onVisible())

        assertEquals(firstProcessExploreGeneration, reporter.onVisible())
        reporter.onHidden()
        clock.now = 2_000L
        val subsequentExploreGeneration = requireNotNull(reporter.onVisible())
        clock.now = 5_500L
        reporter.onViewportCommitted(firstProcessExploreGeneration, PerformanceViewportState.Content)
        reporter.onViewportCommitted(subsequentExploreGeneration, PerformanceViewportState.Offline)
        reporter.onViewportCommitted(subsequentExploreGeneration, PerformanceViewportState.Offline)

        assertEquals(1, measurements.size)
        assertEquals(
            PerformanceExploreAppearanceKind.SubsequentExplore,
            measurements.single().processExploreKind,
        )
        assertEquals(3L, measurements.single().metricValue)
        assertEquals(PerformanceViewportState.Offline, measurements.single().viewportState)
    }

    @Test
    fun deniedDiagnosticsConsentDoesNotStartUntilEligibilityIsRestored() {
        val clock = MutableMonotonicClock(1_000L)
        val measurements = mutableListOf<PerformanceMeasurement>()
        var diagnosticsAllowed = false
        val reporter = AndroidExploreFirstUsableViewportReporter(
            diagnosticsAllowed = { diagnosticsAllowed },
            recordMeasurement = measurements::add,
            monotonicClock = clock::read,
        )
        assertNull(reporter.onVisible())

        assertEquals(emptyList(), measurements)
        assertEquals(0, clock.readCount)

        diagnosticsAllowed = true
        val generation = requireNotNull(reporter.onVisible())
        clock.now = 2_000L
        reporter.onViewportCommitted(generation, PerformanceViewportState.Empty)

        assertEquals(
            PerformanceExploreAppearanceKind.FirstProcessExplore,
            measurements.single().processExploreKind,
        )
    }
}

private fun reporter(
    clock: MutableMonotonicClock,
    measurements: MutableList<PerformanceMeasurement>,
): AndroidExploreFirstUsableViewportReporter = AndroidExploreFirstUsableViewportReporter(
    diagnosticsAllowed = { true },
    recordMeasurement = measurements::add,
    monotonicClock = clock::read,
)

private class MutableMonotonicClock(var now: Long) {
    var readCount = 0
        private set

    fun read(): Long {
        readCount += 1
        return now
    }
}
