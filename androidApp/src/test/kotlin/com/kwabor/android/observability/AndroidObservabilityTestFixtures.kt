package com.kwabor.android.observability

import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.ConsentedAppSessionTracker
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.ObservedAppSession
import com.kwabor.shared.domain.observability.ObservedAppSessionCheckpointRead
import com.kwabor.shared.domain.observability.ObservedAppSessionStore
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeMark
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeRead
import com.kwabor.shared.domain.observability.ObservedAppSessionTimeSource
import com.kwabor.shared.domain.observability.PerformanceMeasurement
import com.kwabor.shared.domain.observability.PerformanceTraceName

internal class TestConsentStore(
    ownerUserId: String? = null,
    consent: ObservabilityConsent = ObservabilityConsent(),
    analyticsPurgePending: Boolean = false,
    diagnosticsReportPurgeRequestId: String? = null,
    installationDeletionRequestId: String? = null,
    private val behavior: TestConsentStoreBehavior = TestConsentStoreBehavior(),
) : ObservabilityConsentStore {
    private var storedConsent = StoredObservabilityConsent(
        ownerUserId = ownerUserId,
        consent = consent,
        analyticsPurgePending = analyticsPurgePending,
        diagnosticsReportPurgeRequestId = diagnosticsReportPurgeRequestId,
        installationDeletionRequestId = installationDeletionRequestId,
    )
    var writeCount = 0
        private set
    var revocationCount = 0
        private set
    var successfulRevocationCount = 0
        private set
    val writeAttempts = mutableListOf<Pair<String, ObservabilityConsent>>()
    var writesSucceed: Boolean
        get() = behavior.writesSucceed
        set(value) {
            behavior.writesSucceed = value
        }
    var revocationsSucceed: Boolean
        get() = behavior.revocationsSucceed
        set(value) {
            behavior.revocationsSucceed = value
        }

    override fun read(): StoredObservabilityConsent = storedConsent

    fun write(ownerUserId: String, consent: ObservabilityConsent): Boolean {
        writeCount += 1
        writeAttempts += ownerUserId to consent
        if (!behavior.writesSucceed) return false
        val persistedOwnerUserId = ownerUserId.takeIf { consent != ObservabilityConsent() }
        storedConsent = storedConsent.copy(
            ownerUserId = persistedOwnerUserId,
            consent = consent,
            persistedOwnerUserId = persistedOwnerUserId,
            persistedConsent = consent,
        )
        return true
    }

    override fun stageWrite(ownerUserId: String, consent: ObservabilityConsent): Boolean {
        behavior.operationLog?.add("durable_stage_write")
        writeCount += 1
        writeAttempts += ownerUserId to consent
        if (!behavior.writesSucceed) return false
        val previous = storedConsent.persistedConsent
        storedConsent = storedConsent.copy(
            ownerUserId = null,
            consent = ObservabilityConsent(),
            analyticsPurgePending = storedConsent.analyticsPurgePending ||
                behavior.requiresAnalyticsPurge(previous, consent),
            diagnosticsReportPurgeRequestId = storedConsent.diagnosticsReportPurgeRequestId
                ?: behavior.diagnosticsPurgeRequest(previous, consent),
            installationDeletionRequestId = storedConsent.installationDeletionRequestId
                ?: behavior.installationDeletionRequest(previous, consent),
            sessionCheckpointPurgePending = true,
            hasStagedConsentActivation = true,
            stagedOwnerUserId = ownerUserId,
            stagedConsent = consent,
        )
        return true
    }

    fun revoke(): Boolean {
        revocationCount += 1
        if (!behavior.revocationsSucceed) return false
        successfulRevocationCount += 1
        storedConsent = storedConsent.copy(
            ownerUserId = null,
            consent = ObservabilityConsent(),
            persistedOwnerUserId = null,
            persistedConsent = ObservabilityConsent(),
        )
        return true
    }

    override fun stageRevocation(): Boolean {
        behavior.operationLog?.add("durable_stage_revoke")
        revocationCount += 1
        if (!behavior.revocationsSucceed) return false
        successfulRevocationCount += 1
        val hadStoredState = storedConsent.hasStoredAccountState
        storedConsent = storedConsent.copy(
            ownerUserId = null,
            consent = ObservabilityConsent(),
            analyticsPurgePending = storedConsent.analyticsPurgePending ||
                behavior.modelsTransitionMaintenance && hadStoredState,
            diagnosticsReportPurgeRequestId = storedConsent.diagnosticsReportPurgeRequestId ?: if (
                behavior.modelsTransitionMaintenance && hadStoredState
            ) {
                NEW_DIAGNOSTICS_REQUEST_ID
            } else {
                null
            },
            installationDeletionRequestId = storedConsent.installationDeletionRequestId ?: if (
                behavior.modelsTransitionMaintenance && hadStoredState
            ) {
                NEW_INSTALLATION_REQUEST_ID
            } else {
                null
            },
            persistedOwnerUserId = null,
            persistedConsent = ObservabilityConsent(),
            sessionCheckpointPurgePending = true,
            hasStagedConsentActivation = false,
            stagedOwnerUserId = null,
            stagedConsent = null,
        )
        return true
    }

    override fun ensureSessionCheckpointPurgePending(): Boolean {
        if (!behavior.sessionMarkerWritesSucceed) return false
        storedConsent = storedConsent.copy(sessionCheckpointPurgePending = true)
        return true
    }

    override fun completeSessionCheckpointPurge(): Boolean {
        if (!behavior.sessionCompletionSucceeds) return false
        storedConsent = storedConsent.copy(sessionCheckpointPurgePending = false)
        return true
    }

    override fun activateStagedConsent(): Boolean {
        val ownerUserId = storedConsent.stagedOwnerUserId
        val consent = storedConsent.stagedConsent
        return when {
            !behavior.activationsSucceed || storedConsent.hasPendingMaintenance -> false
            !storedConsent.hasStagedConsentActivation -> true
            ownerUserId == null || consent == null -> false
            else -> {
                val persistedOwnerUserId = ownerUserId.takeIf { consent != ObservabilityConsent() }
                storedConsent = storedConsent.copy(
                    ownerUserId = persistedOwnerUserId,
                    consent = consent,
                    persistedOwnerUserId = persistedOwnerUserId,
                    persistedConsent = consent,
                    hasStagedConsentActivation = false,
                    stagedOwnerUserId = null,
                    stagedConsent = null,
                )
                true
            }
        }
    }

    override fun clearAnalyticsPurgePending(): Boolean {
        if (!behavior.analyticsClearSucceeds) return false
        storedConsent = storedConsent.copy(analyticsPurgePending = false)
        return true
    }

    override fun completeDiagnosticsReportPurge(expectedRequestId: String): InstallationDeletionCompletion =
        completeRequest(
            expectedRequestId = expectedRequestId,
            currentRequestId = storedConsent.diagnosticsReportPurgeRequestId,
            succeeds = behavior.diagnosticsCompletionSucceeds,
            onCompleted = { storedConsent = storedConsent.copy(diagnosticsReportPurgeRequestId = null) },
        )

    override fun completeInstallationDeletion(expectedRequestId: String): InstallationDeletionCompletion =
        completeRequest(
            expectedRequestId = expectedRequestId,
            currentRequestId = storedConsent.installationDeletionRequestId,
            succeeds = behavior.installationCompletionSucceeds,
            onCompleted = { storedConsent = storedConsent.copy(installationDeletionRequestId = null) },
        )

    fun replaceDiagnosticsPurgeRequest(requestId: String) {
        storedConsent = storedConsent.copy(diagnosticsReportPurgeRequestId = requestId)
    }

    fun replaceInstallationDeletionRequest(requestId: String) {
        storedConsent = storedConsent.copy(installationDeletionRequestId = requestId)
    }

    private fun completeRequest(
        expectedRequestId: String,
        currentRequestId: String?,
        succeeds: Boolean,
        onCompleted: () -> Unit,
    ): InstallationDeletionCompletion = when {
        currentRequestId == null -> InstallationDeletionCompletion.Completed
        currentRequestId != expectedRequestId -> InstallationDeletionCompletion.Superseded
        !succeeds -> InstallationDeletionCompletion.Failure
        else -> {
            onCompleted()
            InstallationDeletionCompletion.Completed
        }
    }
}

