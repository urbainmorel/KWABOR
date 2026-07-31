package com.kwabor.android.onboarding

import com.kwabor.android.observability.AndroidObservabilityBackend
import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.android.observability.ObservabilityConsentStore
import com.kwabor.android.observability.PerformanceTrace
import com.kwabor.android.observability.RemoteMediaPurgeEpoch
import com.kwabor.android.observability.RemoteMediaPurgeState
import com.kwabor.shared.app.DispatcherProvider
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName
import com.kwabor.shared.domain.observability.RemoteFeatureConfiguration
import com.kwabor.shared.domain.observability.RemoteIntroVideo
import com.kwabor.shared.domain.observability.RemoteIntroVideoStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
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

        store.markBundledIntroSeen()
        firstSession.onIntroConsumed(IntroMediaSource.Bundled)
        advanceUntilIdle()
        assertFalse(requireNotNull(firstSession.launchDecision.value.request).isRequired)
        firstSession.close()
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
        assertEquals(listOf(emptySet()), cache.clearProtectedFiles)
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
        val controller = createController(
            RemoteFeatureConfiguration(introVideoStatus = RemoteIntroVideoStatus.Disabled),
        )
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
    fun unavailableRemoteConfigurationPreservesTheLastValidatedPendingRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        val controller = createController(RemoteFeatureConfiguration.SafeDefaults)
        controller.start()

        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        assertIs<IntroMediaSource.Remote>(manager.launchDecision.value.request?.mediaSource)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        assertTrue(cache.clearProtectedFiles.isEmpty())
        manager.close()
        controller.close()
    }

    @Test
    fun transientCachedMediaReadFailurePreservesThePendingRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = FailingFindIntroVideoCache()
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        val controller = createController(RemoteFeatureConfiguration.SafeDefaults)
        controller.start()

        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()
        val request = requireNotNull(manager.launchDecision.value.request)

        assertFalse(request.isRequired)
        assertEquals(IntroMediaSource.Bundled, request.mediaSource)
        assertEquals(PENDING_REMOTE, store.pending)
        assertEquals(0, cache.clearCalls)
        manager.close()
        controller.close()
    }

    @Test
    fun invalidRemoteConfigurationPreservesTheLastValidatedPendingRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        val controller = createController(
            RemoteFeatureConfiguration(introVideoStatus = RemoteIntroVideoStatus.Invalid),
        )
        controller.start()

        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()
        val request = requireNotNull(manager.launchDecision.value.request)
        val remote = assertIs<IntroMediaSource.Remote>(request.mediaSource)

        assertTrue(request.isRequired)
        assertEquals(REMOTE_VIDEO.revision, remote.revision)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        assertTrue(cache.clearProtectedFiles.isEmpty())
        manager.close()
        controller.close()
    }

    @Test
    fun invalidNewerMediaDoesNotReplaceTheLastValidatedPendingRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = FakeIntroVideoCache().apply {
            makeAvailable()
            failResolutions()
        }
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
        val remote = assertIs<IntroMediaSource.Remote>(request.mediaSource)

        assertEquals(REMOTE_VIDEO.revision, remote.revision)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        assertEquals(listOf(NEWER_REMOTE_VIDEO), cache.resolvedSources)
        manager.close()
        controller.close()
    }

    @Test
    fun failedPendingPersistenceKeepsThePreviousRevisionAndCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
            pendingWritesSucceed = false,
        )
        val controller = createController(RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO))
        controller.start()

        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        assertEquals(listOf(NEWER_REMOTE_VIDEO), cache.resolvedSources)
        assertTrue(cache.clearProtectedFiles.isEmpty())
        assertEquals(cache.file, cache.findCached(PENDING_REMOTE))
        manager.close()
        controller.close()
    }

    @Test
    fun reconsentWaitsForThePurgeBeforeResolvingTheLatestCandidate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(RemoteFeatureConfiguration.SafeDefaults)
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(ALLOWED_CONSENT),
        )
        val cache = BlockingPurgeIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = false)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        controller.updateConsent(ObservabilityConsent())
        runCurrent()
        assertTrue(cache.clearStarted.isCompleted)

        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        controller.updateConsent(ALLOWED_CONSENT)
        runCurrent()
        assertTrue(cache.resolvedSources.isEmpty())

        cache.releaseClear.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO), cache.resolvedSources)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        manager.close()
        controller.close()
    }

    @Test
    fun consentRevokedWhileCachedMediaIsValidatedCannotPublishARemoteLaunch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = createController()
        val cache = BlockingFindIntroVideoCache()
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        runCurrent()
        assertTrue(cache.findStarted.isCompleted)

        controller.updateConsent(ObservabilityConsent())
        cache.releaseFind.complete(Unit)
        advanceUntilIdle()

        val request = requireNotNull(manager.launchDecision.value.request)
        assertFalse(request.isRequired)
        assertEquals(IntroMediaSource.Bundled, request.mediaSource)
        assertNull(store.pending)
        assertEquals(listOf(emptySet()), cache.clearProtectedFiles)
        manager.close()
        controller.close()
    }

    @Test
    fun briefConsentRevocationWhileCachedMediaIsValidatedStillInvalidatesThatLaunch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = createController()
        val cache = BlockingFindIntroVideoCache()
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        runCurrent()
        assertTrue(cache.findStarted.isCompleted)

        controller.updateConsent(ObservabilityConsent())
        controller.updateConsent(ALLOWED_CONSENT)
        cache.releaseFind.complete(Unit)
        advanceUntilIdle()

        val request = requireNotNull(manager.launchDecision.value.request)
        assertFalse(request.isRequired)
        assertEquals(IntroMediaSource.Bundled, request.mediaSource)
        assertNull(store.pending)
        assertTrue(cache.clearProtectedFiles.contains(emptySet()))
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
        val currentRemote = assertIs<IntroMediaSource.Remote>(request.mediaSource)

        assertTrue(request.isRequired)
        assertEquals(REMOTE_VIDEO.revision, currentRemote.revision)
        assertEquals(NEWER_REMOTE_VIDEO.revision, store.pending?.revision)
        assertEquals(listOf(NEWER_REMOTE_VIDEO), cache.resolvedSources)
        manager.close()
        controller.close()
    }

    @Test
    fun higherInvalidPublicationDoesNotCancelAValidCandidateAlreadyInFlight() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(ALLOWED_CONSENT),
        )
        val cache = SequencedIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = false)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)

        manager.start()
        runCurrent()
        assertTrue(cache.firstResolutionStarted.isCompleted)

        backend.emit(RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO))
        runCurrent()
        cache.releaseFirstResolution.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO, NEWER_REMOTE_VIDEO), cache.resolvedSources)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        manager.close()
        controller.close()
    }

    @Test
    fun backToBackValidThenInvalidPublicationsStillQualifyTheValidCandidate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(RemoteFeatureConfiguration.SafeDefaults)
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(ALLOWED_CONSENT),
        )
        val cache = FakeIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = false)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()

        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        backend.emit(RemoteFeatureConfiguration(introVideoStatus = RemoteIntroVideoStatus.Invalid))
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO), cache.resolvedSources)
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
        cache: IntroVideoCache,
        store: FakeFirstLaunchStore,
        dispatcher: CoroutineDispatcher,
    ): AndroidIntroMediaManager = AndroidIntroMediaManager(
        observability = controller,
        cache = cache,
        firstLaunchStore = store,
        dispatcherProvider = FakeDispatcherProvider(dispatcher),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidIntroMediaManagerLifecycleTest {
    @Test
    fun briefDisablePurgesTheOldPendingBeforeAReenabledCandidateIsQualified() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(ALLOWED_CONSENT),
        )
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        backend.emit(RemoteFeatureConfiguration(introVideoStatus = RemoteIntroVideoStatus.Disabled))
        backend.emit(RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO))
        advanceUntilIdle()

        assertEquals(NEWER_REMOTE_VIDEO.revision, store.pending?.revision)
        assertEquals(listOf(NEWER_REMOTE_VIDEO), cache.resolvedSources)
        assertTrue(cache.clearProtectedFiles.any { protectedFiles -> cache.file in protectedFiles })
        manager.close()
        controller.close()
    }

    @Test
    fun failedPurgeBlocksCandidatesUntilTheCacheCanActuallyBeCleared() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(RemoteFeatureConfiguration.SafeDefaults)
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(ALLOWED_CONSENT),
        )
        val cache = RecoveringPurgeIntroVideoCache(failedClearAttempts = 2)
        val store = FakeFirstLaunchStore(isBundledIntroRequired = true)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        backend.emit(RemoteFeatureConfiguration(introVideoStatus = RemoteIntroVideoStatus.Disabled))
        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        advanceUntilIdle()

        assertTrue(cache.resolvedSources.isEmpty())
        assertNull(store.pending)

        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO), cache.resolvedSources)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        manager.close()
        controller.close()
    }

    @Test
    fun queuedDisableIsAppliedBeforeTheLaunchSnapshotCanPresentStaleMedia() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(
            configuration = RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO),
            cachedConfiguration = RemoteFeatureConfiguration(
                introVideoStatus = RemoteIntroVideoStatus.Disabled,
            ),
        )
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(ALLOWED_CONSENT),
        )
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
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
        assertEquals(IntroMediaSource.Bundled, request.mediaSource)
        assertEquals(NEWER_REMOTE_VIDEO.revision, store.pending?.revision)
        assertEquals(listOf(NEWER_REMOTE_VIDEO), cache.resolvedSources)
        assertTrue(cache.clearProtectedFiles.contains(emptySet()))
        manager.close()
        controller.close()
    }

    @Test
    fun unacknowledgedDisableSurvivesProcessRestartAndPurgesStalePendingMedia() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val consentStore = FakeConsentStore(ALLOWED_CONSENT)
        val firstController = AndroidObservabilityController(
            backend = FakeBackend(
                configuration = RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO),
                cachedConfiguration = RemoteFeatureConfiguration(
                    introVideoStatus = RemoteIntroVideoStatus.Disabled,
                ),
            ),
            consentStore = consentStore,
        )
        firstController.start()
        firstController.close()

        val restartedController = AndroidObservabilityController(
            backend = FakeBackend(RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO)),
            consentStore = consentStore,
        )
        val cache = FakeIntroVideoCache().apply { makeAvailable() }
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        restartedController.start()
        val manager = createManager(restartedController, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()
        val request = requireNotNull(manager.launchDecision.value.request)

        assertFalse(request.isRequired)
        assertEquals(IntroMediaSource.Bundled, request.mediaSource)
        assertEquals(NEWER_REMOTE_VIDEO.revision, store.pending?.revision)
        assertTrue(cache.clearProtectedFiles.contains(emptySet()))
        manager.close()
        restartedController.close()
    }

    @Test
    fun failedRestartPurgeCannotPresentStalePendingMedia() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val consentStore = FakeConsentStore(ALLOWED_CONSENT).apply {
            writeRequiredRemoteMediaPurgeEpoch(RemoteMediaPurgeEpoch(explicitDisables = 1))
        }
        val controller = AndroidObservabilityController(
            backend = FakeBackend(RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO)),
            consentStore = consentStore,
        )
        val cache = FailedPurgeIntroVideoCache()
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
            pendingClearsSucceed = false,
        )
        controller.start()

        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()
        val request = requireNotNull(manager.launchDecision.value.request)

        assertFalse(request.isRequired)
        assertEquals(IntroMediaSource.Bundled, request.mediaSource)
        assertEquals(PENDING_REMOTE, store.pending)
        assertTrue(cache.cachedMediaWasRead.not())
        assertTrue(cache.resolvedSources.isEmpty())
        manager.close()
        controller.close()
    }

    @Test
    fun queuedCandidateFromBeforeRevocationCannotReturnAfterReconsent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(RemoteFeatureConfiguration.SafeDefaults)
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(ALLOWED_CONSENT),
        )
        val cache = FakeIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = false)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        runCurrent()

        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        controller.updateConsent(ObservabilityConsent())
        backend.emit(RemoteFeatureConfiguration(introVideoStatus = RemoteIntroVideoStatus.Invalid))
        controller.updateConsent(ALLOWED_CONSENT)
        advanceUntilIdle()

        assertTrue(cache.resolvedSources.isEmpty())
        assertNull(store.pending)
        manager.close()
        controller.close()
    }

    @Test
    fun aLaterPublicationCanRetryTheSameRevisionAfterResolutionFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(ALLOWED_CONSENT),
        )
        val cache = FailsOnceIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = false)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO), cache.resolvedSources)
        assertNull(store.pending)

        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO, REMOTE_VIDEO), cache.resolvedSources)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        manager.close()
        controller.close()
    }

    @Test
    fun consumingTheCurrentRemoteKeepsOnlyANewerPendingRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = createController(RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO))
        val cache = VersionedIntroVideoCache(PENDING_REMOTE)
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        val launchSource = assertIs<IntroMediaSource.Remote>(manager.launchDecision.value.request?.mediaSource)
        assertEquals(NEWER_REMOTE_VIDEO.revision, store.pending?.revision)

        store.markRemoteIntroPresented(REMOTE_VIDEO.revision)
        manager.onIntroConsumed(launchSource)
        advanceUntilIdle()

        assertEquals(NEWER_REMOTE_VIDEO.revision, store.pending?.revision)
        assertEquals(setOf(File("intro-${NEWER_REMOTE_VIDEO.revision}.mp4")), cache.files)
        assertFalse(requireNotNull(manager.launchDecision.value.request).isRequired)
        manager.close()
        controller.close()
    }

    @Test
    fun consumedLaunchFileIsReleasedWhenTheInFlightCandidateFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = createController(RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO))
        val cache = BlockingFailedUpgradeIntroVideoCache(PENDING_REMOTE)
        val store = FakeFirstLaunchStore(
            isBundledIntroRequired = false,
            pending = PENDING_REMOTE,
        )
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        runCurrent()
        assertTrue(cache.resolutionStarted.isCompleted)
        val launchSource = assertIs<IntroMediaSource.Remote>(manager.launchDecision.value.request?.mediaSource)

        store.markRemoteIntroPresented(REMOTE_VIDEO.revision)
        manager.onIntroConsumed(launchSource)
        runCurrent()
        cache.releaseResolution.complete(Unit)
        advanceUntilIdle()

        assertTrue(cache.files.isEmpty())
        assertNull(store.pending)
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
        cache: IntroVideoCache,
        store: FakeFirstLaunchStore,
        dispatcher: CoroutineDispatcher,
    ): AndroidIntroMediaManager = AndroidIntroMediaManager(
        observability = controller,
        cache = cache,
        firstLaunchStore = store,
        dispatcherProvider = FakeDispatcherProvider(dispatcher),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidIntroMediaManagerFailureRecoveryTest {
    @Test
    fun acknowledgementFailuresKeepCandidatesBlockedUntilTheTombstoneIsDurable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(RemoteFeatureConfiguration.SafeDefaults)
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(
                initialConsent = ALLOWED_CONSENT,
                thrownAcknowledgementAttempts = 1,
                failedAcknowledgementAttempts = 1,
            ),
        )
        val cache = FakeIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = true)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        backend.emit(RemoteFeatureConfiguration(introVideoStatus = RemoteIntroVideoStatus.Disabled))
        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        advanceUntilIdle()

        assertTrue(cache.resolvedSources.isEmpty())
        assertNull(store.pending)

        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO), cache.resolvedSources)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        manager.close()
        controller.close()
    }

    @Test
    fun snapshotFailureCompletesTheLaunchFallbackAndKeepsTheActorAlive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(
            configuration = RemoteFeatureConfiguration(introVideoStatus = RemoteIntroVideoStatus.Invalid),
            diagnosticsThrow = true,
        )
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(DIAGNOSTICS_ALLOWED_CONSENT),
        )
        val cache = FakeIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = false)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()
        val request = requireNotNull(manager.launchDecision.value.request)

        assertFalse(request.isRequired)
        assertEquals(IntroMediaSource.Bundled, request.mediaSource)

        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO), cache.resolvedSources)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        manager.close()
        controller.close()
    }

    @Test
    fun resolutionFailureCompletesEvenWhenDiagnosticsAreUnavailable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(
            configuration = RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO),
            diagnosticsThrow = true,
        )
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(DIAGNOSTICS_ALLOWED_CONSENT),
        )
        val cache = ThrowingOnceIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = true)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO), cache.resolvedSources)
        assertNull(store.pending)

        backend.emit(RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO))
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO, REMOTE_VIDEO), cache.resolvedSources)
        assertEquals(REMOTE_VIDEO.revision, store.pending?.revision)
        manager.close()
        controller.close()
    }

    @Test
    fun failedPublicationCannotStrandTheNextQueuedCandidate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backend = FakeBackend(
            configuration = RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO),
            diagnosticsThrow = true,
        )
        val controller = AndroidObservabilityController(
            backend = backend,
            consentStore = FakeConsentStore(DIAGNOSTICS_ALLOWED_CONSENT),
        )
        val cache = NullThenSuccessfulIntroVideoCache()
        val store = FakeFirstLaunchStore(isBundledIntroRequired = true)
        controller.start()
        val manager = createManager(controller, cache, store, dispatcher)
        manager.start()
        runCurrent()
        cache.firstResolutionStarted.await()

        backend.emit(RemoteFeatureConfiguration(introVideo = NEWER_REMOTE_VIDEO))
        runCurrent()
        cache.releaseFirstResolution.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(REMOTE_VIDEO, NEWER_REMOTE_VIDEO), cache.resolvedSources)
        assertEquals(NEWER_REMOTE_VIDEO.revision, store.pending?.revision)
        manager.close()
        controller.close()
    }

    private fun createManager(
        controller: AndroidObservabilityController,
        cache: IntroVideoCache,
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
    val clearProtectedFiles = mutableListOf<Set<File>>()
    private var isAvailable = false
    private var resolutionsSucceed = true

    override suspend fun resolve(source: RemoteIntroVideo): File? {
        resolvedSources += source
        if (!resolutionsSucceed) return null
        isAvailable = true
        return file
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? = file.takeIf {
        isAvailable && pending.fileName == it.name
    }

    override suspend fun clear(protectedFiles: Set<File>): Boolean {
        clearProtectedFiles += protectedFiles
        if (file !in protectedFiles) {
            isAvailable = false
        }
        return true
    }

    fun makeAvailable() {
        isAvailable = true
    }

    fun failResolutions() {
        resolutionsSucceed = false
    }
}

private class SequencedIntroVideoCache : IntroVideoCache {
    val firstResolutionStarted = CompletableDeferred<Unit>()
    val releaseFirstResolution = CompletableDeferred<Unit>()
    val resolvedSources = mutableListOf<RemoteIntroVideo>()

    override suspend fun resolve(source: RemoteIntroVideo): File? {
        resolvedSources += source
        if (source.revision != REMOTE_VIDEO.revision) return null
        firstResolutionStarted.complete(Unit)
        releaseFirstResolution.await()
        return File(PENDING_REMOTE.fileName)
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? = null

    override suspend fun clear(protectedFiles: Set<File>): Boolean = true
}

private class FailsOnceIntroVideoCache : IntroVideoCache {
    val resolvedSources = mutableListOf<RemoteIntroVideo>()

    override suspend fun resolve(source: RemoteIntroVideo): File? {
        resolvedSources += source
        return File(PENDING_REMOTE.fileName).takeIf { resolvedSources.size > 1 }
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? = null

    override suspend fun clear(protectedFiles: Set<File>): Boolean = true
}

private class VersionedIntroVideoCache(initialPending: PendingRemoteIntro) : IntroVideoCache {
    val files = mutableSetOf(File(initialPending.fileName))

    override suspend fun resolve(source: RemoteIntroVideo): File {
        val file = File("intro-${source.revision}.mp4")
        files += file
        return file
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? =
        files.firstOrNull { file -> file.name == pending.fileName }

    override suspend fun clear(protectedFiles: Set<File>): Boolean {
        files.retainAll(protectedFiles)
        return true
    }
}

private class BlockingFailedUpgradeIntroVideoCache(initialPending: PendingRemoteIntro) : IntroVideoCache {
    val files = mutableSetOf(File(initialPending.fileName))
    val resolutionStarted = CompletableDeferred<Unit>()
    val releaseResolution = CompletableDeferred<Unit>()

    override suspend fun resolve(source: RemoteIntroVideo): File? {
        resolutionStarted.complete(Unit)
        releaseResolution.await()
        return null
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? =
        files.firstOrNull { file -> file.name == pending.fileName }

    override suspend fun clear(protectedFiles: Set<File>): Boolean {
        files.retainAll(protectedFiles)
        return true
    }
}

private class BlockingPurgeIntroVideoCache : IntroVideoCache {
    val clearStarted = CompletableDeferred<Unit>()
    val releaseClear = CompletableDeferred<Unit>()
    val resolvedSources = mutableListOf<RemoteIntroVideo>()

    override suspend fun resolve(source: RemoteIntroVideo): File {
        resolvedSources += source
        return File("intro-${source.revision}.mp4")
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? = null

    override suspend fun clear(protectedFiles: Set<File>): Boolean {
        clearStarted.complete(Unit)
        releaseClear.await()
        return true
    }
}

private class BlockingFindIntroVideoCache : IntroVideoCache {
    val findStarted = CompletableDeferred<Unit>()
    val releaseFind = CompletableDeferred<Unit>()
    val clearProtectedFiles = mutableListOf<Set<File>>()

    override suspend fun resolve(source: RemoteIntroVideo): File? = null

    override suspend fun findCached(pending: PendingRemoteIntro): File {
        findStarted.complete(Unit)
        releaseFind.await()
        return File(pending.fileName)
    }

    override suspend fun clear(protectedFiles: Set<File>): Boolean {
        clearProtectedFiles += protectedFiles
        return true
    }
}

private class FailingFindIntroVideoCache : IntroVideoCache {
    var clearCalls = 0

    override suspend fun resolve(source: RemoteIntroVideo): File? = null

    override suspend fun findCached(pending: PendingRemoteIntro): File? {
        throw IOException("transient read failure")
    }

    override suspend fun clear(protectedFiles: Set<File>): Boolean {
        clearCalls += 1
        return true
    }
}

private class ThrowingOnceIntroVideoCache : IntroVideoCache {
    val resolvedSources = mutableListOf<RemoteIntroVideo>()
    private var shouldThrow = true

    override suspend fun resolve(source: RemoteIntroVideo): File {
        resolvedSources += source
        if (shouldThrow) {
            shouldThrow = false
            throw IOException("transient resolution failure")
        }
        return File(PENDING_REMOTE.fileName)
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? = null

    override suspend fun clear(protectedFiles: Set<File>): Boolean = true
}

private class NullThenSuccessfulIntroVideoCache : IntroVideoCache {
    val firstResolutionStarted = CompletableDeferred<Unit>()
    val releaseFirstResolution = CompletableDeferred<Unit>()
    val resolvedSources = mutableListOf<RemoteIntroVideo>()

    override suspend fun resolve(source: RemoteIntroVideo): File? {
        resolvedSources += source
        if (resolvedSources.size == 1) {
            firstResolutionStarted.complete(Unit)
            releaseFirstResolution.await()
            return null
        }
        return File("intro-${source.revision}.mp4")
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? = null

    override suspend fun clear(protectedFiles: Set<File>): Boolean = true
}

private class RecoveringPurgeIntroVideoCache(
    private var failedClearAttempts: Int,
) : IntroVideoCache {
    val resolvedSources = mutableListOf<RemoteIntroVideo>()

    override suspend fun resolve(source: RemoteIntroVideo): File {
        resolvedSources += source
        return File(PENDING_REMOTE.fileName)
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File? = null

    override suspend fun clear(protectedFiles: Set<File>): Boolean {
        if (failedClearAttempts <= 0) return true
        failedClearAttempts -= 1
        return false
    }
}

private class FailedPurgeIntroVideoCache : IntroVideoCache {
    val resolvedSources = mutableListOf<RemoteIntroVideo>()
    var cachedMediaWasRead = false

    override suspend fun resolve(source: RemoteIntroVideo): File? {
        resolvedSources += source
        return null
    }

    override suspend fun findCached(pending: PendingRemoteIntro): File {
        cachedMediaWasRead = true
        return File(pending.fileName)
    }

    override suspend fun clear(protectedFiles: Set<File>): Boolean = false
}

private class FakeFirstLaunchStore(
    isBundledIntroRequired: Boolean,
    var pending: PendingRemoteIntro? = null,
    private val pendingWritesSucceed: Boolean = true,
    private val pendingClearsSucceed: Boolean = true,
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

    override fun markRemoteIntroPending(intro: PendingRemoteIntro): Boolean {
        if (!pendingWritesSucceed) return false
        val pendingRevision = pending?.revision ?: 0L
        val shouldPersist = intro.revision > maxOf(lastPresentedRevision, pendingRevision)
        if (shouldPersist) {
            pending = intro
        }
        return shouldPersist
    }

    override fun markRemoteIntroPresented(revision: Long): Boolean {
        lastPresentedRevision = maxOf(lastPresentedRevision, revision)
        if (pending?.revision != null && requireNotNull(pending).revision <= revision) {
            pending = null
        }
        return true
    }

    override fun clearPendingRemoteIntro(): Boolean {
        if (!pendingClearsSucceed) return false
        pending = null
        return true
    }
}

private class FakeConsentStore(
    initialConsent: ObservabilityConsent,
    private var thrownAcknowledgementAttempts: Int = 0,
    private var failedAcknowledgementAttempts: Int = 0,
) : ObservabilityConsentStore {
    private var consent = initialConsent
    private var purgeState = RemoteMediaPurgeState()

    override fun read(): ObservabilityConsent = consent

    override fun write(consent: ObservabilityConsent): Boolean {
        this.consent = consent
        return true
    }

    override fun readRemoteMediaPurgeState(): RemoteMediaPurgeState = purgeState

    override fun writeRequiredRemoteMediaPurgeEpoch(epoch: RemoteMediaPurgeEpoch): Boolean {
        purgeState = purgeState.copy(required = epoch)
        return true
    }

    override fun writeAcknowledgedRemoteMediaPurgeEpoch(epoch: RemoteMediaPurgeEpoch): Boolean {
        if (thrownAcknowledgementAttempts > 0) {
            thrownAcknowledgementAttempts -= 1
            throw IOException("transient acknowledgement failure")
        }
        if (failedAcknowledgementAttempts > 0) {
            failedAcknowledgementAttempts -= 1
            return false
        }
        purgeState = purgeState.copy(acknowledged = epoch)
        return true
    }
}

private class FakeBackend(
    private var configuration: RemoteFeatureConfiguration,
    private var cachedConfiguration: RemoteFeatureConfiguration = configuration,
    private val diagnosticsThrow: Boolean = false,
) : AndroidObservabilityBackend {
    private var remoteUpdateCallback: ((RemoteFeatureConfiguration?) -> Unit)? = null
    override val isConfigured = true

    override fun applyConsent(consent: ObservabilityConsent) = Unit

    override fun track(event: AnalyticsEvent) = Unit

    override fun recordDiagnostic(code: DiagnosticCode) {
        if (diagnosticsThrow) error("diagnostics unavailable")
    }

    override fun startTrace(name: PerformanceTraceName): PerformanceTrace = PerformanceTrace.None

    override fun fetchRemoteConfiguration(onResult: (RemoteFeatureConfiguration?) -> Unit) {
        onResult(configuration)
    }

    override fun readCachedRemoteConfiguration(): RemoteFeatureConfiguration = cachedConfiguration

    override fun startRemoteConfigurationUpdates(onResult: (RemoteFeatureConfiguration?) -> Unit) {
        remoteUpdateCallback = onResult
    }

    override fun stopRemoteConfigurationUpdates() {
        remoteUpdateCallback = null
    }

    fun emit(configuration: RemoteFeatureConfiguration) {
        this.configuration = configuration
        cachedConfiguration = configuration
        remoteUpdateCallback?.invoke(configuration)
    }
}

private class FakeDispatcherProvider(
    dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val ALLOWED_CONSENT = ObservabilityConsent(remoteConfigurationAllowed = true)
private val DIAGNOSTICS_ALLOWED_CONSENT = ObservabilityConsent(
    diagnosticsAllowed = true,
    remoteConfigurationAllowed = true,
)
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
