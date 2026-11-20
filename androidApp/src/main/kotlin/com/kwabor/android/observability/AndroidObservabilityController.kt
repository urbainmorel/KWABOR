package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.ConsentedAppSessionTracker
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.ObservedAppSession
import com.kwabor.shared.domain.observability.PerformanceMeasurement
import com.kwabor.shared.domain.observability.PerformanceTraceName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidObservabilityController internal constructor(
    backend: AndroidObservabilityBackend,
    private val consentStore: ObservabilityConsentStore,
    sessionTracker: ConsentedAppSessionTracker? = null,
) {
    private val stateLock = AndroidObservabilityStateLock()
    private val mutableConsent = MutableStateFlow(ObservabilityConsent())
    private val mutablePrivacyOperationFailed = MutableStateFlow(false)
    private val mutablePerformanceCollectionAllowed = MutableStateFlow(false)
    private var requestedUserId: String? = null
    private var boundUserId: String? = null
    private var pendingConsentMutation: PendingConsentMutation? = null
    private var runtimeSuspendedAfterPersistenceFailure = false
    private var hasStarted = false
    private val runtime = AndroidObservabilityRuntime(
        backend = backend,
        consentStore = consentStore,
        sessionTracker = sessionTracker,
        stateLock = stateLock,
        callbacks = AndroidObservabilityRuntimeCallbacks(
            onPrivacyOperationFailed = { failed ->
                mutablePrivacyOperationFailed.value =
                    failed || runtimeSuspendedAfterPersistenceFailure || pendingConsentMutation != null
            },
            onPerformanceCollectionAllowedChanged = { allowed ->
                mutablePerformanceCollectionAllowed.value = allowed
            },
            onMaintenanceChanged = { reconcileRuntime() },
            onMaintenanceReady = ::activateStagedConsentIfReady,
        ),
    )

    val consent: StateFlow<ObservabilityConsent> = mutableConsent.asStateFlow()
    val privacyOperationFailed: StateFlow<Boolean> = mutablePrivacyOperationFailed.asStateFlow()
    val performanceCollectionAllowed: StateFlow<Boolean> = mutablePerformanceCollectionAllowed.asStateFlow()
    internal val performance = AndroidPerformanceController(
        backend = backend,
        stateLock = stateLock,
        isCollectionAllowed = { runtime.isPerformanceCollectionAllowed },
    )
    val isConfigured: Boolean get() = runtime.isConfigured

    fun start() = stateLock.hold {
        check(!hasStarted) { "The observability controller can only be started once." }
        hasStarted = true
        runtime.suspendCollection()
        reconcileRuntime()
    }

    fun bindToAuthenticatedUser(userId: String?) = stateLock.hold {
        val normalizedUserId = userId.normalizedOrNull()
        val pendingUpdate = pendingConsentMutation as? PendingConsentMutation.Update
        if (pendingUpdate != null && pendingUpdate.ownerUserId != normalizedUserId) {
            pendingConsentMutation = PendingConsentMutation.Revoke
        }
        requestedUserId = normalizedUserId
        if (pendingConsentMutation == PendingConsentMutation.Revoke) {
            boundUserId = null
            runtime.suspendCollection()
            mutableConsent.value = ObservabilityConsent()
            mutablePrivacyOperationFailed.value = true
            reconcileRuntime()
        } else {
            bindRequestedUser()
        }
    }

    fun updateConsent(ownerUserId: String, updatedConsent: ObservabilityConsent): Boolean = stateLock.hold {
        val normalizedUserId = ownerUserId.normalizedOrNull()
        if (normalizedUserId == null || pendingConsentMutation == PendingConsentMutation.Revoke) {
            runtime.suspendCollection()
            runtimeSuspendedAfterPersistenceFailure = true
            mutablePrivacyOperationFailed.value = true
            return@hold false
        }
        attemptConsentUpdate(PendingConsentMutation.Update(normalizedUserId, updatedConsent))
    }

    fun revokeAllConsent(): Boolean = stateLock.hold {
        attemptConsentRevocation(clearRequestedUser = true)
    }

    fun retryPendingMaintenance(): Boolean = stateLock.hold {
        when (val pending = pendingConsentMutation) {
            is PendingConsentMutation.Update -> attemptConsentUpdate(pending)
            PendingConsentMutation.Revoke -> attemptConsentRevocation(clearRequestedUser = false)
            null -> {
                val stored = consentStore.read()
                if (stored.sessionCheckpointPurgePending || stored.hasStagedConsentActivation) {
                    bindRequestedUser()
                } else if (requestedUserId != null && boundUserId == null) {
                    bindRequestedUser()
                } else {
                    reconcileRuntime()
                }
            }
        }
        !mutablePrivacyOperationFailed.value
    }

    fun updateForegroundState(isForeground: Boolean) = stateLock.hold {
        runtime.updateForegroundState(isForeground)
    }

    fun track(event: AnalyticsEvent) = stateLock.hold {
        runtime.track(event)
    }

    fun recordDiagnostic(code: DiagnosticCode) = stateLock.hold {
        runtime.recordDiagnostic(code)
    }

    fun close() = stateLock.hold {
        runtime.close()
    }

    private fun bindRequestedUser(): Boolean {
        runtime.suspendCollection()
        val userId = requestedUserId
        return if (userId == null) {
            boundUserId = null
            mutableConsent.value = ObservabilityConsent()
            clearDisabledSessionCheckpointAndReconcile()
        } else {
            bindAuthenticatedUser(userId)
        }
    }

    private val bindAuthenticatedUser: (String) -> Boolean = binding@{ userId ->
        var stored = consentStore.read()
        if (stored.hasStagedActivationForAnotherUser(userId)) {
            pendingConsentMutation = PendingConsentMutation.Revoke
            return@binding attemptConsentRevocation(clearRequestedUser = false)
        }
        val storedOwner = stored.persistedOwnerUserId
        val ownerChanged = storedOwner != null && storedOwner != userId
        if (ownerChanged && !stageRevocationAndClearSession()) {
            boundUserId = null
            mutableConsent.value = ObservabilityConsent()
            runtimeSuspendedAfterPersistenceFailure = true
            mutablePrivacyOperationFailed.value = true
            reconcileRuntime()
            return@binding false
        }
        if (ownerChanged) {
            runtimeSuspendedAfterPersistenceFailure = false
        }

        stored = consentStore.read()
        boundUserId = userId
        if (stored.requiresDisabledSessionCheckpointCleanup()) {
            if (!ownerChanged && !clearDisabledSessionCheckpointAndReconcile(reconcileAfter = false)) {
                return@binding false
            }
        }

        val refreshed = consentStore.read()
        val canRestore = pendingConsentMutation == null && !runtimeSuspendedAfterPersistenceFailure
        val restoredConsent = refreshed.consent.takeIf { refreshed.ownerUserId == userId && canRestore }
            ?: ObservabilityConsent()
        mutableConsent.value = restoredConsent
        runtime.setRestoredDiagnosticsSendPending(restoredConsent.diagnosticsAllowed)
        if (canRestore) mutablePrivacyOperationFailed.value = false
        reconcileRuntime()
        true
    }

    private fun attemptConsentUpdate(mutation: PendingConsentMutation.Update): Boolean {
        pendingConsentMutation = mutation
        requestedUserId = mutation.ownerUserId
        runtime.suspendCollection()
        val bindingSucceeded = if (boundUserId != mutation.ownerUserId) bindRequestedUser() else true
        if (!bindingSucceeded || boundUserId != mutation.ownerUserId) return false

        if (!consentStore.stageWrite(mutation.ownerUserId, mutation.consent)) {
            mutableConsent.value = ObservabilityConsent()
            runtimeSuspendedAfterPersistenceFailure = true
            mutablePrivacyOperationFailed.value = true
            reconcileRuntime()
            return false
        }
        mutableConsent.value = ObservabilityConsent()
        if (!clearStagedSessionCheckpoint()) return false

        pendingConsentMutation = null
        runtimeSuspendedAfterPersistenceFailure = false
        mutablePrivacyOperationFailed.value = false
        reconcileRuntime()
        return true
    }

    private fun attemptConsentRevocation(clearRequestedUser: Boolean): Boolean {
        pendingConsentMutation = PendingConsentMutation.Revoke
        if (clearRequestedUser) requestedUserId = null
        boundUserId = null
        runtime.suspendCollection()
        if (!consentStore.stageRevocation()) {
            runtimeSuspendedAfterPersistenceFailure = true
            mutableConsent.value = ObservabilityConsent()
            mutablePrivacyOperationFailed.value = true
            reconcileRuntime()
            return false
        }
        mutableConsent.value = ObservabilityConsent()
        if (!clearStagedSessionCheckpoint()) return false

        pendingConsentMutation = null
        runtimeSuspendedAfterPersistenceFailure = false
        mutablePrivacyOperationFailed.value = false
        if (requestedUserId == null) reconcileRuntime() else bindRequestedUser()
        return true
    }

    private val stageRevocationAndClearSession: () -> Boolean = {
        runtime.suspendCollection()
        if (!consentStore.stageRevocation()) {
            false
        } else {
            mutableConsent.value = ObservabilityConsent()
            clearStagedSessionCheckpoint()
        }
    }

    private fun clearDisabledSessionCheckpointAndReconcile(reconcileAfter: Boolean = true): Boolean {
        runtime.suspendCollection()
        if (!consentStore.ensureSessionCheckpointPurgePending()) {
            runtimeSuspendedAfterPersistenceFailure = true
            mutablePrivacyOperationFailed.value = true
            reconcileRuntime()
            return false
        }
        if (!clearStagedSessionCheckpoint()) return false
        runtimeSuspendedAfterPersistenceFailure = false
        if (pendingConsentMutation == null) mutablePrivacyOperationFailed.value = false
        if (reconcileAfter) reconcileRuntime()
        return true
    }

    private val clearStagedSessionCheckpoint: () -> Boolean = {
        if (!runtime.revokeObservedSession() || !consentStore.completeSessionCheckpointPurge()) {
            runtimeSuspendedAfterPersistenceFailure = true
            mutablePrivacyOperationFailed.value = true
            reconcileRuntime()
            false
        } else {
            true
        }
    }

    private fun activateStagedConsentIfReady(): Boolean {
        val stored = consentStore.read()
        if (!stored.hasStagedConsentActivation || stored.hasPendingMaintenance) return false
        if (!consentStore.activateStagedConsent()) {
            runtimeSuspendedAfterPersistenceFailure = true
            mutablePrivacyOperationFailed.value = true
            return false
        }
        runtimeSuspendedAfterPersistenceFailure = false
        mutablePrivacyOperationFailed.value = false
        val activated = consentStore.read()
        val canBind = activated.ownerUserId != null && activated.ownerUserId == requestedUserId
        boundUserId = activated.ownerUserId.takeIf { canBind }
        mutableConsent.value = activated.consent.takeIf { canBind } ?: ObservabilityConsent()
        runtime.setRestoredDiagnosticsSendPending(false)
        reconcileRuntime()
        return true
    }

    private val reconcileRuntime: () -> Unit = {
        val stored = consentStore.read()
        val canRestore =
            stored.ownerUserId == boundUserId &&
                boundUserId != null &&
                !stored.hasStagedConsentActivation &&
                !stored.hasPendingMaintenance &&
                pendingConsentMutation == null &&
                !runtimeSuspendedAfterPersistenceFailure
        val desiredConsent = stored.consent.takeIf { canRestore } ?: ObservabilityConsent()
        mutableConsent.value = desiredConsent
        runtime.reconcile(desiredConsent)
    }
}

