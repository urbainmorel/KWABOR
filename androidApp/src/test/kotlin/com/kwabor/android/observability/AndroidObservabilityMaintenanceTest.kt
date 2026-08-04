package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidObservabilityMaintenanceTest {
    @Test
    fun staleDiagnosticsPurgeCallbackCannotClearANewerRequest() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ObservabilityConsent(diagnosticsAllowed = true),
            diagnosticsReportPurgeRequestId = OLD_DIAGNOSTICS_REQUEST_ID,
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertEquals(1, backend.diagnosticsCheckCount)
        store.replaceDiagnosticsPurgeRequest(NEW_DIAGNOSTICS_REQUEST_ID)
        backend.completeNextDiagnosticsCheck(DiagnosticsReportCheckResult.Success(hasUnsentReports = false))

        assertEquals(1, backend.deleteUnsentReportsCount)
        assertEquals(NEW_DIAGNOSTICS_REQUEST_ID, store.read().diagnosticsReportPurgeRequestId)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertFalse(controller.privacyOperationFailed.value)
        assertEquals(1, backend.diagnosticsCheckCount)
    }

    @Test
    fun diagnosticsPurgeFailureIsVisibleAndRetryCanCompleteIt() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ObservabilityConsent(diagnosticsAllowed = true),
            diagnosticsReportPurgeRequestId = OLD_DIAGNOSTICS_REQUEST_ID,
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        backend.completeNextDiagnosticsCheck(DiagnosticsReportCheckResult.Failure)

        assertTrue(controller.privacyOperationFailed.value)
        assertEquals(OLD_DIAGNOSTICS_REQUEST_ID, store.read().diagnosticsReportPurgeRequestId)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)

        controller.retryPendingMaintenance()

        assertEquals(2, backend.diagnosticsCheckCount)
        backend.completeNextDiagnosticsCheck(DiagnosticsReportCheckResult.Success(hasUnsentReports = false))

        assertFalse(controller.privacyOperationFailed.value)
        assertNull(store.read().diagnosticsReportPurgeRequestId)
        assertEquals(ObservabilityConsent(diagnosticsAllowed = true), backend.appliedConsent)
    }

    @Test
    fun diagnosticsPurgeWithReportsKeepsTheDurableRequestForTheNextProcess() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ObservabilityConsent(diagnosticsAllowed = true),
            diagnosticsReportPurgeRequestId = OLD_DIAGNOSTICS_REQUEST_ID,
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        backend.completeNextDiagnosticsCheck(DiagnosticsReportCheckResult.Success(hasUnsentReports = true))
        controller.retryPendingMaintenance()

        assertEquals(1, backend.deleteUnsentReportsCount)
        assertEquals(1, backend.diagnosticsCheckCount)
        assertEquals(OLD_DIAGNOSTICS_REQUEST_ID, store.read().diagnosticsReportPurgeRequestId)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
    }

    @Test
    fun failedInstallationDeletionRemainsVisibleAndRetriesBeforeRestoringCollection() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ObservabilityConsent(analyticsAllowed = true),
            installationDeletionRequestId = OLD_INSTALLATION_REQUEST_ID,
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertEquals(1, backend.installationDeletionCount)
        backend.completeNextInstallationDeletion(succeeded = false)

        assertTrue(controller.privacyOperationFailed.value)
        assertEquals(OLD_INSTALLATION_REQUEST_ID, store.read().installationDeletionRequestId)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)

        controller.retryPendingMaintenance()

        assertEquals(2, backend.installationDeletionCount)
        backend.completeNextInstallationDeletion(succeeded = true)

        assertFalse(controller.privacyOperationFailed.value)
        assertNull(store.read().installationDeletionRequestId)
        assertEquals(ObservabilityConsent(analyticsAllowed = true), backend.appliedConsent)
    }

    @Test
    fun staleInstallationDeletionCallbackCannotAcknowledgeANewerRequest() {
        val backend = TestObservabilityBackend()
        val store = TestConsentStore(
            ownerUserId = TEST_USER_ID,
            consent = ObservabilityConsent(remoteConfigurationAllowed = true),
            installationDeletionRequestId = OLD_INSTALLATION_REQUEST_ID,
        )
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        store.replaceInstallationDeletionRequest(NEW_INSTALLATION_REQUEST_ID)
        backend.completeNextInstallationDeletion(succeeded = true)

        assertEquals(NEW_INSTALLATION_REQUEST_ID, store.read().installationDeletionRequestId)
        assertEquals(2, backend.installationDeletionCount)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)

        backend.completeNextInstallationDeletion(succeeded = true)

        assertNull(store.read().installationDeletionRequestId)
        assertEquals(ObservabilityConsent(remoteConfigurationAllowed = true), backend.appliedConsent)
        assertTrue(backend.remoteConfigurationFetched)
    }

    @Test
    fun failedGenericRemoteConfigurationFetchIsReportedOnlyWithDiagnosticsConsent() {
        val backend = TestObservabilityBackend(fetchSucceeds = false)
        val controller = AndroidObservabilityController(
            backend,
            TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ObservabilityConsent(
                    diagnosticsAllowed = true,
                    remoteConfigurationAllowed = true,
                ),
            ),
        )
        controller.start()

        controller.bindToAuthenticatedUser(TEST_USER_ID)

        assertEquals(listOf(DiagnosticCode.RemoteConfigurationFetchFailed), backend.diagnostics)
    }

    @Test
    fun staleRemoteConfigurationCallbackCannotReportAfterConsentRevocation() {
        val backend = TestObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ObservabilityConsent(
                    diagnosticsAllowed = true,
                    remoteConfigurationAllowed = true,
                ),
            ),
        )
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        controller.updateConsent(TEST_USER_ID, ObservabilityConsent(diagnosticsAllowed = true))
        backend.emitStaleRemoteUpdate(succeeded = false)

        assertEquals(emptyList(), backend.diagnostics)
    }

    @Test
    fun genericRealtimeRemoteConfigurationFailureIsReportedWhileConsentRemainsGranted() {
        val backend = TestObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            TestConsentStore(
                ownerUserId = TEST_USER_ID,
                consent = ObservabilityConsent(
                    diagnosticsAllowed = true,
                    remoteConfigurationAllowed = true,
                ),
            ),
        )
        controller.start()
        controller.bindToAuthenticatedUser(TEST_USER_ID)

        backend.emitRemoteUpdate(succeeded = false)

        assertEquals(listOf(DiagnosticCode.RemoteConfigurationFetchFailed), backend.diagnostics)
    }
}
