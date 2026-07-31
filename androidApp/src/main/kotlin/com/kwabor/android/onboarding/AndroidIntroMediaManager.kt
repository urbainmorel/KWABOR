package com.kwabor.android.onboarding

import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.android.observability.AndroidRemoteMediaEvent
import com.kwabor.android.observability.AndroidRemoteMediaSubscription
import com.kwabor.android.observability.RemoteMediaPurgeEpoch
import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.RemoteIntroVideo
import com.kwabor.shared.domain.observability.RemoteIntroVideoStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

internal sealed interface IntroMediaSource {
    data object Bundled : IntroMediaSource

    data class Remote(
        val file: File,
        val revision: Long,
    ) : IntroMediaSource
}

internal data class IntroLaunchRequest(
    val isRequired: Boolean,
    val mediaSource: IntroMediaSource,
)

internal data class IntroLaunchDecision(
    val isComplete: Boolean,
    val request: IntroLaunchRequest?,
) {
    companion object {
        val Pending = IntroLaunchDecision(isComplete = false, request = null)

        fun complete(request: IntroLaunchRequest): IntroLaunchDecision =
            IntroLaunchDecision(isComplete = true, request = request)
    }
}

private sealed interface IntroMediaManagerEvent {
    data class Control(val event: AndroidRemoteMediaEvent) : IntroMediaManagerEvent

    data class ResolutionCompleted(
        val attemptId: Long,
        val source: RemoteIntroVideo,
        val purgeEpochAtStart: RemoteMediaPurgeEpoch,
        val resolvedFile: File?,
        val failureAlreadyReported: Boolean,
    ) : IntroMediaManagerEvent

    data class IntroConsumed(val source: IntroMediaSource) : IntroMediaManagerEvent
}

private data class QueuedRemoteSource(
    val source: RemoteIntroVideo,
    val purgeEpoch: RemoteMediaPurgeEpoch,
)

private enum class SnapshotPurgeMode {
    None,
    PreserveLaunchFile,
    RemoveAll,
}

private class ResolvedIntroPublisher(
    private val observability: AndroidObservabilityController,
    private val cache: IntroVideoCache,
    private val firstLaunchStore: FirstLaunchStore,
) {
    suspend fun publish(
        source: RemoteIntroVideo,
        resolvedFile: File?,
        protectedLaunchFile: File?,
        failureAlreadyReported: Boolean,
    ) {
        if (resolvedFile == null) {
            if (!failureAlreadyReported) {
                observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
            }
            return
        }
        val latestKnownRevision = maxOf(
            firstLaunchStore.lastPresentedRemoteRevision(),
            firstLaunchStore.pendingRemoteIntro()?.revision ?: NO_REMOTE_REVISION,
        )
        if (source.revision <= latestKnownRevision) return
        val wasPersisted = firstLaunchStore.markRemoteIntroPending(
            PendingRemoteIntro(
                revision = source.revision,
                sha256 = source.sha256,
                fileName = resolvedFile.name,
            ),
        )
        if (!wasPersisted) {
            observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
            return
        }
        if (!cache.clear(protectedFiles = setOfNotNull(resolvedFile, protectedLaunchFile))) {
            observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
        }
    }
}

private data class PendingRemoteMediaPurge(
    val epoch: RemoteMediaPurgeEpoch,
    val removeLaunchFile: Boolean,
)

private sealed interface IntroMediaOperationResult<out T> {
    data class Success<T>(val value: T) : IntroMediaOperationResult<T>

    data class Failure(val diagnosticCode: DiagnosticCode) : IntroMediaOperationResult<Nothing>
}