internal data class TestConsentStoreBehavior(
    var writesSucceed: Boolean = true,
    var revocationsSucceed: Boolean = true,
    var analyticsClearSucceeds: Boolean = true,
    var diagnosticsCompletionSucceeds: Boolean = true,
    var installationCompletionSucceeds: Boolean = true,
    var sessionMarkerWritesSucceed: Boolean = true,
    var sessionCompletionSucceeds: Boolean = true,
    var activationsSucceed: Boolean = true,
    var modelsTransitionMaintenance: Boolean = false,
    val operationLog: MutableList<String>? = null,
)

private fun TestConsentStoreBehavior.requiresAnalyticsPurge(
    previous: ObservabilityConsent,
    target: ObservabilityConsent,
): Boolean = modelsTransitionMaintenance && previous.analyticsAllowed && !target.analyticsAllowed

private fun TestConsentStoreBehavior.diagnosticsPurgeRequest(
    previous: ObservabilityConsent,
    target: ObservabilityConsent,
): String? = NEW_DIAGNOSTICS_REQUEST_ID.takeIf {
    modelsTransitionMaintenance && previous.diagnosticsAllowed && !target.diagnosticsAllowed
}

private fun TestConsentStoreBehavior.installationDeletionRequest(
    previous: ObservabilityConsent,
    target: ObservabilityConsent,
): String? = NEW_INSTALLATION_REQUEST_ID.takeIf {
    modelsTransitionMaintenance &&
        previous.remoteConfigurationAllowed &&
        !target.remoteConfigurationAllowed
}

