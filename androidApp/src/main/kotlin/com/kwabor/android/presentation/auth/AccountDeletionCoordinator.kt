package com.kwabor.android.presentation.auth

import com.kwabor.android.auth.GoogleIdentityResult
import com.kwabor.shared.domain.auth.AccountDeletionCredential
import com.kwabor.shared.domain.auth.AccountDeletionRequest
import com.kwabor.shared.domain.auth.SocialAuthProvider
import com.kwabor.shared.domain.auth.SocialSignInRequest
import com.kwabor.shared.presentation.auth.initialAuthUiState
import com.kwabor.shared.presentation.auth.initialRegistrationUiState
import kotlinx.coroutines.launch

internal class AccountDeletionCoordinator(
    private val runtime: AuthViewModelRuntime,
    private val dependencies: AuthViewModelDependencies,
) {
    private var pendingIdempotencyKey: String? = null
    private var accountDeletionCompleted = false

    fun clearSensitiveState() {
        runtime.accountDeletionJob?.cancel()
        runtime.accountDeletionJob = null
        pendingIdempotencyKey = null
        accountDeletionCompleted = false
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
        if (!canSubmit()) return
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
        submit(AccountDeletionCredential.Password(password))
    }

    private fun deleteWithGoogle(confirmation: String) {
        if (!canSubmit()) return
        if (
            !isAccountDeletionConfirmationValid(
                value = confirmation,
                expected = runtime.strings.authDeleteAccountConfirmationPhrase,
            )
        ) {
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
        runtime.accountDeletionJob = runtime.coroutineScope.launch {
            when (val credential = dependencies.googleIdentityProvider.acquireIdToken()) {
                GoogleIdentityResult.Cancelled -> publishReady()
                GoogleIdentityResult.Unavailable -> publishError(dependencies.googleIdentityUnavailableMessage)
                is GoogleIdentityResult.Success -> submitSocialCredential(credential)
            }
        }
    }

    private suspend fun submitSocialCredential(credential: GoogleIdentityResult.Success) {
        performDeletion(
            AccountDeletionCredential.Social(
                SocialSignInRequest(
                    provider = SocialAuthProvider.Google,
                    idToken = credential.idToken,
                    rawNonce = credential.nonce,
                ),
            ),
        )
    }

    private fun submit(credential: AccountDeletionCredential) {
        runtime.accessState.value = runtime.accessState.value.copy(
            accountDeletionInProgress = true,
            accountDeletionErrorMessage = null,
        )
        runtime.operationJob?.cancel()
        runtime.accountDeletionJob?.cancel()
        runtime.accountDeletionJob = runtime.coroutineScope.launch { performDeletion(credential) }
    }

    private suspend fun performDeletion(credential: AccountDeletionCredential) {
        val idempotencyKey = pendingIdempotencyKey ?: dependencies.idempotencyKeyProvider.create().also {
            pendingIdempotencyKey = it
        }
        val result = dependencies.authPresenter.deleteAccount(
            request = AccountDeletionRequest(idempotencyKey = idempotencyKey, credential = credential),
            strings = runtime.strings,
        )
        if (!result.isSuccess) {
            publishError(result.errorMessage ?: runtime.strings.authInvalidInput)
            return
        }
        pendingIdempotencyKey = null
        dependencies.googleIdentityProvider.clearCredentialState()
        accountDeletionCompleted = true
        runtime.effectChannel.send(AuthEffect.AccountDeleted)
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
        runtime.platformState.value = AuthPlatformUiState()
    }

    private fun canSubmit(): Boolean = runtime.authState.value.isAuthenticated &&
        runtime.accessState.value.accountDeletionDialogVisible &&
        !runtime.accessState.value.accountDeletionInProgress

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