private class RemoteMediaPurger(
    private val observability: AndroidObservabilityController,
    private val cache: IntroVideoCache,
    private val firstLaunchStore: FirstLaunchStore,
) {
    private var pendingPurge: PendingRemoteMediaPurge? = null

    val isPending: Boolean get() = pendingPurge != null

    suspend fun requirePurge(
        epoch: RemoteMediaPurgeEpoch,
        removeLaunchFile: Boolean,
        protectedLaunchFile: File?,
    ): Boolean {
        val previous = pendingPurge
        pendingPurge = PendingRemoteMediaPurge(
            epoch = previous?.epoch?.merge(epoch) ?: epoch,
            removeLaunchFile = previous?.removeLaunchFile == true || removeLaunchFile,
        )
        return retry(protectedLaunchFile)
    }

    suspend fun retry(protectedLaunchFile: File?): Boolean {
        val purge = pendingPurge ?: return true
        return withContext(NonCancellable) {
            when (
                val result = captureIntroMediaOperation {
                    val pendingWasCleared = firstLaunchStore.clearPendingRemoteIntro()
                    val protectedFiles = if (purge.removeLaunchFile) emptySet() else setOfNotNull(protectedLaunchFile)
                    val cacheWasCleared = cache.clear(protectedFiles = protectedFiles)
                    pendingWasCleared &&
                        cacheWasCleared &&
                        observability.acknowledgeRemoteMediaPurge(purge.epoch)
                }
            ) {
                is IntroMediaOperationResult.Success -> if (result.value) {
                    pendingPurge = null
                    true
                } else {
                    observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
                    false
                }
                is IntroMediaOperationResult.Failure -> {
                    observability.recordDiagnostic(result.diagnosticCode)
                    false
                }
            }
        }
    }
}

private class IntroLaunchDecisionResolver(
    private val observability: AndroidObservabilityController,
    private val cache: IntroVideoCache,
    private val firstLaunchStore: FirstLaunchStore,
    private val purgeEpochAtCreation: RemoteMediaPurgeEpoch,
) {
    fun initialDecision(): IntroLaunchDecision = if (firstLaunchStore.isBundledIntroRequired()) {
        IntroLaunchDecision.complete(
            IntroLaunchRequest(isRequired = true, mediaSource = IntroMediaSource.Bundled),
        )
    } else {
        IntroLaunchDecision.Pending
    }

    suspend fun completeIfNeeded(current: IntroLaunchDecision): IntroLaunchDecision =
        if (current.isComplete) current else IntroLaunchDecision.complete(createReturningLaunchRequest())

    private suspend fun createReturningLaunchRequest(): IntroLaunchRequest {
        if (pendingRemoteIntroMustBeSuppressed()) {
            return clearPendingAndUseBundled()
        }
        val pending = firstLaunchStore.pendingRemoteIntro() ?: return clearCacheAndUseBundled()
        return createRequestForPendingIntro(pending)
    }

    private suspend fun createRequestForPendingIntro(pending: PendingRemoteIntro): IntroLaunchRequest {
        val file = when (val result = captureIntroMediaOperation { cache.findCached(pending) }) {
            is IntroMediaOperationResult.Success -> result.value
            is IntroMediaOperationResult.Failure -> {
                observability.recordDiagnostic(result.diagnosticCode)
                return bundledNotRequired()
            }
        }
        if (file == null) {
            return clearPendingCacheAndUseBundled()
        }
        if (pendingRemoteIntroMustBeSuppressed()) {
            return clearPendingAndUseBundled()
        }
        return IntroLaunchRequest(
            isRequired = true,
            mediaSource = IntroMediaSource.Remote(file = file, revision = pending.revision),
        )
    }

    private fun clearPendingAndUseBundled(): IntroLaunchRequest {
        firstLaunchStore.clearPendingRemoteIntro()
        return bundledNotRequired()
    }

    private suspend fun clearCacheAndUseBundled(): IntroLaunchRequest {
        if (!cache.clear()) {
            observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
        }
        return bundledNotRequired()
    }

    private suspend fun clearPendingCacheAndUseBundled(): IntroLaunchRequest {
        firstLaunchStore.clearPendingRemoteIntro()
        return clearCacheAndUseBundled()
    }

    private fun pendingRemoteIntroMustBeSuppressed(): Boolean =
        !observability.consent.value.remoteConfigurationAllowed ||
            observability.remoteConfiguration.value.introVideoStatus == RemoteIntroVideoStatus.Disabled ||
            observability.remoteMediaPurgeEpoch.value.isNewerThan(purgeEpochAtCreation)
}

private fun bundledNotRequired(): IntroLaunchRequest =
    IntroLaunchRequest(isRequired = false, mediaSource = IntroMediaSource.Bundled)