private val StoredObservabilityConsent.hasStoredAccountState: Boolean
    get() =
        persistedOwnerUserId != null ||
            persistedConsent != ObservabilityConsent() ||
            hasStagedConsentActivation

internal class TestObservabilityBackend(
    initiallyConfigured: Boolean = false,
    var ensureConfiguredSucceeds: Boolean = true,
    private val fetchSucceeds: Boolean = true,
    private val operationLog: MutableList<String>? = null,
) : AndroidObservabilityBackend {
    private var configured = initiallyConfigured
    private val diagnosticsCheckCallbacks = ArrayDeque<(DiagnosticsReportCheckResult) -> Unit>()
    private val installationDeletionCallbacks = ArrayDeque<(Boolean) -> Unit>()

    override val isConfigured: Boolean get() = configured
    var ensureConfiguredCount = 0
        private set
    var appliedConsent = ObservabilityConsent()
        private set
    val appliedConsents = mutableListOf<ObservabilityConsent>()
    val events = mutableListOf<AnalyticsEvent>()
    val observedSessions = mutableListOf<ObservedAppSession>()
    val diagnostics = mutableListOf<DiagnosticCode>()
    val traces = mutableListOf<PerformanceTraceName>()
    val performanceMeasurements = mutableListOf<PerformanceMeasurement>()
    var remoteConfigurationFetched = false
        private set
    var remoteUpdateStartCount = 0
        private set
    var remoteUpdateStopCount = 0
        private set
    var resetAnalyticsDataCount = 0
        private set
    var diagnosticsCheckCount = 0
        private set
    var deleteUnsentReportsCount = 0
        private set
    var sendUnsentReportsCount = 0
        private set
    var installationDeletionCount = 0
        private set
    private var remoteUpdateCallback: ((Boolean) -> Unit)? = null
    private var staleRemoteUpdateCallback: ((Boolean) -> Unit)? = null
    val remoteUpdatesStarted: Boolean get() = remoteUpdateCallback != null

    override fun ensureConfigured(): Boolean {
        ensureConfiguredCount += 1
        if (ensureConfiguredSucceeds) configured = true
        return configured
    }

    override fun applyConsent(consent: ObservabilityConsent) {
        appliedConsent = consent
        appliedConsents += consent
    }

    override fun resetAnalyticsData() {
        resetAnalyticsDataCount += 1
        events.clear()
    }

    override fun checkForUnsentReports(onResult: (DiagnosticsReportCheckResult) -> Unit) {
        diagnosticsCheckCount += 1
        diagnosticsCheckCallbacks.addLast(onResult)
    }

    override fun deleteUnsentReports() {
        deleteUnsentReportsCount += 1
        diagnostics.clear()
        traces.clear()
    }

    override fun sendUnsentReports() {
        sendUnsentReportsCount += 1
    }

    override fun deleteInstallation(onResult: (Boolean) -> Unit) {
        operationLog?.add("fid_delete")
        installationDeletionCount += 1
        installationDeletionCallbacks.addLast(onResult)
    }

    override fun track(event: AnalyticsEvent) {
        events += event
    }

    override fun trackObservedSession(session: ObservedAppSession) {
        observedSessions += session
    }

    override fun recordDiagnostic(code: DiagnosticCode) {
        diagnostics += code
    }

    override fun startTrace(name: PerformanceTraceName): PerformanceTrace {
        traces += name
        return PerformanceTrace.None
    }

    override fun recordPerformanceMeasurement(measurement: PerformanceMeasurement) {
        performanceMeasurements += measurement
    }

    override fun fetchAndActivateRemoteConfiguration(onResult: (Boolean) -> Unit) {
        remoteConfigurationFetched = true
        onResult(fetchSucceeds)
    }

    override fun startRemoteConfigurationUpdates(onResult: (Boolean) -> Unit) {
        remoteUpdateStartCount += 1
        remoteUpdateCallback = onResult
        staleRemoteUpdateCallback = onResult
    }

    override fun stopRemoteConfigurationUpdates() {
        remoteUpdateStopCount += 1
        remoteUpdateCallback = null
    }

    fun completeNextDiagnosticsCheck(result: DiagnosticsReportCheckResult) {
        val callback = diagnosticsCheckCallbacks.removeFirstOrNull()
            ?: error("No diagnostics report check is pending.")
        callback(result)
    }

    fun completeNextInstallationDeletion(succeeded: Boolean) {
        val callback = installationDeletionCallbacks.removeFirstOrNull()
            ?: error("No Firebase installation deletion is pending.")
        callback(succeeded)
    }

    fun emitRemoteUpdate(succeeded: Boolean) {
        remoteUpdateCallback?.invoke(succeeded)
    }

    fun emitStaleRemoteUpdate(succeeded: Boolean) {
        staleRemoteUpdateCallback?.invoke(succeeded)
    }
}