private sealed interface PendingConsentMutation {
    data class Update(
        val ownerUserId: String,
        val consent: ObservabilityConsent,
    ) : PendingConsentMutation

    data object Revoke : PendingConsentMutation
}

internal class AndroidObservabilityStateLock {
    fun <T> hold(block: () -> T): T = synchronized(this, block)
}

internal class AndroidPerformanceController(
    private val backend: AndroidCollectionBackend,
    private val stateLock: AndroidObservabilityStateLock,
    private val isCollectionAllowed: () -> Boolean,
) {
    fun startTrace(name: PerformanceTraceName): PerformanceTrace = stateLock.hold {
        if (isCollectionAllowed()) backend.startTrace(name) else PerformanceTrace.None
    }

    fun recordMeasurement(measurement: PerformanceMeasurement) = stateLock.hold {
        if (isCollectionAllowed()) backend.recordPerformanceMeasurement(measurement)
    }
}

internal interface AndroidObservabilityBackend :
    AndroidCollectionBackend,
    AndroidPrivacyMaintenanceBackend,
    AndroidRemoteConfigurationBackend

internal interface AndroidCollectionBackend {
    val isConfigured: Boolean

    fun ensureConfigured(): Boolean

    fun applyConsent(consent: ObservabilityConsent)

    fun resetAnalyticsData()

