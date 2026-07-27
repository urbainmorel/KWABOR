package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.GoogleIdentityResult
import com.kwabor.shared.domain.auth.PromoterActivationContext
import com.kwabor.shared.domain.auth.PromoterActivationRequest
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import kotlinx.coroutines.launch

internal class PromoterActivationActionCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
    private val flow: PromoterActivationFlowState,
    private val cleanup: PromoterActivationCleanupCoordinator,
) {
    fun activateWithPassword(password: String) {
        val context = readyContext() ?: return
        if (password.length < MINIMUM_PROMOTER_PASSWORD_LENGTH) {
            publishActivationError(runtime.strings.authPasswordTooWeak)
            return
        }
        activate(
            context = context,
            request = PromoterActivationRequest(
                inviteToken = context.inviteToken,
                password = password,
                socialSignInRequest = null,
            ),
        )
    }

    fun activateWithGoogle() {
        val context = readyContext() ?: return
        publishActivating()
        runtime.promoterActivationJob?.cancel()
        runtime.promoterActivationJob = runtime.coroutineScope.launch {
            when (val credential = dependencies.googleIdentityProvider.acquireIdToken()) {
                GoogleIdentityResult.Cancelled -> publishReady()
                GoogleIdentityResult.Unavailable -> publishActivationError(
                    dependencies.googleIdentityUnavailableMessage,
                )
                is GoogleIdentityResult.Success -> activateWithSocialCredential(context, credential)
            }
        }
    }

    fun retrySuccessfulActivation() {
        val pendingActivation = flow.pendingSuccessfulActivation ?: return
        finalizeSuccessfulActivation(pendingActivation)
    }

    fun finish() {
        if (runtime.promoterActivationState.value.stage != PromoterActivationStage.Completed) return
        val destination = flow.postAuthDestination ?: return
        closePromoterFlow(runtime, flow)
        runtime.coroutineScope.launch {
            runtime.effectChannel.send(
                AuthEffect.PromoterActivationCompleted(
                    organizationId = destination.organizationId,
                    listingId = destination.listingId,
                ),
            )
        }
    }

    fun cancel() {
        val stage = runtime.promoterActivationState.value.stage
        when {
            stage.isPromoterOperationRunning() -> Unit
            flow.pendingSuccessfulActivation != null -> retrySuccessfulActivation()
            cleanup.isRequired -> cleanup.retry()
            flow.provisionalSessionMarkerPending -> cleanup.begin(closeAfterCleanup = true)
            flow.activationContext?.sessionImportedForActivation == true ->
                cleanup.begin(closeAfterCleanup = true)
            else -> closePromoterFlow(runtime, flow)
        }
    }

    private fun readyContext(): PromoterActivationContext? {
        if (runtime.promoterActivationState.value.stage != PromoterActivationStage.Ready) return null
        return flow.activationContext
    }

    private suspend fun activateWithSocialCredential(
        context: PromoterActivationContext,
        credential: GoogleIdentityResult.Success,
    ) {
        performActivation(
            context = context,
            request = PromoterActivationRequest(
                inviteToken = context.inviteToken,
                password = null,
                socialSignInRequest = SocialSignInRequest(
                    provider = SocialAuthProvider.Google,
                    idToken = credential.idToken,
                    rawNonce = credential.nonce,
                    suggestedFirstName = credential.profileHint.firstName,
                    suggestedLastName = credential.profileHint.lastName,
                ),
            ),
        )
    }

    private fun activate(context: PromoterActivationContext, request: PromoterActivationRequest) {
        publishActivating()
        runtime.promoterActivationJob?.cancel()
        runtime.promoterActivationJob = runtime.coroutineScope.launch {
            performActivation(context, request)
        }
    }

    private suspend fun performActivation(context: PromoterActivationContext, request: PromoterActivationRequest) {
        val result = dependencies.authPresenter.activatePromoterInvite(request, runtime.strings)
        val activation = result.value
        if (activation == null) {
            publishActivationError(result.errorMessage ?: runtime.strings.authInvalidInput)
            return
        }
        val pendingActivation = PendingSuccessfulActivation(context, activation)
        flow.pendingSuccessfulActivation = pendingActivation
        finalizeSuccessfulActivation(pendingActivation)
    }

    private fun finalizeSuccessfulActivation(pendingActivation: PendingSuccessfulActivation) {
        if (
            pendingActivation.context.sessionImportedForActivation &&
            !dependencies.promoterActivationSessionStore.clear()
        ) {
            runtime.promoterActivationState.value = runtime.promoterActivationState.value.copy(
                stage = PromoterActivationStage.Error,
                errorMessage = runtime.strings.authFederatedUnavailable,
                retryAvailable = true,
            )
            return
        }
        flow.pendingSuccessfulActivation = null
        flow.activationContext = null
        runtime.authState.value = runtime.authState.value.copy(
            currentSession = pendingActivation.activation.session,
            errorMessage = null,
            noticeMessage = null,
        )
        flow.postAuthDestination = PromoterPostAuthDestination(
            organizationId = pendingActivation.activation.organizationId,
            listingId = pendingActivation.activation.listingId,
        )
        runtime.promoterActivationState.value = PromoterActivationUiState(
            stage = PromoterActivationStage.Completed,
            businessName = pendingActivation.context.businessName,
        )
    }

    private fun publishActivating() {
        runtime.promoterActivationState.value = runtime.promoterActivationState.value.copy(
            stage = PromoterActivationStage.Activating,
            errorMessage = null,
        )
    }

    private fun publishReady() {
        runtime.promoterActivationState.value = runtime.promoterActivationState.value.copy(
            stage = PromoterActivationStage.Ready,
            errorMessage = null,
        )
    }

    private fun publishActivationError(message: String) {
        runtime.promoterActivationState.value = runtime.promoterActivationState.value.copy(
            stage = PromoterActivationStage.Ready,
            errorMessage = message,
        )
    }
}

