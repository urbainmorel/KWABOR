package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceMeasurement
import com.kwabor.shared.domain.observability.PerformanceTraceName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidObservabilityController internal constructor(
    backend: AndroidObservabilityBackend,
    private val consentStore: ObservabilityConsentStore,
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
        stateLock = stateLock,
        onPrivacyOperationFailed = { failed ->
            mutablePrivacyOperationFailed.value =
                failed || runtimeSuspendedAfterPersistenceFailure || pendingConsentMutation != null
        },
        onPerformanceCollectionAllowedChanged = { allowed ->
            mutablePerformanceCollectionAllowed.value = allowed
        },
    )

    val consent: StateFlow<ObservabilityConsent> = mutableConsent.asStateFlow()
    val privacyOperationFailed: StateFlow<Boolean> = mutablePrivacyOperationFailed.asStateFlow()
    val performanceCollectionAllowed: StateFlow<Boolean> = mutablePerformanceCollectionAllowed.asStateFlow()
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
                if (requestedUserId != null && boundUserId == null) {
                    bindRequestedUser()
                } else {
                    reconcileRuntime()
                }
            }
        }
        !mutablePrivacyOperationFailed.value
    }

    fun track(event: AnalyticsEvent) = stateLock.hold {
        runtime.track(event)
    }

    fun recordDiagnostic(code: DiagnosticCode) = stateLock.hold {
        runtime.recordDiagnostic(code)
    }

    fun startTrace(name: PerformanceTraceName): PerformanceTrace = stateLock.hold {
        runtime.startTrace(name)
    }

    fun recordPerformanceMeasurement(measurement: PerformanceMeasurement) = stateLock.hold {
        runtime.recordPerformanceMeasurement(measurement)
    }

    fun close() = stateLock.hold {
        runtime.close()
    }

    private fun bindRequestedUser(): Boolean {
        runtime.suspendCollection()
        val userId = requestedUserId
        if (userId == null) {
            boundUserId = null
            mutableConsent.value = ObservabilityConsent()
            reconcileRuntime()
            return true
        }

        val storedOwner = consentStore.read().persistedOwnerUserId
        if (storedOwner != null && storedOwner != userId && !consentStore.revoke()) {
            boundUserId = null
            mutableConsent.value = ObservabilityConsent()
            runtimeSuspendedAfterPersistenceFailure = true
            mutablePrivacyOperationFailed.value = true
            reconcileRuntime()
            return false
        }
        if (storedOwner != null && storedOwner != userId) {
            runtimeSuspendedAfterPersistenceFailure = false
        }

        val refreshed = consentStore.read()
        boundUserId = userId
        val canRestore = pendingConsentMutation == null && !runtimeSuspendedAfterPersistenceFailure
        val restoredConsent = refreshed.consent.takeIf { refreshed.ownerUserId == userId && canRestore }
            ?: ObservabilityConsent()
        mutableConsent.value = restoredConsent
        runtime.setRestoredDiagnosticsSendPending(restoredConsent.diagnosticsAllowed)
        if (canRestore) mutablePrivacyOperationFailed.value = false
        reconcileRuntime()
        return true
    }

    private fun attemptConsentUpdate(mutation: PendingConsentMutation.Update): Boolean {
        pendingConsentMutation = mutation
        requestedUserId = mutation.ownerUserId
        runtime.suspendCollection()
        if (boundUserId != mutation.ownerUserId && !bindRequestedUser()) return false
        if (boundUserId != mutation.ownerUserId) return false

        if (!consentStore.write(mutation.ownerUserId, mutation.consent)) {
            mutableConsent.value = ObservabilityConsent()
            runtimeSuspendedAfterPersistenceFailure = true
            mutablePrivacyOperationFailed.value = true
            reconcileRuntime()
            return false
        }
        pendingConsentMutation = null
        runtimeSuspendedAfterPersistenceFailure = false
        mutableConsent.value = mutation.consent
        mutablePrivacyOperationFailed.value = false
        reconcileRuntime()
        return true
    }

    private fun attemptConsentRevocation(clearRequestedUser: Boolean): Boolean {
        pendingConsentMutation = PendingConsentMutation.Revoke
        if (clearRequestedUser) requestedUserId = null
        boundUserId = null
        runtime.suspendCollection()
        val persisted = consentStore.revoke()
        runtimeSuspendedAfterPersistenceFailure = !persisted
        mutableConsent.value = ObservabilityConsent()
        mutablePrivacyOperationFailed.value = !persisted
        if (!persisted) {
            reconcileRuntime()
            return false
        }

        pendingConsentMutation = null
        if (requestedUserId == null) reconcileRuntime() else bindRequestedUser()
        return true
    }

    private fun reconcileRuntime() {
        val stored = consentStore.read()
        val canRestore =
            stored.ownerUserId == boundUserId &&
                boundUserId != null &&
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

    fun write(ownerUserId: String, consent: ObservabilityConsent): Boolean

    fun revoke(): Boolean

    fun clearAnalyticsPurgePending(): Boolean

    fun completeDiagnosticsReportPurge(expectedRequestId: String): InstallationDeletionCompletion

    fun completeInstallationDeletion(expectedRequestId: String): InstallationDeletionCompletion
}

internal data class StoredObservabilityConsent(
    val ownerUserId: String?,
    val consent: ObservabilityConsent,
    val analyticsPurgePending: Boolean = false,
    val diagnosticsReportPurgeRequestId: String? = null,
    val installationDeletionRequestId: String? = null,
    val persistedOwnerUserId: String? = ownerUserId,
    val persistedConsent: ObservabilityConsent = consent,
) {
    val hasPendingMaintenance: Boolean
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
