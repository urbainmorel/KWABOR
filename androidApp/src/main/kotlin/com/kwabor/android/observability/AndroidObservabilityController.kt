package com.kwabor.android.observability

import android.content.Context
import com.kwabor.shared.domain.observability.AnalyticsEvent
import com.kwabor.shared.domain.observability.DiagnosticCode
import com.kwabor.shared.domain.observability.ObservabilityConsent
import com.kwabor.shared.domain.observability.PerformanceTraceName
import com.kwabor.shared.domain.observability.RemoteFeatureConfiguration
import com.kwabor.shared.domain.observability.RemoteIntroVideoStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface AndroidRemoteMediaEvent {
    data class ConsentChanged(
        val sequence: Long,
        val remoteConfigurationAllowed: Boolean,
        val purgeEpoch: RemoteMediaPurgeEpoch,
    ) : AndroidRemoteMediaEvent

    data class ConfigurationChanged(
        val sequence: Long,
        val configuration: RemoteFeatureConfiguration,
        val purgeEpoch: RemoteMediaPurgeEpoch,
    ) : AndroidRemoteMediaEvent

    data class Snapshot(
        val consentSequence: Long,
        val remoteConfigurationAllowed: Boolean,
        val configurationSequence: Long,
        val configuration: RemoteFeatureConfiguration,
        val purgeEpoch: RemoteMediaPurgeEpoch,
        val acknowledgedPurgeEpoch: RemoteMediaPurgeEpoch,
    ) : AndroidRemoteMediaEvent
}

internal class AndroidRemoteMediaSubscription(
    val events: ReceiveChannel<AndroidRemoteMediaEvent>,
    private val closeSubscription: () -> Unit,
) {
    fun close() = closeSubscription()
}

private class RemoteMediaPurgePersistence(
    private val consentStore: ObservabilityConsentStore,
) {
    private val persistedState = consentStore.readRemoteMediaPurgeState()
    private var requiredPersistencePending = false

    val requiredPurgeEpoch = MutableStateFlow(persistedState.required)
    var acknowledgedPurgeEpoch = persistedState.acknowledged
        private set

    fun require(required: RemoteMediaPurgeEpoch) {
        requiredPersistencePending = !consentStore.writeRequiredRemoteMediaPurgeEpoch(required)
        requiredPurgeEpoch.value = required
    }

    fun ensureRequiredIsPersisted(): Boolean {
        if (!requiredPersistencePending) return true
        val persisted = consentStore.writeRequiredRemoteMediaPurgeEpoch(requiredPurgeEpoch.value)
        requiredPersistencePending = !persisted
        return persisted
    }

    fun acknowledge(purgeEpoch: RemoteMediaPurgeEpoch): Boolean {
        val acknowledged = acknowledgedPurgeEpoch.merge(purgeEpoch)
        if (!consentStore.writeAcknowledgedRemoteMediaPurgeEpoch(acknowledged)) return false
        acknowledgedPurgeEpoch = acknowledged
        return true
    }
}