internal class MutableObservabilityClock(
    var nowEpochMilliseconds: Long = 0L,
) : ClockProvider, ObservedAppSessionTimeSource {
    override fun nowEpochMilliseconds(): Long = nowEpochMilliseconds

    override fun read(): ObservedAppSessionTimeRead = ObservedAppSessionTimeRead.Available(
        ObservedAppSessionTimeMark(
            wallEpochMilliseconds = nowEpochMilliseconds,
            monotonicMilliseconds = nowEpochMilliseconds,
            bootIdentifier = TEST_BOOT_IDENTIFIER,
            bootAnchorEpochMilliseconds = 0L,
        ),
    )
}

internal class TestObservedAppSessionStore(
    initialCheckpoint: ObservedAppSessionCheckpointRead? = null,
    var clearsSucceed: Boolean = true,
    private val operationLog: MutableList<String>? = null,
) : ObservedAppSessionStore {
    var checkpoint = initialCheckpoint
        private set
    var clearCount = 0
        private set

    override fun read(): ObservedAppSessionCheckpointRead = checkpoint ?: ObservedAppSessionCheckpointRead.Missing

    override fun writeForeground(): Boolean {
        checkpoint = ObservedAppSessionCheckpointRead.Foreground
        return true
    }

    override fun writeBackgroundedAt(timeMark: ObservedAppSessionTimeMark): Boolean {
        checkpoint = ObservedAppSessionCheckpointRead.BackgroundedAt(timeMark)
        return true
    }

    override fun clear(): Boolean {
        operationLog?.add("session_clear")
        clearCount += 1
        if (!clearsSucceed) return false
        checkpoint = null
        return true
    }
}

internal fun testObservedAppSessionTracker(
    clock: MutableObservabilityClock,
    store: TestObservedAppSessionStore,
): ConsentedAppSessionTracker = ConsentedAppSessionTracker(timeSource = clock, store = store)

private const val TEST_BOOT_IDENTIFIER = 1L
internal const val TEST_USER_ID = "user-one"
internal const val TEST_OTHER_USER_ID = "user-two"
internal const val OLD_DIAGNOSTICS_REQUEST_ID = "diagnostics-old"
internal const val NEW_DIAGNOSTICS_REQUEST_ID = "diagnostics-new"
internal const val OLD_INSTALLATION_REQUEST_ID = "installation-old"
internal const val NEW_INSTALLATION_REQUEST_ID = "installation-new"
internal val ALL_OBSERVABILITY_GRANTED = ObservabilityConsent(
    analyticsAllowed = true,
    diagnosticsAllowed = true,
    remoteConfigurationAllowed = true,
)
