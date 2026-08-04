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

class AndroidObservabilityConsentRetryTest {
    @Test
    fun updateConsentPersistsTheAccountOwnerAndAppliesTheChoice() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore()
        val controller = AndroidObservabilityController(backend, store)
        controller.start()

        assertTrue(controller.updateConsent(TEST_USER_ID, ALL_OBSERVABILITY_GRANTED))
        controller.track(AnalyticsEvent(AnalyticsEventName.ViewCard))
        controller.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)
        controller.startTrace(PerformanceTraceName.ExploreInitialLoad).stop()

        assertEquals(TEST_USER_ID, store.read().ownerUserId)
        assertEquals(ALL_OBSERVABILITY_GRANTED, store.read().consent)
        assertEquals(1, backend.events.size)
        assertEquals(listOf(DiagnosticCode.UnexpectedApplicationState), backend.diagnostics)
        assertEquals(listOf(PerformanceTraceName.ExploreInitialLoad), backend.traces)
        assertTrue(backend.remoteConfigurationFetched)
        assertEquals(1, backend.remoteUpdateStartCount)
    }

    @Test
    fun remoteConfigurationOnlyChangeDoesNotPurgeStillGrantedChannels() {
        val backend = TestObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            TestConsentStore(ownerUserId = TEST_USER_ID, consent = ALL_OBSERVABILITY_GRANTED),
        )
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)
        controller.track(AnalyticsEvent(AnalyticsEventName.ViewCard))
        controller.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)

        assertTrue(
            controller.updateConsent(
                TEST_USER_ID,
                ALL_OBSERVABILITY_GRANTED.copy(remoteConfigurationAllowed = false),
            ),
        )

        assertEquals(1, backend.events.size)
        assertEquals(1, backend.diagnostics.size)
        assertEquals(0, backend.resetAnalyticsDataCount)
        assertEquals(0, backend.deleteUnsentReportsCount)
    }

    @Test
    fun updateConsentRejectsABlankAccountOwnerAndExposesTheFailure() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(ownerUserId = TEST_USER_ID, consent = ALL_OBSERVABILITY_GRANTED)
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertFalse(controller.updateConsent("   ", ALL_OBSERVABILITY_GRANTED))

        assertEquals(0, store.writeCount)
        assertEquals(ALL_OBSERVABILITY_GRANTED, controller.consent.value)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertTrue(controller.privacyOperationFailed.value)
    }

    @Test
    fun failedConsentWriteStaysClosedAfterRebindingTheSameAccount() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ALL_OBSERVABILITY_GRANTED,
            behavior = TestConsentStoreBehavior(writesSucceed = false),
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertFalse(
            controller.updateConsent(
                ownerUserId = TEST_USER_ID,
                updatedConsent = ObservabilityConsent(diagnosticsAllowed = true),
            ),
        )
        controller.bindToAuthenticatedUser(TEST_USER_ID)
        controller.track(AnalyticsEvent(AnalyticsEventName.ViewCard))
        controller.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)

        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertEquals(ObservabilityConsent(), controller.consent.value)
        assertTrue(controller.privacyOperationFailed.value)
        assertEquals(ALL_OBSERVABILITY_GRANTED, store.read().consent)
        assertEquals(emptyList(), backend.events)
        assertEquals(emptyList(), backend.diagnostics)
        assertEquals(1, backend.remoteUpdateStopCount)
    }

    @Test
    fun retryReplaysTheLastFailedConsentUpdate() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(behavior = TestConsentStoreBehavior(writesSucceed = false))
        val controller = AndroidObservabilityController(backend, store)
        val requestedConsent = ObservabilityConsent(analyticsAllowed = true)
        controller.start()

        assertFalse(controller.updateConsent(TEST_USER_ID, requestedConsent))
        assertEquals(ObservabilityConsent(), backend.appliedConsent)

        store.writesSucceed = true
        assertTrue(controller.retryPendingMaintenance())

        assertEquals(2, store.writeCount)
        assertEquals(TEST_USER_ID, store.read().ownerUserId)
        assertEquals(requestedConsent, controller.consent.value)
        assertEquals(requestedConsent, backend.appliedConsent)
        assertFalse(controller.privacyOperationFailed.value)
    }

    @Test
    fun retryReportsFailureWhileTheConsentWriteStillCannotPersist() {
        val store = TestConsentStore(behavior = TestConsentStoreBehavior(writesSucceed = false))
        val controller = AndroidObservabilityController(TestObservabilityBackend(), store)
        controller.start()
        assertFalse(
            controller.updateConsent(
                TEST_USER_ID,
                ObservabilityConsent(analyticsAllowed = true),
            ),
        )

        assertFalse(controller.retryPendingMaintenance())

        assertEquals(2, store.writeCount)
        assertTrue(controller.privacyOperationFailed.value)
        assertEquals(ObservabilityConsent(), controller.consent.value)
    }

    @Test
    fun pendingUpdateForOneAccountIsNeverReplayedForAnotherAccount() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ALL_OBSERVABILITY_GRANTED,
            behavior = TestConsentStoreBehavior(writesSucceed = false),
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)
        assertFalse(controller.updateConsent(TEST_USER_ID, ObservabilityConsent(diagnosticsAllowed = true)))

        store.writesSucceed = true
        controller.bindToAuthenticatedUser(TEST_OTHER_USER_ID)
        controller.retryPendingMaintenance()
        assertTrue(controller.updateConsent(TEST_OTHER_USER_ID, ObservabilityConsent(analyticsAllowed = true)))

        assertEquals(2, store.writeCount)
        assertEquals(TEST_USER_ID, store.writeAttempts.first().first)
        assertEquals(TEST_OTHER_USER_ID, store.writeAttempts.last().first)
        assertEquals(TEST_OTHER_USER_ID, store.read().ownerUserId)
        assertEquals(1, store.successfulRevocationCount)
    }

    @Test
    fun logoutReplacesAPendingUpdateWithASafeRevocation() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ALL_OBSERVABILITY_GRANTED,
            behavior = TestConsentStoreBehavior(writesSucceed = false),
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)
        assertFalse(controller.updateConsent(TEST_USER_ID, ObservabilityConsent(diagnosticsAllowed = true)))

        store.writesSucceed = true
        controller.bindToAuthenticatedUser(null)
        assertTrue(controller.retryPendingMaintenance())

        assertEquals(1, store.writeCount)
        assertNull(store.read().ownerUserId)
        assertEquals(1, store.successfulRevocationCount)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertFalse(controller.privacyOperationFailed.value)
    }

    @Test
    fun revokeStopsCollectionAndARecreatedControllerCannotRestoreTheOldChoice() {
        val store = TestConsentStore(ownerUserId = TEST_USER_ID, consent = ALL_OBSERVABILITY_GRANTED)
        val backend = TestObservabilityBackend()
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertTrue(controller.revokeAllConsent())
        controller.track(AnalyticsEvent(AnalyticsEventName.ViewCard))
        controller.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)

        assertEquals(StoredObservabilityConsent(null, ObservabilityConsent()), store.read())
        assertEquals(emptyList(), backend.events)
        assertEquals(emptyList(), backend.diagnostics)
        assertEquals(1, backend.remoteUpdateStopCount)

        val recreatedBackend = TestObservabilityBackend()
        val recreatedController = AndroidObservabilityController(recreatedBackend, store)
        recreatedController.start()
        recreatedController.bindToAuthenticatedUser(TEST_USER_ID)

        assertEquals(ObservabilityConsent(), recreatedController.consent.value)
        assertEquals(0, recreatedBackend.ensureConfiguredCount)
        assertFalse(recreatedBackend.remoteConfigurationFetched)
    }

    @Test
    fun failedRevocationStaysClosedAndRetryPersistsIt() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ALL_OBSERVABILITY_GRANTED,
            behavior = TestConsentStoreBehavior(revocationsSucceed = false),
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertFalse(controller.revokeAllConsent())
        assertEquals(ObservabilityConsent(), backend.appliedConsent)

        store.revocationsSucceed = true
        controller.retryPendingMaintenance()

        assertEquals(2, store.revocationCount)
        assertNull(store.read().ownerUserId)
        assertEquals(ObservabilityConsent(), controller.consent.value)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertFalse(controller.privacyOperationFailed.value)
    }

    @Test
    fun pendingRevocationRemainsPriorityAcrossAnAccountChange() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ALL_OBSERVABILITY_GRANTED,
            behavior = TestConsentStoreBehavior(revocationsSucceed = false),
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)
        assertFalse(controller.revokeAllConsent())

        controller.bindToAuthenticatedUser(TEST_OTHER_USER_ID)
        assertFalse(controller.updateConsent(TEST_OTHER_USER_ID, ALL_OBSERVABILITY_GRANTED))
        store.revocationsSucceed = true
        controller.retryPendingMaintenance()

        assertEquals(0, store.writeCount)
        assertNull(store.read().ownerUserId)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertFalse(controller.privacyOperationFailed.value)
    }

    @Test
    fun mismatchedAccountBindingFailureIsVisibleAndCanBeRetried() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ALL_OBSERVABILITY_GRANTED,
            behavior = TestConsentStoreBehavior(revocationsSucceed = false),
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()

        controller.bindToAuthenticatedUser(TEST_OTHER_USER_ID)

        assertTrue(controller.privacyOperationFailed.value)
        assertEquals(ObservabilityConsent(), controller.consent.value)
        assertEquals(TEST_USER_ID, store.read().ownerUserId)
        assertFalse(backend.remoteConfigurationFetched)

        store.revocationsSucceed = true
        controller.retryPendingMaintenance()

        assertFalse(controller.privacyOperationFailed.value)
        assertEquals(2, store.revocationCount)
        assertEquals(1, store.successfulRevocationCount)
        assertNull(store.read().ownerUserId)
        assertEquals(ObservabilityConsent(), store.read().consent)
    }
}
