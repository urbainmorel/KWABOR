package com.kwabor.android.onboarding

import com.kwabor.android.observability.AndroidObservabilityController
import com.kwabor.shared.domain.core.DispatcherProvider
import com.kwabor.shared.domain.observability.DiagnosticCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class AndroidIntroMediaManager internal constructor(
    private val observability: AndroidObservabilityController,
    private val cache: IntroVideoCache,
    dispatcherProvider: DispatcherProvider,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    private val mutableRemoteVideoFile = MutableStateFlow<File?>(null)

    val remoteVideoFile: StateFlow<File?> = mutableRemoteVideoFile.asStateFlow()

    fun start() {
        coroutineScope.launch {
            combine(observability.consent, observability.remoteConfiguration) { consent, configuration ->
                configuration.introVideo.takeIf { consent.remoteConfigurationAllowed }
            }.collectLatest { source ->
                if (source == null) {
                    cache.clear()
                    mutableRemoteVideoFile.value = null
                    return@collectLatest
                }
                val file = runCatching { cache.resolve(source) }.getOrNull()
                if (file == null) {
                    observability.recordDiagnostic(DiagnosticCode.IntroVideoIntegrityFailed)
                }
                mutableRemoteVideoFile.value = file
            }
        }
    }

    fun close() {
        coroutineScope.cancel()
    }
}
