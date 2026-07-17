package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName
import com.kwabor.shared.domain.observability.RemoteFeatureConfiguration
import com.kwabor.shared.domain.observability.RemoteIntroVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidObservabilityControllerTest {
    @Test
    fun realtimeListenerOnlyAcceptsIntroVideoKeys() {
        assertFalse(setOf("unrelated_feature").containsIntroVideoRemoteKey())
        assertEquals(true, setOf("intro_video_revision").containsIntroVideoRemoteKey())
    }

    @Test
    fun start_keepsAllCollectionDisabledWithoutStoredConsent() {
        val backend = FakeObservabilityBackend()
        val controller = AndroidObservabilityController(backend, InMemoryConsentStore())

        controller.start()
        controller.track(AnalyticsEvent(AnalyticsEventName.ViewCard))
        controller.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)

        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertEquals(emptyList(), backend.events)
        assertEquals(emptyList(), backend.diagnostics)
        assertFalse(backend.remoteConfigurationFetched)
        assertFalse(backend.remoteUpdatesStarted)
    }

    @Test
    fun updateConsent_persistsChoiceAndGatesEveryBackendCapability() {
        val backend = FakeObservabilityBackend()
        val store = InMemoryConsentStore()
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        val granted = ObservabilityConsent(
            analyticsAllowed = true,
            diagnosticsAllowed = true,
            remoteConfigurationAllowed = true,
        )

        assertTrue(controller.updateConsent(granted))
        controller.track(AnalyticsEvent(AnalyticsEventName.ViewCard))
        controller.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)
        controller.startTrace(PerformanceTraceName.ExploreInitialLoad).stop()

        assertEquals(granted, store.consent)
        assertEquals(1, backend.events.size)
        assertEquals(listOf(DiagnosticCode.UnexpectedApplicationState), backend.diagnostics)
        assertEquals(listOf(PerformanceTraceName.ExploreInitialLoad), backend.traces)
        assertEquals(REMOTE_CONFIGURATION, controller.remoteConfiguration.value)
        assertEquals(1, backend.remoteUpdateStartCount)
    }

    @Test
    fun updateConsentDoesNotApplyOrPublishChoiceWhenDurableWriteFails() {
        val backend = FakeObservabilityBackend()
        val store = InMemoryConsentStore(writesSucceed = false)
        val controller = AndroidObservabilityController(backend, store)
        controller.start()
        val granted = ObservabilityConsent(analyticsAllowed = true)

        assertFalse(controller.updateConsent(granted))

        assertEquals(ObservabilityConsent(), controller.consent.value)
        assertEquals(ObservabilityConsent(), backend.appliedConsent)
        assertEquals(ObservabilityConsent(), store.consent)
    }

    @Test
    fun revokingConsent_revertsToSafeConfigAndStopsFurtherCollection() {
        val backend = FakeObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            InMemoryConsentStore(
                ObservabilityConsent(
                    analyticsAllowed = true,
                    diagnosticsAllowed = true,
                    remoteConfigurationAllowed = true,
                ),
            ),
        )
        controller.start()

        controller.updateConsent(ObservabilityConsent())
        controller.track(AnalyticsEvent(AnalyticsEventName.ViewCard))
        controller.recordDiagnostic(DiagnosticCode.UnexpectedApplicationState)

        assertSame(RemoteFeatureConfiguration.SafeDefaults, controller.remoteConfiguration.value)
        assertEquals(emptyList(), backend.events)
        assertEquals(emptyList(), backend.diagnostics)
        assertEquals(1, backend.remoteUpdateStopCount)
    }

    @Test
    fun realtimeRemoteConfigUpdateIsPublishedOnlyWhileConsentRemainsGranted() {
        val backend = FakeObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            InMemoryConsentStore(ObservabilityConsent(remoteConfigurationAllowed = true)),
        )
        controller.start()

        backend.emitRemoteUpdate(NEWER_REMOTE_CONFIGURATION)

        assertEquals(NEWER_REMOTE_CONFIGURATION, controller.remoteConfiguration.value)

        controller.updateConsent(ObservabilityConsent())
        backend.emitRemoteUpdate(REMOTE_CONFIGURATION)

        assertSame(RemoteFeatureConfiguration.SafeDefaults, controller.remoteConfiguration.value)
        assertEquals(1, backend.remoteUpdateStopCount)
    }

    @Test
    fun closeRemovesRealtimeRemoteConfigListener() {
        val backend = FakeObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            InMemoryConsentStore(ObservabilityConsent(remoteConfigurationAllowed = true)),
        )
        controller.start()

        controller.close()

        assertEquals(1, backend.remoteUpdateStopCount)
    }
}

private class InMemoryConsentStore(
    var consent: ObservabilityConsent = ObservabilityConsent(),
    private val writesSucceed: Boolean = true,
) : ObservabilityConsentStore {
    override fun read(): ObservabilityConsent = consent

    override fun write(consent: ObservabilityConsent): Boolean {
        if (writesSucceed) this.consent = consent
        return writesSucceed
    }
}

private class FakeObservabilityBackend : AndroidObservabilityBackend {
    override val isConfigured: Boolean = true
    var appliedConsent = ObservabilityConsent()
    val events = mutableListOf<AnalyticsEvent>()
    val diagnostics = mutableListOf<DiagnosticCode>()
    val traces = mutableListOf<PerformanceTraceName>()
    var remoteConfigurationFetched = false
    var remoteUpdateStartCount = 0
    var remoteUpdateStopCount = 0
    var remoteUpdateCallback: ((RemoteFeatureConfiguration?) -> Unit)? = null
    val remoteUpdatesStarted: Boolean get() = remoteUpdateCallback != null

    override fun applyConsent(consent: ObservabilityConsent) {
        appliedConsent = consent
        if (!consent.analyticsAllowed) {
            events.clear()
        }
        if (!consent.diagnosticsAllowed) {
            diagnostics.clear()
            traces.clear()
        }
    }

    override fun track(event: AnalyticsEvent) {
        events += event
    }

    override fun recordDiagnostic(code: DiagnosticCode) {
        diagnostics += code
    }

    override fun startTrace(name: PerformanceTraceName): PerformanceTrace {
        traces += name
        return PerformanceTrace.None
    }

    override fun fetchRemoteConfiguration(onResult: (RemoteFeatureConfiguration?) -> Unit) {
        remoteConfigurationFetched = true
        onResult(REMOTE_CONFIGURATION)
    }

    override fun readCachedRemoteConfiguration(): RemoteFeatureConfiguration? = null

    override fun startRemoteConfigurationUpdates(onResult: (RemoteFeatureConfiguration?) -> Unit) {
        remoteUpdateStartCount += 1
        remoteUpdateCallback = onResult
    }

    override fun stopRemoteConfigurationUpdates() {
        remoteUpdateStopCount += 1
        remoteUpdateCallback = null
    }

    fun emitRemoteUpdate(configuration: RemoteFeatureConfiguration?) {
        remoteUpdateCallback?.invoke(configuration)
    }
}

private val REMOTE_CONFIGURATION = RemoteFeatureConfiguration(
    introVideo = RemoteIntroVideo(
        url = "https://cdn.kwabor.example/intro.mp4",
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        revision = 1,
    ),
)
private val NEWER_REMOTE_CONFIGURATION = RemoteFeatureConfiguration(
    introVideo = RemoteIntroVideo(
        url = "https://cdn.kwabor.example/intro-v2.mp4",
        sha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
        revision = 2,
    ),
)
