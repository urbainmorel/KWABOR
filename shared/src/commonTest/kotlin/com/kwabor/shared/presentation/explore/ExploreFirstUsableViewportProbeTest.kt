package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.observability.PerformanceExploreAppearanceKind
import com.kwabor.shared.domain.observability.PerformanceMetricName
import com.kwabor.shared.domain.observability.PerformanceTraceName
import com.kwabor.shared.domain.observability.PerformanceViewportState
import com.kwabor.shared.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExploreFirstUsableViewportProbeTest {
    @Test
    fun duplicateAppearAndCommitProduceExactlyOneFirstProcessExploreMeasurement() {
        val probe = ExploreFirstUsableViewportProbe()
        val first = probe.onVisible(diagnosticsAllowed = true, startedAtNanoseconds = 1_000L)
        val duplicate = probe.onVisible(diagnosticsAllowed = true, startedAtNanoseconds = 2_000L)

        assertEquals(first, duplicate)
        val measurement = probe.onViewportCommitted(
            generation = requireNotNull(first).generation,
            viewportState = PerformanceViewportState.Content,
            committedAtNanoseconds = 4_500L,
        )
        assertEquals(
            PerformanceExploreAppearanceKind.FirstProcessExplore,
            measurement?.sample?.processExploreKind,
        )
        assertEquals(3_500L, measurement?.durationNanoseconds)
        assertNull(
            probe.onViewportCommitted(
                generation = first.generation,
                viewportState = PerformanceViewportState.Content,
                committedAtNanoseconds = 5_000L,
            ),
        )
        assertNull(probe.onVisible(diagnosticsAllowed = true, startedAtNanoseconds = 6_000L))
    }

    @Test
    fun staleGenerationCannotCompleteTheSubsequentExploreSample() {
        val probe = ExploreFirstUsableViewportProbe()
        val firstProcessExplore = requireNotNull(probe.onVisible(true, 1_000L))
        probe.onHidden()
        val subsequentExplore = requireNotNull(probe.onVisible(true, 3_000L))

        assertEquals(
            PerformanceExploreAppearanceKind.SubsequentExplore,
            subsequentExplore.processExploreKind,
        )
        assertNull(
            probe.onViewportCommitted(
                firstProcessExplore.generation,
                PerformanceViewportState.Content,
                4_000L,
            ),
        )
        assertEquals(
            2_000L,
            probe.onViewportCommitted(subsequentExplore.generation, PerformanceViewportState.Empty, 5_000L)
                ?.durationNanoseconds,
        )
    }

    @Test
    fun backgroundOrDismissalCancelsWithoutExportingAndReentryIsSubsequentExplore() {
        val probe = ExploreFirstUsableViewportProbe()
        val firstProcessExplore = requireNotNull(probe.onVisible(true, 1_000L))

        probe.onHidden()

        assertNull(
            probe.onViewportCommitted(
                firstProcessExplore.generation,
                PerformanceViewportState.Offline,
                2_000L,
            ),
        )
        val subsequentExplore = requireNotNull(probe.onVisible(true, 3_000L))
        assertEquals(
            PerformanceExploreAppearanceKind.SubsequentExplore,
            subsequentExplore.processExploreKind,
        )
    }

    @Test
    fun deniedConsentAndClockRollbackFailClosed() {
        val deniedProbe = ExploreFirstUsableViewportProbe()
        assertNull(deniedProbe.onVisible(false, 1_000L))
        val firstAllowed = requireNotNull(deniedProbe.onVisible(true, 1_500L))
        assertEquals(
            PerformanceExploreAppearanceKind.FirstProcessExplore,
            firstAllowed.processExploreKind,
        )

        val rollbackProbe = ExploreFirstUsableViewportProbe()
        assertNull(rollbackProbe.onVisible(true, -1L))
        val rollback = requireNotNull(rollbackProbe.onVisible(true, 2_000L))
        assertNull(
            rollbackProbe.onViewportCommitted(
                rollback.generation,
                PerformanceViewportState.Content,
                1_999L,
            ),
        )
    }

    @Test
    fun measurementMapsToTheTypedNonPiiFirebaseContract() {
        val probe = ExploreFirstUsableViewportProbe()
        val sample = requireNotNull(probe.onVisible(true, 1_000_000L))
        val measurement = requireNotNull(
            probe.onViewportCommitted(
                generation = sample.generation,
                viewportState = PerformanceViewportState.Offline,
                committedAtNanoseconds = 2_234_567L,
            ),
        ).performanceMeasurement

        assertEquals(PerformanceTraceName.ExploreInitialLoad, measurement.traceName)
        assertEquals(PerformanceMetricName.FirstUsableViewportMicroseconds, measurement.metricName)
        assertEquals(1_234L, measurement.metricValue)
        assertEquals(
            PerformanceExploreAppearanceKind.FirstProcessExplore,
            measurement.processExploreKind,
        )
        assertEquals(PerformanceViewportState.Offline, measurement.viewportState)
    }

    @Test
    fun onlyRenderedUsableStatesCanFinishTheProbe() {
        val strings = stringsFor(AppLocale.French)
        val base = initialExploreUiState(strings)

        assertNull(base.copy(isLoading = true).firstUsableViewportState)
        assertNull(base.copy(isRefreshing = true).firstUsableViewportState)
        assertEquals(PerformanceViewportState.Empty, base.firstUsableViewportState)
        assertEquals(PerformanceViewportState.Offline, base.copy(isOffline = true).firstUsableViewportState)
        assertEquals(
            PerformanceViewportState.Error,
            base.copy(errorMessage = "network").firstUsableViewportState,
        )
        assertEquals(
            PerformanceViewportState.Content,
            base.copy(listings = sampleExploreUiState(strings).listings).firstUsableViewportState,
        )
        assertEquals(
            PerformanceViewportState.Content,
            base.copy(
                listings = sampleExploreUiState(strings).listings,
                isRefreshing = true,
            ).firstUsableViewportState,
        )
        assertEquals(
            PerformanceViewportState.Offline,
            base.copy(
                listings = sampleExploreUiState(strings).listings,
                isOffline = true,
            ).firstUsableViewportState,
        )
    }
}