class AndroidIntroMediaManager internal constructor(
    private val observability: AndroidObservabilityController,
    private val cache: IntroVideoCache,
    private val firstLaunchStore: FirstLaunchStore,
    dispatcherProvider: DispatcherProvider,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    private val purgeEpochAtCreation = observability.remoteMediaPurgeEpoch.value
    private val launchDecisionResolver = IntroLaunchDecisionResolver(
        observability = observability,
        cache = cache,
        firstLaunchStore = firstLaunchStore,
        purgeEpochAtCreation = purgeEpochAtCreation,
    )
    private val resolvedIntroPublisher = ResolvedIntroPublisher(
        observability = observability,
        cache = cache,
        firstLaunchStore = firstLaunchStore,
    )
    private val remoteMediaPurger = RemoteMediaPurger(
        observability = observability,
        cache = cache,
        firstLaunchStore = firstLaunchStore,
    )
    private val managerEventChannel = Channel<IntroMediaManagerEvent>(capacity = Channel.UNLIMITED)
    private val queuedRemoteSources = ArrayDeque<QueuedRemoteSource>()
    private val mutableLaunchDecision = MutableStateFlow(launchDecisionResolver.initialDecision())
    private var remoteConfigurationAllowed: Boolean? = null
    private var remoteVideoStatus = RemoteIntroVideoStatus.Unavailable
    private var lastConsentSequence = -1L
    private var lastConfigurationSequence = -1L
    private var nextResolutionAttemptId = 0L
    private var inFlightAttemptId: Long? = null
    private var resolutionJob: Job? = null
    private var inFlightSource: RemoteIntroVideo? = null
    private var protectedLaunchFile: File? = null
    private var launchRemoteConsumed = false
    private var launchDecisionInitialized = false
    private var remoteMediaSubscription: AndroidRemoteMediaSubscription? = null
    private var hasStarted = false
    internal val launchDecision: StateFlow<IntroLaunchDecision> = mutableLaunchDecision.asStateFlow()

    fun start() {
        check(!hasStarted) { "The intro media manager can only be started once." }
        hasStarted = true
        val subscription = observability.openRemoteMediaSubscription()
        remoteMediaSubscription = subscription
        coroutineScope.launch {
            for (event in subscription.events) {
                managerEventChannel.send(IntroMediaManagerEvent.Control(event))
            }
        }
        coroutineScope.launch {
            for (event in managerEventChannel) {
                when (val result = captureIntroMediaOperation { processManagerEvent(event) }) {
                    is IntroMediaOperationResult.Success -> Unit
                    is IntroMediaOperationResult.Failure -> {
                        if ((event as? IntroMediaManagerEvent.Control)?.event is AndroidRemoteMediaEvent.Snapshot) {
                            captureIntroMediaOperation { initializeLaunchDecisionIfNeeded() }
                        }
                        captureIntroMediaOperation {
                            observability.recordDiagnostic(result.diagnosticCode)
                        }
                    }
                }
            }
        }
    }

    internal fun onIntroConsumed(source: IntroMediaSource) {
        check(managerEventChannel.trySend(IntroMediaManagerEvent.IntroConsumed(source)).isSuccess) {
            "The intro media manager event channel must remain available."
        }
    }

    private suspend fun processManagerEvent(event: IntroMediaManagerEvent) {
        when (event) {
            is IntroMediaManagerEvent.Control -> processControlEvent(event.event)
            is IntroMediaManagerEvent.ResolutionCompleted -> processResolutionCompleted(event)
            is IntroMediaManagerEvent.IntroConsumed -> {
                val launchSource = mutableLaunchDecision.value.request?.mediaSource as? IntroMediaSource.Remote
                mutableLaunchDecision.value = IntroLaunchDecision.complete(
                    IntroLaunchRequest(isRequired = false, mediaSource = IntroMediaSource.Bundled),
                )
                val consumedRemote = event.source as? IntroMediaSource.Remote
                if (launchSource?.revision == consumedRemote?.revision) {
                    protectedLaunchFile = null
                    launchRemoteConsumed = true
                    cleanupReleasedLaunchFileIfIdle()
                }
            }
        }
    }

    private suspend fun processControlEvent(event: AndroidRemoteMediaEvent) {
        when (event) {
            is AndroidRemoteMediaEvent.ConsentChanged -> processConsentChanged(event)
            is AndroidRemoteMediaEvent.ConfigurationChanged -> processConfigurationChanged(event)
            is AndroidRemoteMediaEvent.Snapshot -> processSnapshot(event)
        }
    }

    private suspend fun processSnapshot(event: AndroidRemoteMediaEvent.Snapshot) {
        if (event.consentSequence > lastConsentSequence) {
            lastConsentSequence = event.consentSequence
            remoteConfigurationAllowed = event.remoteConfigurationAllowed
        }
        if (event.configurationSequence > lastConfigurationSequence) {
            lastConfigurationSequence = event.configurationSequence
            remoteVideoStatus = event.configuration.introVideoStatus
        }
        event.requiredPurge()?.let { purge ->
            cancelResolution()
            queuedRemoteSources.clear()
            remoteMediaPurger.requirePurge(
                epoch = purge.epoch,
                removeLaunchFile = purge.removeLaunchFile,
                protectedLaunchFile = protectedLaunchFile,
            )
        }
        when (event.configuration.introVideoStatus) {
            RemoteIntroVideoStatus.Unavailable,
            RemoteIntroVideoStatus.Disabled,
            -> Unit
            RemoteIntroVideoStatus.Invalid -> {
                observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
            }
            RemoteIntroVideoStatus.Candidate -> {
                if (event.remoteConfigurationAllowed) {
                    enqueueCandidate(
                        source = requireNotNull(event.configuration.introVideo),
                        purgeEpoch = event.purgeEpoch,
                    )
                }
            }
        }
        initializeLaunchDecisionIfNeeded()
    }

    private suspend fun initializeLaunchDecisionIfNeeded() {
        if (launchDecisionInitialized) return
        val currentDecision = mutableLaunchDecision.value
        val decisionResult = if (remoteMediaPurger.isPending && !currentDecision.isComplete) {
            IntroMediaOperationResult.Success(IntroLaunchDecision.complete(bundledNotRequired()))
        } else {
            captureIntroMediaOperation { launchDecisionResolver.completeIfNeeded(currentDecision) }
        }
        mutableLaunchDecision.value = when (decisionResult) {
            is IntroMediaOperationResult.Success -> decisionResult.value
            is IntroMediaOperationResult.Failure -> IntroLaunchDecision.complete(bundledNotRequired())
        }
        protectedLaunchFile = (
            mutableLaunchDecision.value.request?.mediaSource as? IntroMediaSource.Remote
            )?.file
        launchDecisionInitialized = true
        if (decisionResult is IntroMediaOperationResult.Failure) {
            captureIntroMediaOperation {
                observability.recordDiagnostic(decisionResult.diagnosticCode)
            }
        }
        startNextResolutionIfPossible()
    }

    private suspend fun processConsentChanged(event: AndroidRemoteMediaEvent.ConsentChanged) {
        if (event.sequence <= lastConsentSequence) return
        lastConsentSequence = event.sequence
        val previousConsent = remoteConfigurationAllowed
        remoteConfigurationAllowed = event.remoteConfigurationAllowed
        when {
            !event.remoteConfigurationAllowed -> {
                cancelResolution()
                queuedRemoteSources.clear()
                remoteMediaPurger.requirePurge(
                    epoch = event.purgeEpoch,
                    removeLaunchFile = true,
                    protectedLaunchFile = protectedLaunchFile,
                )
            }
            previousConsent != true -> {
                remoteMediaPurger.retry(protectedLaunchFile)
                startNextResolutionIfPossible()
            }
        }
    }

    private suspend fun processConfigurationChanged(event: AndroidRemoteMediaEvent.ConfigurationChanged) {
        if (event.sequence <= lastConfigurationSequence) return
        lastConfigurationSequence = event.sequence
        val previousStatus = remoteVideoStatus
        remoteVideoStatus = event.configuration.introVideoStatus
        if (event.configuration.introVideoStatus != RemoteIntroVideoStatus.Disabled) {
            remoteMediaPurger.retry(protectedLaunchFile)
        }
        when (event.configuration.introVideoStatus) {
            RemoteIntroVideoStatus.Unavailable -> Unit
            RemoteIntroVideoStatus.Invalid -> {
                observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
            }
            RemoteIntroVideoStatus.Disabled -> {
                if (previousStatus != RemoteIntroVideoStatus.Disabled) {
                    cancelResolution()
                    queuedRemoteSources.clear()
                    remoteMediaPurger.requirePurge(
                        epoch = event.purgeEpoch,
                        removeLaunchFile = false,
                        protectedLaunchFile = protectedLaunchFile,
                    )
                }
            }
            RemoteIntroVideoStatus.Candidate -> {
                if (remoteConfigurationAllowed == true) {
                    enqueueCandidate(
                        source = requireNotNull(event.configuration.introVideo),
                        purgeEpoch = event.purgeEpoch,
                    )
                }
            }
        }
        startNextResolutionIfPossible()
    }

    private fun enqueueCandidate(source: RemoteIntroVideo, purgeEpoch: RemoteMediaPurgeEpoch) {
        if (observability.remoteMediaPurgeEpoch.value.isNewerThan(purgeEpoch)) return
        val latestKnownRevision = maxOf(
            firstLaunchStore.lastPresentedRemoteRevision(),
            inFlightSource?.revision ?: NO_REMOTE_REVISION,
            queuedRemoteSources.peekLast()?.source?.revision ?: NO_REMOTE_REVISION,
        )
        if (source.revision <= latestKnownRevision) return
        queuedRemoteSources.addLast(QueuedRemoteSource(source = source, purgeEpoch = purgeEpoch))
        startNextResolutionIfPossible()
    }

    private fun startNextResolutionIfPossible() {
        if (!launchDecisionInitialized) return
        if (remoteConfigurationAllowed != true) return
        if (remoteVideoStatus == RemoteIntroVideoStatus.Disabled) return
        if (remoteMediaPurger.isPending) return
        if (resolutionJob != null) return
        val queuedSource = queuedRemoteSources.pollCurrentGenerationSource(
            currentPurgeEpoch = observability.remoteMediaPurgeEpoch.value,
            firstLaunchStore = firstLaunchStore,
        ) ?: return
        val source = queuedSource.source
        val resolutionPurgeEpoch = queuedSource.purgeEpoch
        nextResolutionAttemptId += 1
        val attemptId = nextResolutionAttemptId
        inFlightAttemptId = attemptId
        inFlightSource = source
        resolutionJob = coroutineScope.launch {
            val resolution = captureIntroMediaOperation { cache.resolve(source = source) }
            val failureAlreadyReported = resolution is IntroMediaOperationResult.Failure
            val resolvedFile = when (resolution) {
                is IntroMediaOperationResult.Success -> resolution.value
                is IntroMediaOperationResult.Failure -> {
                    captureIntroMediaOperation {
                        observability.recordDiagnostic(resolution.diagnosticCode)
                    }
                    null
                }
            }
            managerEventChannel.send(
                IntroMediaManagerEvent.ResolutionCompleted(
                    attemptId = attemptId,
                    source = source,
                    purgeEpochAtStart = resolutionPurgeEpoch,
                    resolvedFile = resolvedFile,
                    failureAlreadyReported = failureAlreadyReported,
                ),
            )
        }
    }

    private suspend fun processResolutionCompleted(event: IntroMediaManagerEvent.ResolutionCompleted) {
        if (event.attemptId != inFlightAttemptId || event.source != inFlightSource) return
        resolutionJob = null
        inFlightAttemptId = null
        inFlightSource = null
        val policyInvalidated =
            !observability.consent.value.remoteConfigurationAllowed ||
                observability.remoteConfiguration.value.introVideoStatus == RemoteIntroVideoStatus.Disabled ||
                observability.remoteMediaPurgeEpoch.value.isNewerThan(event.purgeEpochAtStart)
        if (!policyInvalidated) {
            captureIntroMediaOperation {
                resolvedIntroPublisher.publish(
                    source = event.source,
                    resolvedFile = event.resolvedFile,
                    protectedLaunchFile = protectedLaunchFile,
                    failureAlreadyReported = event.failureAlreadyReported,
                )
            }.reportFailureTo(observability)
        }
        captureIntroMediaOperation { cleanupReleasedLaunchFileIfIdle() }.reportFailureTo(observability)
        startNextResolutionIfPossible()
    }

    private suspend fun cancelResolution() {
        val activeResolution = resolutionJob
        activeResolution?.cancelAndJoin()
        resolutionJob = null
        inFlightAttemptId = null
        inFlightSource = null
    }

    private suspend fun cleanupReleasedLaunchFileIfIdle() {
        if (!launchRemoteConsumed || resolutionJob != null) return
        val pending = firstLaunchStore.pendingRemoteIntro()
        val pendingFile = when (val result = captureIntroMediaOperation { pending?.let { cache.findCached(it) } }) {
            is IntroMediaOperationResult.Success -> result.value
            is IntroMediaOperationResult.Failure -> {
                observability.recordDiagnostic(result.diagnosticCode)
                return
            }
        }
        if (pending != null && pendingFile == null) {
            firstLaunchStore.clearPendingRemoteIntro()
        }
        if (!cache.clear(protectedFiles = setOfNotNull(pendingFile))) {
            observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
        }
        launchRemoteConsumed = false
    }

    fun close() {
        remoteMediaSubscription?.close()
        remoteMediaSubscription = null
        managerEventChannel.close()
        coroutineScope.cancel()
    }
}

