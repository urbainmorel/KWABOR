package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.AnalyticsEventName
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidObservabilityControllerTest {
    @Test
    fun startKeepsAllCollectionAndRemoteConfigurationDisabledWithoutStoredConsent() {
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
    fun updateConsentPersistsChoiceAndGatesEveryBackendCapability() {
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
        assertTrue(backend.remoteConfigurationFetched)
        assertEquals(1, backend.remoteUpdateStartCount)
    }

    @Test
    fun updateConsentDoesNotApplyChoiceWhenDurableWriteFails() {
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
    fun revokingConsentStopsRemoteUpdatesAndFurtherCollection() {
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

        assertEquals(emptyList(), backend.events)
        assertEquals(emptyList(), backend.diagnostics)
        assertEquals(1, backend.remoteUpdateStopCount)
    }

    @Test
    fun failedGenericRemoteConfigurationFetchIsReportedOnlyWithDiagnosticsConsent() {
        val backend = FakeObservabilityBackend(fetchSucceeds = false)
        val controller = AndroidObservabilityController(
            backend,
            InMemoryConsentStore(
                ObservabilityConsent(
                    diagnosticsAllowed = true,
                    remoteConfigurationAllowed = true,
                ),
            ),
        )

        controller.start()

        assertEquals(listOf(DiagnosticCode.RemoteConfigurationFetchFailed), backend.diagnostics)
    }

    @Test
    fun staleRemoteConfigurationCallbackCannotReportAfterConsentRevocation() {
        val backend = FakeObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            InMemoryConsentStore(
                ObservabilityConsent(
                    diagnosticsAllowed = true,
                    remoteConfigurationAllowed = true,
                ),
            ),
        )
        controller.start()

        controller.updateConsent(ObservabilityConsent(diagnosticsAllowed = true))
        backend.emitStaleRemoteUpdate(succeeded = false)

        assertEquals(emptyList(), backend.diagnostics)
    }

    @Test
    fun genericRealtimeRemoteConfigurationFailureIsReportedWhileConsentRemainsGranted() {
        val backend = FakeObservabilityBackend()
        val controller = AndroidObservabilityController(
            backend,
            InMemoryConsentStore(
                ObservabilityConsent(
                    diagnosticsAllowed = true,
                    remoteConfigurationAllowed = true,
                ),
            ),
        )
        controller.start()

        backend.emitRemoteUpdate(succeeded = false)

        assertEquals(listOf(DiagnosticCode.RemoteConfigurationFetchFailed), backend.diagnostics)
    }

    @Test
    fun closeRemovesTheGenericRealtimeRemoteConfigurationListener() {
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

private class FakeObservabilityBackend(
    private val fetchSucceeds: Boolean = true,
) : AndroidObservabilityBackend {
    override val isConfigured: Boolean = true
    var appliedConsent = ObservabilityConsent()
    val events = mutableListOf<AnalyticsEvent>()
    val diagnostics = mutableListOf<DiagnosticCode>()
    val traces = mutableListOf<PerformanceTraceName>()
    var remoteConfigurationFetched = false
    var remoteUpdateStartCount = 0
    var remoteUpdateStopCount = 0
    var remoteUpdateCallback: ((Boolean) -> Unit)? = null
    var staleRemoteUpdateCallback: ((Boolean) -> Unit)? = null
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

    fun emitRemoteUpdate(succeeded: Boolean) {
        remoteUpdateCallback?.invoke(succeeded)
    }

    fun emitStaleRemoteUpdate(succeeded: Boolean) {
        staleRemoteUpdateCallback?.invoke(succeeded)
    }
}