    fun track(event: AnalyticsEvent)

    fun trackObservedSession(session: ObservedAppSession)

    fun recordDiagnostic(code: DiagnosticCode)

    fun startTrace(name: PerformanceTraceName): PerformanceTrace

    fun recordPerformanceMeasurement(measurement: PerformanceMeasurement)
}

internal interface AndroidPrivacyMaintenanceBackend {
    fun checkForUnsentReports(onResult: (DiagnosticsReportCheckResult) -> Unit)

    fun deleteUnsentReports()

    fun sendUnsentReports()

    fun deleteInstallation(onResult: (Boolean) -> Unit)
}

internal interface AndroidRemoteConfigurationBackend {
    fun fetchAndActivateRemoteConfiguration(onResult: (Boolean) -> Unit)

    fun startRemoteConfigurationUpdates(onResult: (Boolean) -> Unit)

    fun stopRemoteConfigurationUpdates()
}

internal interface ObservabilityConsentStore {
    fun read(): StoredObservabilityConsent

    fun stageWrite(ownerUserId: String, consent: ObservabilityConsent): Boolean

    fun stageRevocation(): Boolean

    fun ensureSessionCheckpointPurgePending(): Boolean

    fun completeSessionCheckpointPurge(): Boolean

    fun activateStagedConsent(): Boolean

