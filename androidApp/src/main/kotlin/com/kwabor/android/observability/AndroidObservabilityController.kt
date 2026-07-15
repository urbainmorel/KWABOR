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
    private val mutableConsent = MutableStateFlow(ObservabilityConsent())

    val remoteConfiguration: StateFlow<RemoteFeatureConfiguration> = mutableRemoteConfiguration.asStateFlow()
    val consent: StateFlow<ObservabilityConsent> = mutableConsent.asStateFlow()
    val isConfigured: Boolean get() = backend.isConfigured

    fun start() {
        val storedConsent = consentStore.read()
        mutableConsent.value = storedConsent
        backend.applyConsent(storedConsent)
        if (storedConsent.remoteConfigurationAllowed) {
            backend.readCachedRemoteConfiguration()?.let { configuration ->
                mutableRemoteConfiguration.value = configuration
            }
            refreshRemoteConfiguration()
            backend.startRemoteConfigurationUpdates(::publishRemoteConfiguration)
        }
    }

    fun updateConsent(updatedConsent: ObservabilityConsent) {
        val previousConsent = mutableConsent.value
        mutableConsent.value = updatedConsent
        consentStore.write(updatedConsent)
        backend.applyConsent(updatedConsent)

        if (!updatedConsent.remoteConfigurationAllowed) {
            backend.stopRemoteConfigurationUpdates()
            mutableRemoteConfiguration.value = RemoteFeatureConfiguration.SafeDefaults
        } else if (!previousConsent.remoteConfigurationAllowed) {
            backend.readCachedRemoteConfiguration()?.let { configuration ->
                mutableRemoteConfiguration.value = configuration
            }
            refreshRemoteConfiguration()
            backend.startRemoteConfigurationUpdates(::publishRemoteConfiguration)
        }
    }

    fun track(event: AnalyticsEvent) {
        if (mutableConsent.value.analyticsAllowed) {
            backend.track(event)
        }
    }

    fun recordDiagnostic(code: DiagnosticCode) {
        if (mutableConsent.value.diagnosticsAllowed) {
            backend.recordDiagnostic(code)
        }
    }

    fun startTrace(name: PerformanceTraceName): PerformanceTrace {
        if (!mutableConsent.value.diagnosticsAllowed) {
            return PerformanceTrace.None
        }
        return backend.startTrace(name)
    }

    fun refreshRemoteConfiguration() {
        if (!mutableConsent.value.remoteConfigurationAllowed) {
            return
        }
        backend.fetchRemoteConfiguration { configuration ->
            publishRemoteConfiguration(configuration)
        }
    }

    fun close() {
        backend.stopRemoteConfigurationUpdates()
    }

    private fun publishRemoteConfiguration(configuration: RemoteFeatureConfiguration?) {
        if (!mutableConsent.value.remoteConfigurationAllowed) return
        if (configuration == null) {
            recordDiagnostic(DiagnosticCode.RemoteConfigurationFetchFailed)
            return
        }
        mutableRemoteConfiguration.value = configuration
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

    fun readCachedRemoteConfiguration(): RemoteFeatureConfiguration?

    fun startRemoteConfigurationUpdates(onResult: (RemoteFeatureConfiguration?) -> Unit)

    fun stopRemoteConfigurationUpdates()
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
