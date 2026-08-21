package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceMeasurement
import com.kwabor.shared.domain.observability.PerformanceMetricName
import com.kwabor.shared.domain.observability.PerformanceSampleKind
import com.kwabor.shared.domain.observability.PerformanceTraceName
import com.kwabor.shared.domain.observability.PerformanceViewportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidObservabilityControllerTest {
    @Test
    fun freshInstallWithoutConfigurationDoesNotInitializeFirebase() {
        val backend = TestObservabilityBackend(ensureConfiguredSucceeds = false)
        val controller = AndroidObservabilityController(backend, TestConsentStore())

        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertEquals(0, backend.ensureConfiguredCount)
        assertFalse(backend.isConfigured)
        assertEquals(emptyList(), backend.appliedConsents)
        assertEquals(ObservabilityConsent(), controller.consent.value)
        assertFalse(controller.privacyOperationFailed.value)
    }

    @Test
    fun backendConfigurationFailureIsVisibleAndCanBeRetried() {
        val backend = TestObservabilityBackend(ensureConfiguredSucceeds = false)
        val controller = AndroidObservabilityController(
            backend,
            TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ObservabilityConsent(analyticsAllowed = true),
            ),
        )
        controller.start()

        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertTrue(controller.privacyOperationFailed.value)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertEquals(1, backend.ensureConfiguredCount)

        backend.ensureConfiguredSucceeds = true
        controller.retryPendingMaintenance()

        assertFalse(controller.privacyOperationFailed.value)
        assertEquals(ObservabilityConsent(analyticsAllowed = true), backend.appliedConsent)
        assertEquals(2, backend.ensureConfiguredCount)
    }

    @Test
    fun startKeepsCollectionDisabledEvenWhenAStoredConsentExists() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(ownerUserId = TEST_USER_ID, consent = ALL_OBSERVABILITY_GRANTED)
        val controller = AndroidObservabilityController(backend, store)

        controller.start()
        controller.track(AnalyticsEvent(AnalyticsEventName.ViewCard))
        controller.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)

        assertEquals(ObservabilityConsent(), controller.consent.value)
        assertEquals(0, backend.ensureConfiguredCount)
        assertEquals(TEST_USER_ID, store.read().ownerUserId)
        assertEquals(ALL_OBSERVABILITY_GRANTED, store.read().consent)
        assertEquals(emptyList(), backend.events)
        assertEquals(emptyList(), backend.diagnostics)
        assertFalse(backend.remoteConfigurationFetched)
        assertFalse(backend.remoteUpdatesStarted)
    }

    @Test
    fun matchingAuthenticatedUserRestoresStoredConsentAndGatesEveryBackendCapability() {
        val backend = TestObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            TestConsentStore(ownerUserId = TEST_USER_ID, consent = ALL_OBSERVABILITY_GRANTED),
        )
        controller.start()

        controller.bindToAuthenticatedUser(TEST_USER_ID)
        assertEquals(1, backend.diagnosticsCheckCount)
        backend.completeNextDiagnosticsCheck(
            DiagnosticsReportCheckResult.Success(hasUnsentReports = true),
        )
        controller.track(AnalyticsEvent(AnalyticsEventName.ViewCard))
        controller.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)
        controller.startTrace(PerformanceTraceName.ExploreInitialLoad).stop()

        assertEquals(ALL_OBSERVABILITY_GRANTED, controller.consent.value)
        assertEquals(ALL_OBSERVABILITY_GRANTED, backend.appliedConsent)
        assertEquals(1, backend.ensureConfiguredCount)
        assertEquals(1, backend.sendUnsentReportsCount)
        assertEquals(1, backend.events.size)
        assertEquals(listOf(DiagnosticCode.UnexpectedApplicationState), backend.diagnostics)
        assertEquals(listOf(PerformanceTraceName.ExploreInitialLoad), backend.traces)
        assertTrue(backend.remoteConfigurationFetched)
        assertEquals(1, backend.remoteUpdateStartCount)
    }

    @Test
    fun performanceMeasurementRequiresEffectiveDiagnosticsConsentAtEmission() {
        val backend = TestObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ObservabilityConsent(diagnosticsAllowed = true),
            ),
        )
        val measurement = PerformanceMeasurement(
            traceName = PerformanceTraceName.ExploreInitialLoad,
            metricName = PerformanceMetricName.FirstUsableViewportMicroseconds,
            metricValue = 1_250_000L,
            sampleKind = PerformanceSampleKind.Cold,
            viewportState = PerformanceViewportState.Content,
        )
        controller.start()

        assertFalse(controller.performanceCollectionAllowed.value)
        controller.recordPerformanceMeasurement(measurement)
        controller.bindToAuthenticatedUser(TEST_USER_ID)
        assertTrue(controller.performanceCollectionAllowed.value)
        controller.recordPerformanceMeasurement(measurement)
        assertTrue(controller.updateConsent(TEST_USER_ID, ObservabilityConsent()))
        assertFalse(controller.performanceCollectionAllowed.value)
        controller.recordPerformanceMeasurement(measurement)

        assertEquals(listOf(measurement), backend.performanceMeasurements)
        assertFalse(controller.performanceCollectionAllowed.value)
    }

    @Test
    fun staleRestoredDiagnosticsCheckCannotSendReportsAfterConsentRevocation() {
        val backend = TestObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ObservabilityConsent(diagnosticsAllowed = true),
            ),
        )
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertEquals(1, backend.diagnosticsCheckCount)
        assertTrue(controller.updateConsent(TEST_USER_ID, ObservabilityConsent()))
        backend.completeNextDiagnosticsCheck(
            DiagnosticsReportCheckResult.Success(hasUnsentReports = true),
        )

        assertEquals(0, backend.sendUnsentReportsCount)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
    }

    @Test
    fun missingAuthenticatedUserSuspendsRuntimeConsentWithoutDeletingStoredChoice() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(ownerUserId = TEST_USER_ID, consent = ALL_OBSERVABILITY_GRANTED)
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        controller.bindToAuthenticatedUser(null)

        assertEquals(ObservabilityConsent(), controller.consent.value)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertEquals(TEST_USER_ID, store.read().ownerUserId)
        assertEquals(ALL_OBSERVABILITY_GRANTED, store.read().consent)
        assertEquals(1, backend.remoteUpdateStopCount)
    }

    @Test
    fun differentAuthenticatedUserRevokesThePreviousAccountsStoredChoice() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(ownerUserId = TEST_USER_ID, consent = ALL_OBSERVABILITY_GRANTED)
        val controller = AndroidObservabilityController(backend, store)
        controller.start()

        controller.bindToAuthenticatedUser(TEST_OTHER_USER_ID)

        assertEquals(StoredObservabilityConsent(null, ObservabilityConsent()), store.read())
        assertEquals(ObservabilityConsent(), controller.consent.value)
        assertEquals(1, store.revocationCount)
        assertFalse(controller.privacyOperationFailed.value)
        assertFalse(backend.remoteConfigurationFetched)
    }

    @Test
    fun closeRemovesTheGenericRealtimeRemoteConfigurationListener() {
        val backend = TestObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ObservabilityConsent(remoteConfigurationAllowed = true),
            ),
        )
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        controller.close()

        assertEquals(1, backend.remoteUpdateStopCount)
    }
}