class AndroidObservabilityController internal constructor(
    private val backend: AndroidObservabilityBackend,
    private val consentStore: ObservabilityConsentStore,
) {
    private val remoteMediaEventLock = Any()
    private val remoteMediaPurgePersistence = RemoteMediaPurgePersistence(consentStore)
    private val mutableRemoteConfiguration = MutableStateFlow(RemoteFeatureConfiguration.SafeDefaults)
    private val mutableConsent = MutableStateFlow(ObservabilityConsent())
    private val mutableRemoteMediaPurgeEpoch = remoteMediaPurgePersistence.requiredPurgeEpoch
    private val remoteMediaSubscribers = mutableSetOf<Channel<AndroidRemoteMediaEvent>>()
    private var consentSequence = 0L
    private var configurationSequence = 0L
    private var remoteConfigurationGeneration = 0L
    private var hasStarted = false

    val remoteConfiguration: StateFlow<RemoteFeatureConfiguration> = mutableRemoteConfiguration.asStateFlow()
    val consent: StateFlow<ObservabilityConsent> = mutableConsent.asStateFlow()
    internal val remoteMediaPurgeEpoch: StateFlow<RemoteMediaPurgeEpoch> = mutableRemoteMediaPurgeEpoch.asStateFlow()
    val isConfigured: Boolean get() = backend.isConfigured

    fun start() {
        synchronized(remoteMediaEventLock) {
            check(!hasStarted) { "The observability controller can only be started once." }
            hasStarted = true
        }
        val storedConsent = consentStore.read()
        synchronized(remoteMediaEventLock) {
            mutableConsent.value = storedConsent
            consentSequence += 1
            enqueueRemoteMediaEvent(
                AndroidRemoteMediaEvent.ConsentChanged(
                    sequence = consentSequence,
                    remoteConfigurationAllowed = storedConsent.remoteConfigurationAllowed,
                    purgeEpoch = mutableRemoteMediaPurgeEpoch.value,
                ),
            )
        }
        backend.applyConsent(storedConsent)
        if (storedConsent.remoteConfigurationAllowed) {
            startRemoteConfigurationSession()
        }
    }

    @Synchronized
    fun updateConsent(updatedConsent: ObservabilityConsent): Boolean {
        val remoteConfigurationIsBeingReenabled = synchronized(remoteMediaEventLock) {
            !mutableConsent.value.remoteConfigurationAllowed && updatedConsent.remoteConfigurationAllowed
        }
        if (
            remoteConfigurationIsBeingReenabled &&
            !synchronized(remoteMediaEventLock) { remoteMediaPurgePersistence.ensureRequiredIsPersisted() }
        ) {
            return false
        }
        if (!consentStore.write(updatedConsent)) return false
        val previousConsent = synchronized(remoteMediaEventLock) {
            applyConsentStateTransitionLocked(updatedConsent)
        }
        backend.applyConsent(updatedConsent)

        if (!updatedConsent.remoteConfigurationAllowed) {
            backend.stopRemoteConfigurationUpdates()
            synchronized(remoteMediaEventLock) {
                setRemoteConfigurationLocked(
                    configuration = RemoteFeatureConfiguration.SafeDefaults,
                    publishUnchanged = false,
                )
            }
        } else if (!previousConsent.remoteConfigurationAllowed) {
            startRemoteConfigurationSession()
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
        val subscribers = synchronized(remoteMediaEventLock) {
            remoteConfigurationGeneration += 1
            remoteMediaSubscribers.toList().also { remoteMediaSubscribers.clear() }
        }
        subscribers.forEach { it.close() }
        backend.stopRemoteConfigurationUpdates()
    }

    internal fun openRemoteMediaSubscription(): AndroidRemoteMediaSubscription {
        val channel = Channel<AndroidRemoteMediaEvent>(capacity = Channel.UNLIMITED)
        synchronized(remoteMediaEventLock) {
            check(
                channel.trySend(
                    AndroidRemoteMediaEvent.Snapshot(
                        consentSequence = consentSequence,
                        remoteConfigurationAllowed = mutableConsent.value.remoteConfigurationAllowed,
                        configurationSequence = configurationSequence,
                        configuration = mutableRemoteConfiguration.value,
                        purgeEpoch = mutableRemoteMediaPurgeEpoch.value,
                        acknowledgedPurgeEpoch = remoteMediaPurgePersistence.acknowledgedPurgeEpoch,
                    ),
                ).isSuccess,
            ) { "The remote media snapshot channel must remain available." }
            remoteMediaSubscribers += channel
        }
        return AndroidRemoteMediaSubscription(events = channel) {
            synchronized(remoteMediaEventLock) {
                remoteMediaSubscribers.remove(channel)
                channel.close()
            }
        }
    }

    internal fun acknowledgeRemoteMediaPurge(purgeEpoch: RemoteMediaPurgeEpoch): Boolean =
        synchronized(remoteMediaEventLock) {
            remoteMediaPurgePersistence.acknowledge(purgeEpoch)
        }

    private fun applyConsentStateTransitionLocked(updatedConsent: ObservabilityConsent): ObservabilityConsent {
        val previousConsent = mutableConsent.value
        mutableConsent.value = updatedConsent
        if (previousConsent.remoteConfigurationAllowed && !updatedConsent.remoteConfigurationAllowed) {
            remoteMediaPurgePersistence.require(
                mutableRemoteMediaPurgeEpoch.value.withConsentRevocation(),
            )
            remoteConfigurationGeneration += 1
        }
        if (previousConsent.remoteConfigurationAllowed != updatedConsent.remoteConfigurationAllowed) {
            consentSequence += 1
            enqueueRemoteMediaEvent(
                AndroidRemoteMediaEvent.ConsentChanged(
                    sequence = consentSequence,
                    remoteConfigurationAllowed = updatedConsent.remoteConfigurationAllowed,
                    purgeEpoch = mutableRemoteMediaPurgeEpoch.value,
                ),
            )
        }
        return previousConsent
    }

    private fun startRemoteConfigurationSession() {
        val generation = synchronized(remoteMediaEventLock) {
            if (!mutableConsent.value.remoteConfigurationAllowed) return
            remoteConfigurationGeneration += 1
            remoteConfigurationGeneration
        }
        backend.readCachedRemoteConfiguration()?.let { configuration ->
            publishRemoteConfiguration(configuration, generation)
        }
        if (!isRemoteConfigurationGenerationActive(generation)) return
        backend.fetchRemoteConfiguration { configuration ->
            publishRemoteConfiguration(configuration, generation)
        }
        if (!isRemoteConfigurationGenerationActive(generation)) return
        backend.startRemoteConfigurationUpdates { configuration ->
            publishRemoteConfiguration(configuration, generation)
        }
        if (!isRemoteConfigurationGenerationActive(generation)) {
            backend.stopRemoteConfigurationUpdates()
        }
    }

    private fun publishRemoteConfiguration(configuration: RemoteFeatureConfiguration?, generation: Long) {
        if (!isRemoteConfigurationGenerationActive(generation)) return
        if (configuration == null) {
            recordDiagnostic(DiagnosticCode.RemoteConfigurationFetchFailed)
            return
        }
        synchronized(remoteMediaEventLock) {
            if (
                !mutableConsent.value.remoteConfigurationAllowed ||
                generation != remoteConfigurationGeneration
            ) {
                return
            }
            setRemoteConfigurationLocked(configuration = configuration, publishUnchanged = true)
        }
    }

    private fun setRemoteConfigurationLocked(configuration: RemoteFeatureConfiguration, publishUnchanged: Boolean) {
        val previousConfiguration = mutableRemoteConfiguration.value
        if (
            configuration.introVideoStatus != RemoteIntroVideoStatus.Disabled &&
            !remoteMediaPurgePersistence.ensureRequiredIsPersisted()
        ) {
            return
        }
        if (!publishUnchanged && configuration == previousConfiguration) return
        mutableRemoteConfiguration.value = configuration
        if (
            configuration.introVideoStatus == RemoteIntroVideoStatus.Disabled &&
            previousConfiguration.introVideoStatus != RemoteIntroVideoStatus.Disabled
        ) {
            remoteMediaPurgePersistence.require(
                mutableRemoteMediaPurgeEpoch.value.withExplicitDisable(),
            )
        }
        configurationSequence += 1
        enqueueRemoteMediaEvent(
            AndroidRemoteMediaEvent.ConfigurationChanged(
                sequence = configurationSequence,
                configuration = configuration,
                purgeEpoch = mutableRemoteMediaPurgeEpoch.value,
            ),
        )
    }

    private fun isRemoteConfigurationGenerationActive(generation: Long): Boolean = synchronized(remoteMediaEventLock) {
        mutableConsent.value.remoteConfigurationAllowed && generation == remoteConfigurationGeneration
    }

    private fun enqueueRemoteMediaEvent(event: AndroidRemoteMediaEvent) {
        remoteMediaSubscribers.forEach { subscriber ->
            check(subscriber.trySend(event).isSuccess) {
                "The remote media event subscriber must remain available."
            }
        }
    }
}

internal fun createAndroidObservabilityController(context: Context): AndroidObservabilityController =
    AndroidObservabilityController(
        backend = FirebaseAndroidObservabilityBackend.create(context.applicationContext),
        consentStore = SharedPreferencesObservabilityConsentStore(context.applicationContext),
    )

internal data class RemoteMediaPurgeEpoch(
    val consentRevocations: Long = 0,
    val explicitDisables: Long = 0,
) {
    fun withConsentRevocation(): RemoteMediaPurgeEpoch = copy(consentRevocations = consentRevocations + 1)

    fun withExplicitDisable(): RemoteMediaPurgeEpoch = copy(explicitDisables = explicitDisables + 1)

    fun merge(other: RemoteMediaPurgeEpoch): RemoteMediaPurgeEpoch = RemoteMediaPurgeEpoch(
        consentRevocations = maxOf(consentRevocations, other.consentRevocations),
        explicitDisables = maxOf(explicitDisables, other.explicitDisables),
    )
}

internal data class RemoteMediaPurgeState(
    val required: RemoteMediaPurgeEpoch = RemoteMediaPurgeEpoch(),
    val acknowledged: RemoteMediaPurgeEpoch = RemoteMediaPurgeEpoch(),
)

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

    fun write(consent: ObservabilityConsent): Boolean

    fun readRemoteMediaPurgeState(): RemoteMediaPurgeState

    fun writeRequiredRemoteMediaPurgeEpoch(epoch: RemoteMediaPurgeEpoch): Boolean

    fun writeAcknowledgedRemoteMediaPurgeEpoch(epoch: RemoteMediaPurgeEpoch): Boolean
}

fun interface PerformanceTrace {
    fun stop()

    companion object {
        val None = PerformanceTrace {}
    }
}
