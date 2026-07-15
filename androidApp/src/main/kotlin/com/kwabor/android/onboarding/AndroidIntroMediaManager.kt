package com.kwabor.android.onboarding

import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.RemoteIntroVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

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

private data class IntroMediaConfiguration(
    val remoteConfigurationAllowed: Boolean,
    val source: RemoteIntroVideo?,
)

class AndroidIntroMediaManager internal constructor(
    private val observability: AndroidObservabilityController,
    private val cache: IntroVideoCache,
    private val firstLaunchStore: FirstLaunchStore,
    dispatcherProvider: DispatcherProvider,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    private val mutableLaunchDecision = MutableStateFlow(initialLaunchDecision())
    internal val launchDecision: StateFlow<IntroLaunchDecision> = mutableLaunchDecision.asStateFlow()

    fun start() {
        coroutineScope.launch {
            completeLaunchDecisionIfNeeded()
            val protectedLaunchFile = (
                mutableLaunchDecision.value.request?.mediaSource as? IntroMediaSource.Remote
                )?.file
            combine(observability.consent, observability.remoteConfiguration) { consent, configuration ->
                IntroMediaConfiguration(
                    remoteConfigurationAllowed = consent.remoteConfigurationAllowed,
                    source = configuration.introVideo,
                )
            }.collectLatest { configuration ->
                synchronizeConfiguration(
                    configuration = configuration,
                    protectedLaunchFile = protectedLaunchFile,
                )
            }
        }
    }

    private suspend fun synchronizeConfiguration(configuration: IntroMediaConfiguration, protectedLaunchFile: File?) {
        if (!configuration.remoteConfigurationAllowed) {
            firstLaunchStore.clearPendingRemoteIntro()
            cache.clear()
            return
        }
        val source = configuration.source
        if (source == null) {
            firstLaunchStore.clearPendingRemoteIntro()
            cache.clear(protectedFile = protectedLaunchFile)
            return
        }
        val latestPendingRevision = firstLaunchStore.pendingRemoteIntro()?.revision ?: NO_REMOTE_REVISION
        val latestKnownRevision = maxOf(
            firstLaunchStore.lastPresentedRemoteRevision(),
            latestPendingRevision,
        )
        if (source.revision <= latestKnownRevision) return

        val file = try {
            cache.resolve(source = source, protectedFile = protectedLaunchFile)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        if (file == null) {
            observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
            return
        }
        firstLaunchStore.markRemoteIntroPending(
            PendingRemoteIntro(
                revision = source.revision,
                sha256 = source.sha256,
                fileName = file.name,
            ),
        )
    }

    private fun initialLaunchDecision(): IntroLaunchDecision = if (firstLaunchStore.isBundledIntroRequired()) {
        IntroLaunchDecision.complete(
            IntroLaunchRequest(isRequired = true, mediaSource = IntroMediaSource.Bundled),
        )
    } else {
        IntroLaunchDecision.Pending
    }

    private suspend fun completeLaunchDecisionIfNeeded() {
        if (mutableLaunchDecision.value.isComplete) return
        mutableLaunchDecision.value = IntroLaunchDecision.complete(createReturningLaunchRequest())
    }

    private suspend fun createReturningLaunchRequest(): IntroLaunchRequest {
        if (!observability.consent.value.remoteConfigurationAllowed) {
            return IntroLaunchRequest(isRequired = false, mediaSource = IntroMediaSource.Bundled)
        }
        val pending = firstLaunchStore.pendingRemoteIntro()
            ?: return IntroLaunchRequest(isRequired = false, mediaSource = IntroMediaSource.Bundled)
        val activeSource = observability.remoteConfiguration.value.introVideo
        val pendingMatchesActiveSource = activeSource != null &&
            activeSource.revision == pending.revision &&
            activeSource.sha256 == pending.sha256
        if (!pendingMatchesActiveSource) {
            firstLaunchStore.clearPendingRemoteIntro()
            return IntroLaunchRequest(isRequired = false, mediaSource = IntroMediaSource.Bundled)
        }
        val file = try {
            cache.findCached(pending)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        if (file == null) {
            firstLaunchStore.clearPendingRemoteIntro()
            return IntroLaunchRequest(isRequired = false, mediaSource = IntroMediaSource.Bundled)
        }
        if (!pendingStillMatchesActiveConfiguration(pending)) {
            firstLaunchStore.clearPendingRemoteIntro()
            return IntroLaunchRequest(isRequired = false, mediaSource = IntroMediaSource.Bundled)
        }
        return IntroLaunchRequest(
            isRequired = true,
            mediaSource = IntroMediaSource.Remote(file = file, revision = pending.revision),
        )
    }

    private fun pendingStillMatchesActiveConfiguration(pending: PendingRemoteIntro): Boolean {
        if (!observability.consent.value.remoteConfigurationAllowed) return false
        val activeSource = observability.remoteConfiguration.value.introVideo ?: return false
        return activeSource.revision == pending.revision && activeSource.sha256 == pending.sha256
    }

    fun close() {
        coroutineScope.cancel()
    }
}

private const val NO_REMOTE_REVISION = 0L
