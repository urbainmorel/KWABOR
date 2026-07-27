package com.kwabor.android.presentation.auth

import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationResult

internal class PromoterActivationCoordinator(
    private val runtime: AuthViewModelRuntime,
    dependencies: AuthViewModelDependencies,
    retrySessionRestore: () -> Unit,
) {
    private val flow = PromoterActivationFlowState()
    private val cleanup = PromoterActivationCleanupCoordinator(runtime, dependencies, flow)
    private val link = PromoterActivationLinkCoordinator(
        runtime = runtime,
        dependencies = dependencies,
        flow = flow,
        cleanup = cleanup,
        retrySessionRestore = retrySessionRestore,
    )
    private val activation = PromoterActivationActionCoordinator(runtime, dependencies, flow, cleanup)

    fun clearSensitiveState() {
        runtime.promoterActivationJob?.cancel()
        runtime.promoterActivationJob = null
        flow.clear()
    }

    fun blocks(intent: AuthIntent): Boolean = flow.exclusive && intent !is AuthIntent.PromoterActivation

    fun handle(intent: AuthIntent.PromoterActivation) {
        when (intent) {
            is AuthIntent.OpenPromoterActivation -> link.open(intent.callbackUrl)
            AuthIntent.RetryPromoterActivationLink -> retry()
            is AuthIntent.ActivatePromoterWithPassword -> activation.activateWithPassword(intent.password)
            AuthIntent.ActivatePromoterWithGoogle -> activation.activateWithGoogle()
            AuthIntent.CancelPromoterActivation -> activation.cancel()
            AuthIntent.FinishPromoterActivation -> activation.finish()
        }
    }

    fun resumePendingCallbackAfterSessionRestoreRetry() {
        if (flow.pendingCallbackUrl != null) link.retry()
    }

    private fun retry() {
        when {
            cleanup.isRequired -> cleanup.retry()
            flow.pendingSuccessfulActivation != null -> activation.retrySuccessfulActivation()
            else -> link.retry()
        }
    }
}

internal class PromoterActivationFlowState {
    var pendingCallbackUrl: String? = null
    var activationContext: PromoterActivationContext? = null
    var postAuthDestination: PromoterPostAuthDestination? = null
    var pendingSuccessfulActivation: PendingSuccessfulActivation? = null
    var provisionalSessionMarkerPending: Boolean = false
    var exclusive: Boolean = false

    fun clear() {
        pendingCallbackUrl = null
        activationContext = null
        postAuthDestination = null
        pendingSuccessfulActivation = null
        provisionalSessionMarkerPending = false
        exclusive = false
    }
}

internal data class PromoterPostAuthDestination(
    val organizationId: String,
    val listingId: String,
)

internal class PendingSuccessfulActivation(
    val context: PromoterActivationContext,
    val activation: PromoterActivationResult,
) {
    override fun toString(): String = "PendingSuccessfulActivation(<redacted>)"
}
