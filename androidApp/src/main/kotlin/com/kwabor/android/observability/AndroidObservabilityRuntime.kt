package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName

internal class AndroidObservabilityRuntime(
    private val backend: AndroidObservabilityBackend,
    private val consentStore: ObservabilityConsentStore,
    stateLock: AndroidObservabilityStateLock,
    private val onPrivacyOperationFailed: (Boolean) -> Unit,
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
        onPrivacyOperationFailed = onPrivacyOperationFailed,
        onReconcile = ::reconcileLatest,
        suspendCollection = { applyEffectiveConsent(ObservabilityConsent()) },
    )
    private val diagnosticsReports = AndroidDiagnosticsReportCoordinator(
        backend = backend,
        consentStore = consentStore,
        stateLock = stateLock,
        onPrivacyOperationFailed = onPrivacyOperationFailed,
        onReconcile = ::reconcileLatest,
        isDiagnosticsAllowed = { effectiveConsent.diagnosticsAllowed },
    )

    val isConfigured: Boolean get() = backend.isConfigured

    fun suspendCollection() {
        diagnosticsReports.invalidateSession()
        applyEffectiveConsent(ObservabilityConsent())
    }

    fun setRestoredDiagnosticsSendPending(pending: Boolean) {
        diagnosticsReports.setRestoredSendPending(pending)
    }

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

    fun startTrace(name: PerformanceTraceName): PerformanceTrace =
        if (effectiveConsent.diagnosticsAllowed) backend.startTrace(name) else PerformanceTrace.None

    fun close() {
        remoteConfiguration.close()
    }

    private fun reconcileLatest() {
        val initialStored = consentStore.read()
        if (!ensureBackend(initialStored)) return
        val stored = clearAnalyticsPurge(initialStored) ?: return
        val installationDeletionRequestId = stored.installationDeletionRequestId
        if (installationDeletionRequestId != null) {
            applyEffectiveConsent(ObservabilityConsent())
            installationDeletion.resume(installationDeletionRequestId)
            return
        }

        val effective = desiredConsent.copy(
            diagnosticsAllowed = desiredConsent.diagnosticsAllowed && stored.diagnosticsReportPurgeRequestId == null,
        )
        applyEffectiveConsent(effective)
        if (diagnosticsReports.resumePurge(stored.diagnosticsReportPurgeRequestId)) return
        diagnosticsReports.resumeRestoredSend()
    }

    private fun ensureBackend(stored: StoredObservabilityConsent): Boolean {
        val requiresBackend = desiredConsent.allowsAnyCollection || stored.hasPendingMaintenance
        if (!requiresBackend) return true
        val configured = backend.isConfigured || backend.ensureConfigured()
        if (!configured) {
            backendConfigurationFailed = true
            onPrivacyOperationFailed(true)
            applyEffectiveConsent(ObservabilityConsent())
            return false
        }
        if (backendConfigurationFailed) {
            backendConfigurationFailed = false
            onPrivacyOperationFailed(false)
        }
        return true
    }

    private fun clearAnalyticsPurge(stored: StoredObservabilityConsent): StoredObservabilityConsent? {
        if (!stored.analyticsPurgePending || !backend.isConfigured) return stored
        applyEffectiveConsent(ObservabilityConsent())
        backend.resetAnalyticsData()
        if (!consentStore.clearAnalyticsPurgePending()) {
            onPrivacyOperationFailed(true)
            return null
        }
        onPrivacyOperationFailed(false)
        return consentStore.read()
    }

    private fun applyEffectiveConsent(updatedConsent: ObservabilityConsent) {
        val previousConsent = effectiveConsent
        effectiveConsent = updatedConsent
        if (backend.isConfigured) backend.applyConsent(updatedConsent)
        remoteConfiguration.transition(previousConsent, updatedConsent)
    }
}

private val ObservabilityConsent.allowsAnyCollection: Boolean
    get() = analyticsAllowed || diagnosticsAllowed || remoteConfigurationAllowed
