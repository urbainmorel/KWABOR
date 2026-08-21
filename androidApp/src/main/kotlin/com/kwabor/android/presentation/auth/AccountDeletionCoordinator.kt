package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.GoogleIdentityResult
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal class AccountDeletionCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
    private val providerCleanup: AccountDeletionProviderCleanupCoordinator,
) {
    private val canSubmit: Boolean
        get() = runtime.authState.value.isAuthenticated &&
            runtime.accessState.value.accountDeletionDialogVisible &&
            !runtime.accessState.value.accountDeletionInProgress

    private val interactionLifecycle = AccountDeletionInteractionLifecycle(
        runtime = runtime,
        dependencies = dependencies,
        publishReady = ::publishReady,
        publishError = ::publishError,
    )
    private var pendingIdempotencyKey: String? = null
    private var accountDeletionCompleted = false
    private val outcomeHandler = AccountDeletionOutcomeHandler(
        runtime = runtime,
        interactionLifecycle = interactionLifecycle,
        providerCleanup = providerCleanup,
        callbacks = AccountDeletionTerminalCallbacks(
            consumeIdempotencyKey = { pendingIdempotencyKey = null },
            markDeletionCompleted = {
                accountDeletionCompleted = true
                runtime.accountDeletionNavigationPending.value = true
            },
            publishReady = ::publishReady,
            publishError = ::publishError,
        ),
    )
    private val ownedBlockRunner = AccountDeletionOwnedBlockRunner(
        interactionLifecycle = interactionLifecycle,
        outcomeHandler = outcomeHandler,
        purgeWorker = AccountDeletionPurgeWorker(
            workerScope = dependencies.accountDeletionWorkerScope,
            registry = dependencies.accountDeletionPurgeRegistry,
            purge = dependencies.purgePrivateDataForAccountDeletion,
            resume = dependencies.resumePrivateDataAfterAccountDeletionFailure,
        ),
        unexpectedErrorMessage = runtime.strings.authFederatedUnavailable,
    )

    fun clearSensitiveState() {
        runtime.accountDeletionJob?.cancel()
        runtime.accountDeletionJob = null
        pendingIdempotencyKey = null
    }

    fun handle(intent: AuthIntent.AccountSecurity) {
        when (intent) {
            AuthIntent.RequestAccountDeletion -> request()
            AuthIntent.CancelAccountDeletion -> cancel()
            AuthIntent.AccountDeletionNavigationHandled -> completeNavigation()
            is AuthIntent.DeleteAccountWithPassword -> deleteWithPassword(
                password = intent.password,
                confirmation = intent.confirmation,
            )
            is AuthIntent.DeleteAccountWithGoogle -> deleteWithGoogle(intent.confirmation)
        }
    }

    private fun request() {
        if (!runtime.authState.value.isAuthenticated || runtime.accessState.value.accountDeletionInProgress) return
        pendingIdempotencyKey = null
        runtime.accessState.value = runtime.accessState.value.copy(
            accountDeletionDialogVisible = true,
            accountDeletionErrorMessage = null,
        )
    }

    private fun cancel() {
        if (runtime.accessState.value.accountDeletionInProgress) return
        pendingIdempotencyKey = null
        runtime.accessState.value = runtime.accessState.value.copy(
            accountDeletionDialogVisible = false,
            accountDeletionErrorMessage = null,
        )
    }

    private fun deleteWithPassword(password: String, confirmation: String) {
        if (!canSubmit) return
        val expectedAccountId = interactionLifecycle.captureAuthenticatedAccountId() ?: run {
            publishError(runtime.strings.authSessionExpired)
            return
        }
        if (
            password.isBlank() ||
            !isAccountDeletionConfirmationValid(
                value = confirmation,
                expected = runtime.strings.authDeleteAccountConfirmationPhrase,
            )
        ) {
            publishError(runtime.strings.authInvalidInput)
            return
        }
        if (!revokeObservabilityConsent()) return
        submit(expectedAccountId, AccountDeletionCredential.Password(password))
    }

    private fun deleteWithGoogle(confirmation: String) {
        if (!canSubmit) return
        val expectedAccountId = interactionLifecycle.captureAuthenticatedAccountId() ?: run {
            publishError(runtime.strings.authSessionExpired)
            return
        }
        if (!isAccountDeletionConfirmationValid(confirmation, runtime.strings.authDeleteAccountConfirmationPhrase)) {
            publishError(runtime.strings.authInvalidInput)
            return
        }
        if (!revokeObservabilityConsent()) return
        runtime.accessState.value = runtime.accessState.value.copy(
            accountDeletionInProgress = true,
            accountDeletionErrorMessage = null,
        )
        runtime.operationJob?.cancel()
        runtime.accountDeletionJob?.cancel()
        runtime.accountDeletionJob = runtime.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ownedBlockRunner.run(expectedAccountId) deletion@{ onRemoteAttemptStarted ->
                if (!interactionLifecycle.verifyCapturedAccount(expectedAccountId)) return@deletion
                when (val credential = dependencies.googleIdentityProvider.acquireIdToken()) {
                    GoogleIdentityResult.Cancelled -> interactionLifecycle.resumeAfterFailure(
                        expectedAccountId,
                        errorMessage = null,
                    )
                    GoogleIdentityResult.Unavailable -> interactionLifecycle.resumeAfterFailure(
                        expectedAccountId = expectedAccountId,
                        errorMessage = dependencies.googleIdentityUnavailableMessage,
                    )
                    is GoogleIdentityResult.Success -> submitSocialCredential(
                        expectedAccountId,
                        credential,
                        onRemoteAttemptStarted,
                    )
                }
            }
        }
    }

    private suspend fun submitSocialCredential(
        expectedAccountId: String,
        credential: GoogleIdentityResult.Success,
        onRemoteAttemptStarted: () -> Unit,
    ) {
        performDeletion(
            expectedAccountId = expectedAccountId,
            credential = AccountDeletionCredential.Social(
                SocialSignInRequest(
                    provider = SocialAuthProvider.Google,
                    idToken = credential.idToken,
                    rawNonce = credential.nonce,
                ),
            ),
            onRemoteAttemptStarted = onRemoteAttemptStarted,
        )
    }

    private fun submit(expectedAccountId: String, credential: AccountDeletionCredential) {
        runtime.accessState.value = runtime.accessState.value.copy(
            accountDeletionInProgress = true,
            accountDeletionErrorMessage = null,
        )
        runtime.operationJob?.cancel()
        runtime.accountDeletionJob?.cancel()
        runtime.accountDeletionJob = runtime.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ownedBlockRunner.run(expectedAccountId) { onRemoteAttemptStarted ->
                performDeletion(expectedAccountId, credential, onRemoteAttemptStarted)
            }
        }
    }

    private suspend fun performDeletion(
        expectedAccountId: String,
        credential: AccountDeletionCredential,
        onRemoteAttemptStarted: () -> Unit,
    ) {
        if (!interactionLifecycle.verifyCapturedAccount(expectedAccountId)) return
        val idempotencyKey = pendingIdempotencyKey ?: dependencies.idempotencyKeyProvider.create().also {
            pendingIdempotencyKey = it
        }
        if (!providerCleanup.armBeforeRemoteBoundary()) {
            outcomeHandler.finishResolvedPreTransportFailure(
                expectedAccountId = expectedAccountId,
                errorMessage = runtime.strings.settings.privacyPersistenceError,
            )
            return
        }
        onRemoteAttemptStarted()
        val result = dependencies.authPresenter.deleteAccount(
            request = AccountDeletionRequest(
                expectedAccountId = expectedAccountId,
                idempotencyKey = idempotencyKey,
                credential = credential,
            ),
            strings = runtime.strings,
        )
        outcomeHandler.finish(result, expectedAccountId)
    }

    private fun revokeObservabilityConsent(): Boolean {
        if (dependencies.revokeObservabilityConsent()) return true
        publishError(runtime.strings.settings.privacyPersistenceError)
        return false
    }

    private fun completeNavigation() {
        if (!accountDeletionCompleted) return
        accountDeletionCompleted = false
        runtime.authState.value = initialAuthUiState()
        runtime.registrationState.value = initialRegistrationUiState()
        runtime.accessState.value = AuthAccessUiState()
        runtime.platformState.value = if (
            runtime.sessionRestoreStatus.value == AuthSessionRestoreStatus.Failed
        ) {
            AuthPlatformUiState(surface = AuthSurface.SessionRestoreFailure)
        } else {
            AuthPlatformUiState()
        }
        runtime.accountDeletionNavigationPending.value = false
    }

    private fun publishReady() {
        runtime.accessState.value = runtime.accessState.value.copy(
            accountDeletionInProgress = false,
            accountDeletionErrorMessage = null,
        )
    }

    private fun publishError(message: String) {
        runtime.accessState.value = runtime.accessState.value.copy(
            accountDeletionInProgress = false,
            accountDeletionErrorMessage = message,
        )
    }
}

internal fun isAccountDeletionConfirmationValid(value: String, expected: String): Boolean = value.trim() == expected