    fun clearAnalyticsPurgePending(): Boolean

    fun completeDiagnosticsReportPurge(expectedRequestId: String): InstallationDeletionCompletion

    fun completeInstallationDeletion(expectedRequestId: String): InstallationDeletionCompletion
}

private fun StoredObservabilityConsent.hasStagedActivationForAnotherUser(userId: String): Boolean =
    hasStagedConsentActivation && stagedOwnerUserId != userId

private fun StoredObservabilityConsent.requiresDisabledSessionCheckpointCleanup(): Boolean =
    sessionCheckpointPurgePending ||
        hasStagedConsentActivation ||
        ownerUserId == null ||
        !consent.allowsObservedSessionMeasurement

internal data class StoredObservabilityConsent(
    val ownerUserId: String?,
    val consent: ObservabilityConsent,
    val analyticsPurgePending: Boolean = false,
    val diagnosticsReportPurgeRequestId: String? = null,
    val installationDeletionRequestId: String? = null,
    val persistedOwnerUserId: String? = ownerUserId,
    val persistedConsent: ObservabilityConsent = consent,
    val sessionCheckpointPurgePending: Boolean = false,
    val hasStagedConsentActivation: Boolean = false,
    val stagedOwnerUserId: String? = null,
    val stagedConsent: ObservabilityConsent? = null,
) {
    val hasPendingMaintenance: Boolean
        get() =
            sessionCheckpointPurgePending || hasPendingBackendMaintenance

    val hasPendingBackendMaintenance: Boolean
        get() =
            analyticsPurgePending ||
                diagnosticsReportPurgeRequestId != null ||
                installationDeletionRequestId != null
}

internal enum class InstallationDeletionCompletion {
    Completed,
    Superseded,
    Failure,
}

internal sealed interface DiagnosticsReportCheckResult {
    data class Success(val hasUnsentReports: Boolean) : DiagnosticsReportCheckResult

    data object Failure : DiagnosticsReportCheckResult
}

private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

fun interface PerformanceTrace {
    fun stop()

    companion object {
        val None = PerformanceTrace {}
    }
}
