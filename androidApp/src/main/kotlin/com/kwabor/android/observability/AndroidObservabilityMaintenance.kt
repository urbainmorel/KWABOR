package com.kwabor.android.observability

internal class AndroidInstallationDeletionCoordinator(
    private val backend: AndroidObservabilityBackend,
    private val consentStore: ObservabilityConsentStore,
    private val stateLock: AndroidObservabilityStateLock,
    private val onPrivacyOperationFailed: (Boolean) -> Unit,
    private val onReconcile: () -> Unit,
    private val suspendCollection: () -> Unit,
) {
    private var inFlightRequestId: String? = null

    fun resume(requestId: String) {
        if (!backend.isConfigured || inFlightRequestId != null) return
        inFlightRequestId = requestId
        backend.deleteInstallation { succeeded ->
            stateLock.hold { complete(requestId, succeeded) }
        }
    }

    private fun complete(requestId: String, succeeded: Boolean) {
        if (inFlightRequestId != requestId) return
        inFlightRequestId = null
        if (!succeeded) {
            if (consentStore.read().installationDeletionRequestId != requestId) {
                onReconcile()
                return
            }
            onPrivacyOperationFailed(true)
            suspendCollection()
            return
        }
        when (consentStore.completeInstallationDeletion(requestId)) {
            InstallationDeletionCompletion.Completed -> {
                onPrivacyOperationFailed(false)
                onReconcile()
            }
            InstallationDeletionCompletion.Superseded -> onReconcile()
            InstallationDeletionCompletion.Failure -> {
                onPrivacyOperationFailed(true)
                suspendCollection()
            }
        }
    }
}

internal class AndroidDiagnosticsReportCoordinator(
    private val backend: AndroidObservabilityBackend,
    private val consentStore: ObservabilityConsentStore,
    private val stateLock: AndroidObservabilityStateLock,
    private val onPrivacyOperationFailed: (Boolean) -> Unit,
    private val onReconcile: () -> Unit,
    private val isDiagnosticsAllowed: () -> Boolean,
) {
    private var processState: DiagnosticsReportProcessState = DiagnosticsReportProcessState.Available
    private var sessionGeneration = 0L
    private var restoredSendPending = false

    fun invalidateSession() {
        sessionGeneration += 1
        restoredSendPending = false
    }

    fun setRestoredSendPending(pending: Boolean) {
        restoredSendPending = pending
    }

    fun resumePurge(requestId: String?): Boolean {
        if (requestId == null) return false
        if (backend.isConfigured && processState == DiagnosticsReportProcessState.Available) {
            processState = DiagnosticsReportProcessState.CheckingPurge(requestId)
            backend.checkForUnsentReports { result ->
                stateLock.hold { completePurgeCheck(requestId, result) }
            }
        }
        return true
    }

    fun resumeRestoredSend() {
        if (!restoredSendPending || !isDiagnosticsAllowed() || !backend.isConfigured) return
        if (processState != DiagnosticsReportProcessState.Available) return
        val generation = sessionGeneration
        processState = DiagnosticsReportProcessState.CheckingSend(generation)
        backend.checkForUnsentReports { result ->
            stateLock.hold { completeSendCheck(generation, result) }
        }
    }

    private fun completePurgeCheck(requestId: String, result: DiagnosticsReportCheckResult) {
        if (processState != DiagnosticsReportProcessState.CheckingPurge(requestId)) return
        when (result) {
            DiagnosticsReportCheckResult.Failure -> {
                processState = DiagnosticsReportProcessState.Available
                onPrivacyOperationFailed(true)
            }
            is DiagnosticsReportCheckResult.Success -> completeSuccessfulPurge(requestId, result.hasUnsentReports)
        }
    }

    private fun completeSuccessfulPurge(requestId: String, hasUnsentReports: Boolean) {
        backend.deleteUnsentReports()
        processState = DiagnosticsReportProcessState.Consumed
        onPrivacyOperationFailed(false)
        if (hasUnsentReports) return
        restoredSendPending = false
        when (consentStore.completeDiagnosticsReportPurge(requestId)) {
            InstallationDeletionCompletion.Completed -> {
                onPrivacyOperationFailed(false)
                onReconcile()
            }
            InstallationDeletionCompletion.Superseded -> onReconcile()
            InstallationDeletionCompletion.Failure -> onPrivacyOperationFailed(true)
        }
    }

    private fun completeSendCheck(generation: Long, result: DiagnosticsReportCheckResult) {
        if (processState != DiagnosticsReportProcessState.CheckingSend(generation)) return
        when (result) {
            DiagnosticsReportCheckResult.Failure -> completeFailedSend(generation)
            is DiagnosticsReportCheckResult.Success -> completeSuccessfulSend(generation, result.hasUnsentReports)
        }
    }

    private fun completeFailedSend(generation: Long) {
        processState = DiagnosticsReportProcessState.Available
        if (generation == sessionGeneration) onPrivacyOperationFailed(true) else onReconcile()
    }

    private fun completeSuccessfulSend(generation: Long, hasUnsentReports: Boolean) {
        processState = DiagnosticsReportProcessState.Consumed
        if (generation != sessionGeneration || !isDiagnosticsAllowed()) {
            onReconcile()
            return
        }
        restoredSendPending = false
        onPrivacyOperationFailed(false)
        if (hasUnsentReports) backend.sendUnsentReports()
    }
}

private sealed interface DiagnosticsReportProcessState {
    data object Available : DiagnosticsReportProcessState

    data class CheckingPurge(val requestId: String) : DiagnosticsReportProcessState

    data class CheckingSend(val generation: Long) : DiagnosticsReportProcessState

    data object Consumed : DiagnosticsReportProcessState
}