internal class PromoterActivationCleanupCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
    private val flow: PromoterActivationFlowState,
) {
    private var closeAfterCleanup = false
    private var stateAfterCleanup: PromoterActivationUiState? = null

    var isRequired: Boolean = false
        private set

    fun settleFailedProvisionalMarkerWrite() {
        settleProvisionalMarkerBeforeExposure(
            PromoterActivationUiState(
                stage = PromoterActivationStage.Error,
                errorMessage = runtime.strings.authFederatedUnavailable,
                retryAvailable = true,
            ),
        )
    }

    fun settleProvisionalMarkerBeforeExposure(errorState: PromoterActivationUiState) {
        if (dependencies.promoterActivationSessionStore.clear()) {
            flow.provisionalSessionMarkerPending = false
            runtime.promoterActivationState.value = errorState
        } else {
            begin(closeAfterCleanup = false, stateAfterCleanup = errorState)
        }
    }

    fun begin(closeAfterCleanup: Boolean, stateAfterCleanup: PromoterActivationUiState? = null) {
        isRequired = true
        this.closeAfterCleanup = closeAfterCleanup
        this.stateAfterCleanup = stateAfterCleanup
        cleanupImportedSession()
    }

    fun retry() {
        if (!isRequired) return
        cleanupImportedSession()
    }

    private fun cleanupImportedSession() {
        runtime.promoterActivationState.value = runtime.promoterActivationState.value.copy(
            stage = PromoterActivationStage.Cancelling,
            errorMessage = null,
        )
        runtime.promoterActivationJob = runtime.coroutineScope.launch {
            val signedOutState = dependencies.authPresenter.signOut(runtime.authState.value, runtime.strings)
            val signOutErrorMessage = signedOutState.errorMessage
            if (signOutErrorMessage != null) {
                publishCleanupError(signOutErrorMessage)
                return@launch
            }
            runtime.authState.value = signedOutState
            dependencies.googleIdentityProvider.clearCredentialState()
            if (!dependencies.promoterActivationSessionStore.clear()) {
                publishCleanupError(runtime.strings.authFederatedUnavailable)
                return@launch
            }
            completeCleanup()
        }
    }

    private fun completeCleanup() {
        isRequired = false
        flow.provisionalSessionMarkerPending = false
        if (closeAfterCleanup) {
            closePromoterFlow(runtime, flow)
            reset()
            return
        }
        flow.activationContext = null
        runtime.promoterActivationState.value = stateAfterCleanup ?: PromoterActivationUiState(
            stage = PromoterActivationStage.Error,
            errorMessage = runtime.strings.authFederatedUnavailable,
            retryAvailable = false,
        )
        reset()
    }

    private fun publishCleanupError(message: String) {
        runtime.promoterActivationState.value = runtime.promoterActivationState.value.copy(
            stage = PromoterActivationStage.Error,
            errorMessage = message,
            retryAvailable = true,
        )
    }

    private fun reset() {
        closeAfterCleanup = false
        stateAfterCleanup = null
    }
}

private fun PromoterActivationStage.isPromoterOperationRunning(): Boolean =
    this == PromoterActivationStage.Activating || this == PromoterActivationStage.Cancelling

private fun closePromoterFlow(runtime: AuthViewModelRuntime, flow: PromoterActivationFlowState) {
    flow.clear()
    runtime.promoterActivationState.value = PromoterActivationUiState()
    runtime.platformState.value = AuthPlatformUiState()
}

private const val MINIMUM_PROMOTER_PASSWORD_LENGTH = 8
