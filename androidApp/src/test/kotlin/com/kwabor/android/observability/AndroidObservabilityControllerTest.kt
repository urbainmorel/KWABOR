package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun pendingForegroundEmitsOneSessionOnlyAfterBothRequiredConsentsAreEffective() {
        val backend = TestObservabilityBackend()
        val clock = MutableObservabilityClock()
        val sessionStore = TestObservedAppSessionStore()
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ObservabilityConsent(analyticsAllowed = true),
            ),
            sessionTracker = testObservedAppSessionTracker(clock, sessionStore),
        )
        controller.start()
        controller.updateForegroundState(isForeground = true)
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertEquals(emptyList(), backend.observedSessions)

        assertTrue(controller.updateConsent(TEST_USER_ID, ALL_OBSERVABILITY_GRANTED))
        controller.updateForegroundState(isForeground = true)

        assertEquals(1, backend.observedSessions.size)
    }

    @Test
    fun failedSessionClearKeepsRevocationOffUntilRetryAndExplicitRegrant() {
        val backend = TestObservabilityBackend()
        val clock = MutableObservabilityClock()
        val sessionStore = TestObservedAppSessionStore(clearsSucceed = false)
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ALL_OBSERVABILITY_GRANTED,
            ),
            sessionTracker = testObservedAppSessionTracker(clock, sessionStore),
        )
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)
        controller.updateForegroundState(isForeground = true)
        controller.updateForegroundState(isForeground = false)

        assertFalse(controller.revokeAllConsent())
        assertEquals(ObservabilityConsent(), backend.appliedConsent)

        sessionStore.clearsSucceed = true
        assertTrue(controller.retryPendingMaintenance())
        controller.updateForegroundState(isForeground = true)

        assertEquals(1, backend.observedSessions.size)
        assertEquals(2, sessionStore.clearCount)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertTrue(controller.updateConsent(TEST_USER_ID, ALL_OBSERVABILITY_GRANTED))
        assertEquals(2, backend.observedSessions.size)
    }

    @Test
    fun changingAuthenticatedAccountClearsSessionCheckpointAndStopsMeasurement() {
        val backend = TestObservabilityBackend()
        val clock = MutableObservabilityClock()
        val sessionStore = TestObservedAppSessionStore()
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ALL_OBSERVABILITY_GRANTED,
            ),
            sessionTracker = testObservedAppSessionTracker(clock, sessionStore),
        )
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)
        controller.updateForegroundState(isForeground = true)
        controller.updateForegroundState(isForeground = false)

        controller.bindToAuthenticatedUser(TEST_OTHER_USER_ID)
        controller.updateForegroundState(isForeground = true)

        assertEquals(1, backend.observedSessions.size)
        assertEquals(1, sessionStore.clearCount)
        assertNull(sessionStore.checkpoint)
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