private fun ArrayDeque<QueuedRemoteSource>.pollCurrentGenerationSource(
    currentPurgeEpoch: RemoteMediaPurgeEpoch,
    firstLaunchStore: FirstLaunchStore,
): QueuedRemoteSource? {
    while (isNotEmpty()) {
        val queuedSource = removeFirst()
        val latestDurableRevision = maxOf(
            firstLaunchStore.lastPresentedRemoteRevision(),
            firstLaunchStore.pendingRemoteIntro()?.revision ?: NO_REMOTE_REVISION,
        )
        if (
            !currentPurgeEpoch.isNewerThan(queuedSource.purgeEpoch) &&
            queuedSource.source.revision > latestDurableRevision
        ) {
            return queuedSource
        }
    }
    return null
}

private fun AndroidRemoteMediaEvent.Snapshot.purgeMode(): SnapshotPurgeMode {
    val consentPurgeRequired = purgeEpoch.consentRevocations > acknowledgedPurgeEpoch.consentRevocations
    val disablePurgeRequired = purgeEpoch.explicitDisables > acknowledgedPurgeEpoch.explicitDisables
    return when {
        !remoteConfigurationAllowed || consentPurgeRequired -> SnapshotPurgeMode.RemoveAll
        configuration.introVideoStatus == RemoteIntroVideoStatus.Disabled || disablePurgeRequired -> {
            SnapshotPurgeMode.PreserveLaunchFile
        }
        else -> SnapshotPurgeMode.None
    }
}

