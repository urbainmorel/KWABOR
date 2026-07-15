package com.kwabor.android.onboarding

import com.kwabor.android.observability.AndroidObservabilityBackend
import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.android.observability.ObservabilityConsentStore
import com.kwabor.android.observability.PerformanceTrace
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName
import com.kwabor.shared.domain.observability.RemoteFeatureConfiguration
import com.kwabor.shared.domain.observability.RemoteIntroVideo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidIntroMediaManagerTest {
    @Test
    fun firstLaunchUsesBundledOfflineAndQueuesRemoteForOnlyTheNextLaunch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = createController()
        val cache = FakeIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = true)
        controller.start()
        val firstSession = createManager(controller, cache, store, dispatcher)

        assertEquals(
            IntroLaunchDecision.complete(
                IntroLaunchRequest(isRequired = true, mediaSource = IntroMediaSource.Bundled),
            ),
            firstSession.launchDecision.value,
        )

        firstSession.start()
        advanceUntilIdle()

        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        assertEquals(IntroMediaSource.Bundled, firstSession.launchDecision.value.request?.mediaSource)

        firstSession.close()
        store.markBundledIntroSeen()
        val nextSession = createManager(controller, cache, store, dispatcher)
        assertFalse(nextSession.launchDecision.value.isComplete)

        nextSession.start()
        advanceUntilIdle()
        val nextRequest = requireNotNull(nextSession.launchDecision.value.request)
        val remote = assertIs<IntroMediaSource.Remote>(nextRequest.mediaSource)

        assertTrue(nextRequest.isRequired)
        assertSame(cache.file, remote.file)
        assertEquals(REMOTE_VIDEO.revision, remote.revision)
        nextSession.close()
        controller.close()
    }

    @Test
    fun presentedRemoteRevisionIsNotPresentedAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = createController()
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        controller.start()
        val presentingSession = createManager(controller, cache, store, dispatcher)
        presentingSession.start()
        advanceUntilIdle()

        assertIs<IntroMediaSource.Remote>(presentingSession.launchDecision.value.request?.mediaSource)
        store.markRemoteIntroPresented(REMOTE_VIDEO.revision)
        val followingSession = createManager(controller, cache, store, dispatcher)
        followingSession.start()
        advanceUntilIdle()
        val followingRequest = requireNotNull(followingSession.launchDecision.value.request)

        assertFalse(followingRequest.isRequired)
        assertEquals(IntroMediaSource.Bundled, followingRequest.mediaSource)
        presentingSession.close()
        followingSession.close()
        controller.close()
    }

    @Test
    fun revocationPurgesPendingRevisionAndEveryCachedRemote() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = createController()
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        controller.updateConsent(ObservabilityConsent())
        advanceUntilIdle()

        assertNull(store.pending)
        assertEquals(listOf<File?>(null), cache.clearProtectedFiles)
        assertNull(cache.findCached(PENDING_REMOTE))
        manager.close()
        controller.close()
    }

    @Test
    fun disabledRemoteConfigurationCannotPresentAStalePendingRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        val controller = createController(RemoteFeatureConfiguration.SafeDefaults)
        controller.start()

        val manager = createManager(controller, cache, store, dispatcher)
        assertFalse(manager.launchDecision.value.isComplete)
        manager.start()
        advanceUntilIdle()
        val request = requireNotNull(manager.launchDecision.value.request)

        assertFalse(request.isRequired)
        assertEquals(IntroMediaSource.Bundled, request.mediaSource)
        assertNull(store.pending)
        manager.close()
        controller.close()
    }

    @Test
    fun changedRemoteRevisionIsQueuedForNextLaunchWithoutReplacingCurrentDecision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        val controller = createController(RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO))
        controller.start()

        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()
        val request = requireNotNull(manager.launchDecision.value.request)

        assertFalse(request.isRequired)
        assertEquals(IntroMediaSource.Bundled, request.mediaSource)
        assertEquals(NEWER_REMOTE_VIDEO.revision, store.pending?.revision)
        assertEquals(listOf(NEWER_REMOTE_VIDEO), cache.resolvedSources)
        manager.close()
        controller.close()
    }

    @Test
    fun missingOrInvalidCachedFileClearsPendingInsteadOfPresentingIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = createController()
        val cache = FakeIntroVideoCache()
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        controller.start()

        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()
        val request = requireNotNull(manager.launchDecision.value.request)

        assertFalse(request.isRequired)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        manager.close()
        controller.close()
    }

    private fun createController(
        configuration: RemoteFeatureConfiguration = RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO),
    ): AndroidObservabilityController = AndroidObservabilityController(
        backend = FakeBackend(configuration),
        consentStore = FakeConsentStore(ALLOWED_CONSENT),
    )

    private fun createManager(
        controller: AndroidObservabilityController,
        cache: FakeIntroVideoCache,
        store: FakeFirstLaunchStore,
        dispatcher: CoroutineDispatcher,
    ): AndroidIntroMediaManager = AndroidIntroMediaManager(
        observability = controller,
        cache = cache,
        firstLaunchStore = store,
        dispatcherProvider = FakeDispatcherProvider(dispatcher),
    )
}

