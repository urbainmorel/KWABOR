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
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidIntroMediaManagerTest {
    @Test
    fun consentedConfigurationPublishesVerifiedFileAndRevocationClearsIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val configuration = RemoteFeatureConfiguration(introVideo = REMOTE_VIDEO)
        val controller = AndroidObservabilityController(
            backend = FakeBackend(configuration),
            consentStore = FakeConsentStore(ALLOWED_CONSENT),
        )
        val cache = FakeIntroVideoCache()
        val manager = AndroidIntroMediaManager(
            observability = controller,
            cache = cache,
            dispatcherProvider = FakeDispatcherProvider(dispatcher),
        )

        controller.start()
        manager.start()
        advanceUntilIdle()

        assertSame(cache.file, manager.remoteVideoFile.value)
        assertEquals(listOf(REMOTE_VIDEO), cache.resolvedSources)

        controller.updateConsent(ObservabilityConsent())
        advanceUntilIdle()

        assertNull(manager.remoteVideoFile.value)
        assertEquals(1, cache.clearCount)
        manager.close()
    }
}

private class FakeIntroVideoCache : IntroVideoCache {
    val file = File("verified-intro.mp4")
    val resolvedSources = mutableListOf<RemoteIntroVideo>()
    var clearCount = 0

    override suspend fun resolve(source: RemoteIntroVideo): File {
        resolvedSources += source
        return file
    }

    override suspend fun clear() {
        clearCount += 1
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
