package com.kwabor.android.observability

import com.kwabor.shared.domain.observability.ObservabilityConsent

internal class AndroidRemoteConfigurationCoordinator(
    private val backend: AndroidRemoteConfigurationBackend,
    private val stateLock: AndroidObservabilityStateLock,
    private val isRemoteConfigurationAllowed: () -> Boolean,
    private val reportFailure: () -> Unit,
) {
    private var generation = 0L

    fun transition(previousConsent: ObservabilityConsent, updatedConsent: ObservabilityConsent) {
        when {
            previousConsent.remoteConfigurationAllowed && !updatedConsent.remoteConfigurationAllowed -> stop()
            updatedConsent.remoteConfigurationAllowed && !previousConsent.remoteConfigurationAllowed -> start()
        }
    }

    fun close() {
        stop()
    }

    private fun start() {
        if (!isRemoteConfigurationAllowed()) return
        generation += 1
        val activeGeneration = generation
        backend.fetchAndActivateRemoteConfiguration { succeeded ->
            stateLock.hold { handleResult(succeeded, activeGeneration) }
        }
        if (!isActive(activeGeneration)) return
        backend.startRemoteConfigurationUpdates { succeeded ->
            stateLock.hold { handleResult(succeeded, activeGeneration) }
        }
        if (!isActive(activeGeneration)) backend.stopRemoteConfigurationUpdates()
    }

    private fun stop() {
        generation += 1
        backend.stopRemoteConfigurationUpdates()
    }

    private fun handleResult(succeeded: Boolean, callbackGeneration: Long) {
        if (!succeeded && isActive(callbackGeneration)) reportFailure()
    }

    private fun isActive(callbackGeneration: Long): Boolean =
        isRemoteConfigurationAllowed() && callbackGeneration == generation
}
