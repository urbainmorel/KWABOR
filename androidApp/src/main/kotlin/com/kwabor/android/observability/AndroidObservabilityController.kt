package com.kwabor.android.observability

import android.content.Context
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidObservabilityController internal constructor(
    private val backend: AndroidObservabilityBackend,
    private val consentStore: ObservabilityConsentStore,
) {
    private val stateLock = Any()
    private val mutableConsent = MutableStateFlow(ObservabilityConsent())
    private var remoteConfigurationGeneration = 0L
    private var hasStarted = false

    val consent: StateFlow<ObservabilityConsent> = mutableConsent.asStateFlow()
    val isConfigured: Boolean get() = backend.isConfigured

    fun start() {
        val storedConsent = synchronized(stateLock) {
            check(!hasStarted) { "The observability controller can only be started once." }
            hasStarted = true
            consentStore.read().also { consent -> mutableConsent.value = consent }
        }
        backend.applyConsent(storedConsent)
        if (storedConsent.remoteConfigurationAllowed) {
            startRemoteConfigurationSession()
        }
    }

    @Synchronized
    fun updateConsent(updatedConsent: ObservabilityConsent): Boolean {
        if (!consentStore.write(updatedConsent)) return false
        val previousConsent = synchronized(stateLock) {
            mutableConsent.value.also {
                mutableConsent.value = updatedConsent
                if (it.remoteConfigurationAllowed && !updatedConsent.remoteConfigurationAllowed) {
                    remoteConfigurationGeneration += 1
                }
            }
        }
        backend.applyConsent(updatedConsent)
        when {
            !updatedConsent.remoteConfigurationAllowed -> backend.stopRemoteConfigurationUpdates()
            !previousConsent.remoteConfigurationAllowed -> startRemoteConfigurationSession()
        }
        return true
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

    fun close() {
        synchronized(stateLock) {
            remoteConfigurationGeneration += 1
        }
        backend.stopRemoteConfigurationUpdates()
    }

    private fun startRemoteConfigurationSession() {
        val generation = synchronized(stateLock) {
            if (!mutableConsent.value.remoteConfigurationAllowed) return
            remoteConfigurationGeneration += 1
            remoteConfigurationGeneration
        }
        backend.fetchAndActivateRemoteConfiguration { succeeded ->
            handleRemoteConfigurationResult(succeeded = succeeded, generation = generation)
        }
        if (!isRemoteConfigurationGenerationActive(generation)) return
        backend.startRemoteConfigurationUpdates { succeeded ->
            handleRemoteConfigurationResult(succeeded = succeeded, generation = generation)
        }
        if (!isRemoteConfigurationGenerationActive(generation)) {
            backend.stopRemoteConfigurationUpdates()
        }
    }

    private fun handleRemoteConfigurationResult(succeeded: Boolean, generation: Long) {
        if (!isRemoteConfigurationGenerationActive(generation) || succeeded) return
        recordDiagnostic(DiagnosticCode.RemoteConfigurationFetchFailed)
    }

    private fun isRemoteConfigurationGenerationActive(generation: Long): Boolean = synchronized(stateLock) {
        mutableConsent.value.remoteConfigurationAllowed && generation == remoteConfigurationGeneration
    }
}

internal fun createAndroidObservabilityController(context: Context): AndroidObservabilityController =
    AndroidObservabilityController(
        backend = FirebaseAndroidObservabilityBackend.create(context.applicationContext),
        consentStore = SharedPreferencesObservabilityConsentStore(context.applicationContext),
    )

internal interface AndroidObservabilityBackend {
    val isConfigured: Boolean

    fun applyConsent(consent: ObservabilityConsent)

    fun track(event: AnalyticsEvent)

    fun recordDiagnostic(code: DiagnosticCode)

    fun startTrace(name: PerformanceTraceName): PerformanceTrace

    fun fetchAndActivateRemoteConfiguration(onResult: (Boolean) -> Unit)

    fun startRemoteConfigurationUpdates(onResult: (Boolean) -> Unit)

    fun stopRemoteConfigurationUpdates()
}

internal interface ObservabilityConsentStore {
    fun read(): ObservabilityConsent

    fun write(consent: ObservabilityConsent): Boolean
}

fun interface PerformanceTrace {
    fun stop()

    companion object {
        val None = PerformanceTrace {}
    }
}
