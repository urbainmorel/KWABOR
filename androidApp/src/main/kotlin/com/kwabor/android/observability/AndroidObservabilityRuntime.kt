package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.ConsentedAppSessionTracker
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent

internal class AndroidObservabilityRuntime(
    private val backend: AndroidObservabilityBackend,
    private val consentStore: ObservabilityConsentStore,
    private val sessionTracker: ConsentedAppSessionTracker?,
    stateLock: AndroidObservabilityStateLock,
    private val callbacks: AndroidObservabilityRuntimeCallbacks,
) {
    private var desiredConsent = ObservabilityConsent()
    private var effectiveConsent = ObservabilityConsent()
    private var backendConfigurationFailed = false
    private val remoteConfiguration = AndroidRemoteConfigurationCoordinator(
        backend = backend,
        stateLock = stateLock,
        isRemoteConfigurationAllowed = { effectiveConsent.remoteConfigurationAllowed },
        reportFailure = {
            if (effectiveConsent.diagnosticsAllowed) {
                backend.recordDiagnostic(DiagnosticCode.RemoteConfigurationFetchFailed)
            }
        },
    )
    private val installationDeletion = AndroidInstallationDeletionCoordinator(
        backend = backend,
        consentStore = consentStore,
        stateLock = stateLock,
        onPrivacyOperationFailed = callbacks.onPrivacyOperationFailed,
        onReconcile = callbacks.onMaintenanceChanged,
        suspendCollection = { applyEffectiveConsent(ObservabilityConsent()) },
    )
    private val diagnosticsReports = AndroidDiagnosticsReportCoordinator(
        backend = backend,
        consentStore = consentStore,
        stateLock = stateLock,
        onPrivacyOperationFailed = callbacks.onPrivacyOperationFailed,
        onReconcile = callbacks.onMaintenanceChanged,
        isDiagnosticsAllowed = { effectiveConsent.diagnosticsAllowed },
    )

    val isConfigured: Boolean get() = backend.isConfigured
    val isPerformanceCollectionAllowed: Boolean get() = effectiveConsent.diagnosticsAllowed

    fun suspendCollection() {
        diagnosticsReports.invalidateSession()
        applyEffectiveConsent(ObservabilityConsent())
    }

    fun setRestoredDiagnosticsSendPending(pending: Boolean) {
        diagnosticsReports.setRestoredSendPending(pending)
    }

    fun updateForegroundState(isForeground: Boolean) {
        if (isForeground) {
            sessionTracker?.onForeground()?.let(backend::trackObservedSession)
        } else {
            sessionTracker?.onBackground()
        }
    }

    fun revokeObservedSession(): Boolean = sessionTracker?.revoke() ?: true

    fun reconcile(updatedDesiredConsent: ObservabilityConsent) {
        desiredConsent = updatedDesiredConsent
        reconcileLatest()
    }

    fun track(event: AnalyticsEvent) {
        if (effectiveConsent.analyticsAllowed) backend.track(event)
    }

    fun recordDiagnostic(code: DiagnosticCode) {
        if (effectiveConsent.diagnosticsAllowed) backend.recordDiagnostic(code)
    }

    fun close() {
        remoteConfiguration.close()
    }

    private fun reconcileLatest() {
        val initialStored = consentStore.read()
        if (initialStored.sessionCheckpointPurgePending) {
            applyEffectiveConsent(ObservabilityConsent())
        } else if (ensureBackend(initialStored)) {
            reconcileConfiguredRuntime(initialStored)
        }
    }

    private fun reconcileConfiguredRuntime(initialStored: StoredObservabilityConsent) {
        val stored = clearAnalyticsPurge(initialStored) ?: return
        val installationDeletionRequestId = stored.installationDeletionRequestId
        if (installationDeletionRequestId != null) {
            applyEffectiveConsent(ObservabilityConsent())
            installationDeletion.resume(installationDeletionRequestId)
            return
        }

        if (stored.isReadyForStagedActivation && callbacks.onMaintenanceReady()) {
            return
        }

        val effective = desiredConsent.copy(
            diagnosticsAllowed = desiredConsent.diagnosticsAllowed && stored.diagnosticsReportPurgeRequestId == null,
        )
        applyEffectiveConsent(effective)
        if (!diagnosticsReports.resumePurge(stored.diagnosticsReportPurgeRequestId)) {
            diagnosticsReports.resumeRestoredSend()
        }
    }

    private fun ensureBackend(stored: StoredObservabilityConsent): Boolean {
        val requiresBackend = desiredConsent.allowsAnyCollection || stored.hasPendingBackendMaintenance
        if (!requiresBackend) return true
        val configured = backend.isConfigured || backend.ensureConfigured()
        if (!configured) {
            backendConfigurationFailed = true
            callbacks.onPrivacyOperationFailed(true)
            applyEffectiveConsent(ObservabilityConsent())
            return false
        }
        if (backendConfigurationFailed) {
            backendConfigurationFailed = false
            callbacks.onPrivacyOperationFailed(false)
        }
        return true
    }

    private fun clearAnalyticsPurge(stored: StoredObservabilityConsent): StoredObservabilityConsent? {
        if (!stored.analyticsPurgePending || !backend.isConfigured) return stored
        applyEffectiveConsent(ObservabilityConsent())
        backend.resetAnalyticsData()
        if (!consentStore.clearAnalyticsPurgePending()) {
            callbacks.onPrivacyOperationFailed(true)
            return null
        }
        callbacks.onPrivacyOperationFailed(false)
        return consentStore.read()
    }

    private fun applyEffectiveConsent(updatedConsent: ObservabilityConsent) {
        val previousConsent = effectiveConsent
        effectiveConsent = updatedConsent
        callbacks.onPerformanceCollectionAllowedChanged(updatedConsent.diagnosticsAllowed)
        if (backend.isConfigured) backend.applyConsent(updatedConsent)
        remoteConfiguration.transition(previousConsent, updatedConsent)
        sessionTracker
            ?.updateMeasurementEligibility(
                allowed = updatedConsent.allowsObservedSessionMeasurement,
            )
            ?.let(backend::trackObservedSession)
    }
}

internal data class AndroidObservabilityRuntimeCallbacks(
    val onPrivacyOperationFailed: (Boolean) -> Unit,
    val onPerformanceCollectionAllowedChanged: (Boolean) -> Unit,
    val onMaintenanceChanged: () -> Unit,
    val onMaintenanceReady: () -> Boolean,
)

private val StoredObservabilityConsent.isReadyForStagedActivation: Boolean
    get() =
        diagnosticsReportPurgeRequestId == null &&
            !analyticsPurgePending &&
            hasStagedConsentActivation

private val ObservabilityConsent.allowsAnyCollection: Boolean
    get() = analyticsAllowed || diagnosticsAllowed || remoteConfigurationAllowed
