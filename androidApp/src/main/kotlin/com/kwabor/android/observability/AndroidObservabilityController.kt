package com.kwabor.android.observability

import android.content.Context
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName
import com.kwabor.shared.domain.observability.RemoteFeatureConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidObservabilityController internal constructor(
    private val backend: AndroidObservabilityBackend,
    private val consentStore: ObservabilityConsentStore,
) {
    private val mutableRemoteConfiguration = MutableStateFlow(RemoteFeatureConfiguration.SafeDefaults)

    val remoteConfiguration: StateFlow<RemoteFeatureConfiguration> = mutableRemoteConfiguration.asStateFlow()
    val isConfigured: Boolean get() = backend.isConfigured
    var consent: ObservabilityConsent = ObservabilityConsent()
        private set

    fun start() {
        consent = consentStore.read()
        backend.applyConsent(consent)
        if (consent.remoteConfigurationAllowed) {
            refreshRemoteConfiguration()
        }
    }

    fun updateConsent(updatedConsent: ObservabilityConsent) {
        val previousConsent = consent
        consent = updatedConsent
        consentStore.write(updatedConsent)
        backend.applyConsent(updatedConsent)

        if (!updatedConsent.remoteConfigurationAllowed) {
            mutableRemoteConfiguration.value = RemoteFeatureConfiguration.SafeDefaults
        } else if (!previousConsent.remoteConfigurationAllowed) {
            refreshRemoteConfiguration()
        }
    }

    fun track(event: AnalyticsEvent) {
        if (consent.analyticsAllowed) {
            backend.track(event)
        }
    }

    fun recordDiagnostic(code: DiagnosticCode) {
        if (consent.diagnosticsAllowed) {
            backend.recordDiagnostic(code)
        }
    }

    fun startTrace(name: PerformanceTraceName): PerformanceTrace {
        if (!consent.diagnosticsAllowed) {
            return PerformanceTrace.None
        }
        return backend.startTrace(name)
    }

    fun refreshRemoteConfiguration() {
        if (!consent.remoteConfigurationAllowed) {
            return
        }
        backend.fetchRemoteConfiguration { configuration ->
            if (configuration == null) {
                recordDiagnostic(DiagnosticCode.RemoteConfigurationFetchFailed)
                return@fetchRemoteConfiguration
            }
            mutableRemoteConfiguration.value = configuration
        }
    }

    companion object {
        fun create(context: Context): AndroidObservabilityController = AndroidObservabilityController(
            backend = FirebaseAndroidObservabilityBackend.create(context.applicationContext),
            consentStore = SharedPreferencesObservabilityConsentStore(context.applicationContext),
        )
    }
}

internal interface AndroidObservabilityBackend {
    val isConfigured: Boolean

    fun applyConsent(consent: ObservabilityConsent)

    fun track(event: AnalyticsEvent)

    fun recordDiagnostic(code: DiagnosticCode)

    fun startTrace(name: PerformanceTraceName): PerformanceTrace

    fun fetchRemoteConfiguration(onResult: (RemoteFeatureConfiguration?) -> Unit)
}

internal interface ObservabilityConsentStore {
    fun read(): ObservabilityConsent

    fun write(consent: ObservabilityConsent)
}

fun interface PerformanceTrace {
    fun stop()

    companion object {
        val None = PerformanceTrace {}
    }
}