private fun AndroidRemoteMediaEvent.Snapshot.requiredPurge(): PendingRemoteMediaPurge? = when (purgeMode()) {
    SnapshotPurgeMode.RemoveAll -> PendingRemoteMediaPurge(epoch = purgeEpoch, removeLaunchFile = true)
    SnapshotPurgeMode.PreserveLaunchFile -> PendingRemoteMediaPurge(epoch = purgeEpoch, removeLaunchFile = false)
    SnapshotPurgeMode.None -> null
}

private suspend fun <T> captureIntroMediaOperation(operation: suspend () -> T): IntroMediaOperationResult<T> =
    runCatching {
        operation()
    }.fold(
        onSuccess = { value -> IntroMediaOperationResult.Success(value) },
        onFailure = { failure ->
            when (failure) {
                is CancellationException -> throw failure
                is Exception -> IntroMediaOperationResult.Failure(failure.toIntroMediaDiagnosticCode())
                else -> throw failure
            }
        },
    )

private suspend fun IntroMediaOperationResult<*>.reportFailureTo(observability: AndroidObservabilityController) {
    if (this is IntroMediaOperationResult.Failure) {
        captureIntroMediaOperation {
            observability.recordDiagnostic(diagnosticCode)
        }
    }
}

private fun Exception.toIntroMediaDiagnosticCode(): DiagnosticCode = if (this is IOException) {
    DiagnosticCode.IntroVideoIntegrityFailed
} else {
    DiagnosticCode.UnexpectedApplicationState
}

private fun RemoteMediaPurgeEpoch.isNewerThan(other: RemoteMediaPurgeEpoch): Boolean =
    consentRevocations > other.consentRevocations || explicitDisables > other.explicitDisables

private const val NO_REMOTE_REVISION = 0L