private class FakeIntroVideoCache : IntroVideoCache {
    val file = File(PENDING_REMOTE.fileName)
    val resolvedSources = mutableListOf<RemoteIntroVideo>()
    val clearProtectedFiles = mutableListOf<File?>()
    private var isAvailable = false

    override suspend fun resolve(source: RemoteIntroVideo, protectedFile: File?): File {
        resolvedSources += source
        isAvailable = true
        return file
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? = file.takeIf {
        isAvailable && pending.fileName == it.name
    }

    override suspend fun clear(protectedFile: File?) {
        clearProtectedFiles += protectedFile
        if (protectedFile != file) {
            isAvailable = false
        }
    }

    fun makeAvailable() {
        isAvailable = true
    }
}

private class FakeFirstLaunchStore(
    isBundledIntroRequired: Boolean,
    var pending: PendingRemoteIntro? = null,
) : FirstLaunchStore {
    private var bundledIntroRequired = isBundledIntroRequired
    private var lastPresentedRevision = 0L

    override fun isBundledIntroRequired(): Boolean = bundledIntroRequired

    override fun markBundledIntroSeen() {
        bundledIntroRequired = false
    }

    override fun pendingRemoteIntro(): PendingRemoteIntro? = pending?.takeIf {
        it.revision > lastPresentedRevision
    }

    override fun lastPresentedRemoteRevision(): Long = lastPresentedRevision

    override fun markRemoteIntroPending(intro: PendingRemoteIntro) {
        val pendingRevision = pending?.revision ?: 0L
        if (intro.revision > maxOf(lastPresentedRevision, pendingRevision)) {
            pending = intro
        }
    }

    override fun markRemoteIntroPresented(revision: Long) {
        lastPresentedRevision = maxOf(lastPresentedRevision, revision)
        if (pending?.revision != null && requireNotNull(pending).revision <= revision) {
            pending = null
        }
    }

    override fun clearPendingRemoteIntro() {
        pending = null
    }
}

private class FakeConsentStore(initialConsent: ObservabilityConsent) : ObservabilityConsentStore {
    private var consent = initialConsent

    override fun read(): ObservabilityConsent = consent

    override fun write(consent: ObservabilityConsent) {
        this.consent = consent
    }
}

private class FakeBackend(
    private val configuration: RemoteFeatureConfiguration,
) : AndroidObservabilityBackend {
    override val isConfigured = true

    override fun applyConsent(consent: ObservabilityConsent) = Unit

    override fun track(event: AnalyticsEvent) = Unit

    override fun recordDiagnostic(code: DiagnosticCode) = Unit

    override fun startTrace(name: PerformanceTraceName): PerformanceTrace = PerformanceTrace.None

    override fun fetchRemoteConfiguration(onResult: (RemoteFeatureConfiguration?) -> Unit) {
        onResult(configuration)
    }

    override fun readCachedRemoteConfiguration(): RemoteFeatureConfiguration = configuration

    override fun startRemoteConfigurationUpdates(onResult: (RemoteFeatureConfiguration?) -> Unit) = Unit

    override fun stopRemoteConfigurationUpdates() = Unit
}

private class FakeDispatcherProvider(
    dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val ALLOWED_CONSENT = ObservabilityConsent(remoteConfigurationAllowed = true)
private val REMOTE_VIDEO = RemoteIntroVideo(
    url = "https://cdn.kwabor.example/intro.mp4",
    sha256 = "a".repeat(64),
    revision = 1,
)
private val NEWER_REMOTE_VIDEO = RemoteIntroVideo(
    url = "https://cdn.kwabor.example/intro-v2.mp4",
    sha256 = "b".repeat(64),
    revision = 2,
)
private val PENDING_REMOTE = PendingRemoteIntro(
    revision = REMOTE_VIDEO.revision,
    sha256 = REMOTE_VIDEO.sha256,
    fileName = "intro-${REMOTE_VIDEO.revision}.mp4",
)
